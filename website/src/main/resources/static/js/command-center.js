import { API } from './lib/api.js';
import { authHeaders, fetchJson } from './lib/util.js';
import {
  ACTION_PHRASES,
  actionCountdown,
  clearActionDialogState,
  displayMetric,
  isAccessDenied,
  metricState,
  nextLogPageState,
  nextPollDelay,
  pollFailureDecision,
  pollRequestDecision,
  shouldPoll,
} from './lib/command-center.js';

const root = document.querySelector('#commandCenterRoot');
const healthBadge = document.querySelector('#commandHealthBadge');
const sampleAge = document.querySelector('#commandSampleAge');
const connectionStatus = document.querySelector('#commandConnectionStatus');
const alertStrip = document.querySelector('#commandAlertStrip');
const metricGrid = document.querySelector('#commandMetricGrid');
const version = document.querySelector('#commandVersion');
const logOutput = document.querySelector('#commandLogOutput');
const logStatus = document.querySelector('#commandLogStatus');
const logLevel = document.querySelector('#commandLogLevel');
const logQuery = document.querySelector('#commandLogQuery');
const logPause = document.querySelector('#commandLogPause');
const logAutoscroll = document.querySelector('#commandLogAutoscroll');
const actionDialog = document.querySelector('#commandActionDialog');
const actionForm = document.querySelector('#commandActionForm');
const passwordInput = document.querySelector('#commandActionPassword');
const phraseInput = document.querySelector('#commandActionPhrase');
const requiredPhrase = document.querySelector('#commandRequiredPhrase');
const actionSubmit = document.querySelector('#commandActionSubmit');
const actionStatus = document.querySelector('#commandActionStatus');
const dialogStatus = document.querySelector('#commandDialogStatus');
const pendingPanel = document.querySelector('#commandPendingAction');
const pendingText = document.querySelector('#commandPendingText');
const cancelAction = document.querySelector('#commandCancelAction');

let pollTimer;
let sampleTimer;
let searchTimer;
let failures = 0;
let logsPaused = false;
let logState = { generation: 0, cursor: null, lastAppliedNextCursor: undefined };
let latestSnapshot = null;
let currentChallenge = null;
let actionInFlight = false;
let pollGeneration = 0;
let pollController = null;
let pollInFlight = false;
let pollRequested = false;
let accessRevoked = false;

function redirectLostSignal() {
  window.location.replace('/404');
}

/** Verify the exact server-reported role before revealing or populating the shell. */
async function gateCommandCenter() {
  if (!localStorage.getItem('cbellLoginToken')) {
    redirectLostSignal();
    return;
  }
  try {
    const account = await fetchJson(API.accounts.me, { headers: authHeaders() });
    if (account?.role !== 'ADMIN') {
      revokeCommandCenterAccess();
      return;
    }
    localStorage.setItem('cbellRole', 'ADMIN');
    root.classList.remove('d-none');
    wireControls();
    await pollNow();
  } catch (error) {
    revokeCommandCenterAccess(error);
  }
}

function wireControls() {
  logPause.addEventListener('click', () => {
    logsPaused = !logsPaused;
    logPause.textContent = logsPaused ? 'Resume' : 'Pause';
    logPause.setAttribute('aria-pressed', String(logsPaused));
    logStatus.textContent = logsPaused ? 'Log stream paused' : 'Log stream resumed';
    restartPollGeneration();
  });
  logLevel.addEventListener('change', resetLogStream);
  logQuery.addEventListener('input', () => {
    window.clearTimeout(searchTimer);
    invalidateLogStream();
    searchTimer = window.setTimeout(requestPoll, 300);
  });
  document.querySelector('#commandLogClear').addEventListener('click', () => {
    logOutput.replaceChildren();
    logStatus.textContent = 'Visible log records cleared';
    restartPollGeneration();
  });
  document.querySelector('#commandLogCopy').addEventListener('click', copyVisibleLogs);
  document.querySelectorAll('[data-command-action]').forEach(button => {
    button.addEventListener('click', () => openActionDialog(button.dataset.commandAction));
  });
  document.querySelector('#commandActionDismiss').addEventListener('click', closeActionDialog);
  actionDialog.addEventListener('cancel', event => {
    event.preventDefault();
    closeActionDialog();
  });
  actionDialog.addEventListener('close', clearDialogState);
  actionForm.addEventListener('submit', confirmAction);
  cancelAction.addEventListener('click', cancelPendingAction);
  document.addEventListener('visibilitychange', handleVisibilityChange);
  sampleTimer = window.setInterval(updateTimeDisplays, 1000);
}

function resetLogStream() {
  invalidateLogStream();
  requestPoll();
}

function invalidateLogStream() {
  invalidatePoll(false);
  logState = { generation: pollGeneration, cursor: null, lastAppliedNextCursor: undefined };
  logOutput.replaceChildren();
}

async function copyVisibleLogs() {
  try {
    await navigator.clipboard.writeText(logOutput.textContent || '');
    logStatus.textContent = 'Visible log records copied';
  } catch (_) {
    logStatus.textContent = 'Unable to copy visible log records';
  }
}

function handleVisibilityChange() {
  invalidatePoll(false);
  if (document.hidden) {
    connectionStatus.textContent = 'Mission Control polling paused while this tab is hidden';
    return;
  }
  connectionStatus.textContent = 'Mission Control polling resumed';
  logState = { ...logState, generation: pollGeneration };
  requestPoll();
}

async function pollNow() {
  window.clearTimeout(pollTimer);
  const decision = pollRequestDecision({
    hidden: document.hidden, revoked: accessRevoked, inFlight: pollInFlight,
  });
  if (decision === 'ignore') return;
  if (decision === 'queue') {
    pollRequested = true;
    return;
  }
  pollInFlight = true;
  pollRequested = false;
  const generation = pollGeneration;
  const controller = new AbortController();
  pollController = controller;
  try {
    const snapshot = await fetchJson(API.admin.commandCenter.snapshot, {
      headers: authHeaders(), signal: controller.signal,
    });
    if (!isCurrentPoll(generation, controller)) return;
    latestSnapshot = snapshot;
    failures = 0;
    renderSnapshot(snapshot);
    connectionStatus.textContent = 'Mission Control connected';
    if (shouldPoll(document.hidden, logsPaused)) await pollLogs(generation, controller);
  } catch (error) {
    const failureDecision = pollFailureDecision(error, isCurrentPoll(generation, controller));
    if (failureDecision === 'revoke') {
      revokeCommandCenterAccess(error);
    } else if (failureDecision === 'retry') {
      failures += 1;
      renderOffline(error);
    }
  } finally {
    if (pollController === controller) pollController = null;
    pollInFlight = false;
    if (accessRevoked || document.hidden) return;
    if (pollRequested) {
      pollRequested = false;
      queueMicrotask(pollNow);
    } else {
      pollTimer = window.setTimeout(pollNow, nextPollDelay(failures));
    }
  }
}

function isCurrentPoll(generation, controller) {
  return !accessRevoked && generation === pollGeneration
    && pollController === controller && !controller.signal.aborted;
}

function invalidatePoll(restart = true) {
  pollGeneration += 1;
  window.clearTimeout(pollTimer);
  pollRequested = restart && !document.hidden && !accessRevoked;
  pollController?.abort();
  if (!pollInFlight && pollRequested) requestPoll();
}

function restartPollGeneration() {
  invalidatePoll(false);
  logState = { ...logState, generation: pollGeneration };
  requestPoll();
}

function requestPoll() {
  if (accessRevoked || document.hidden) return;
  if (pollInFlight) {
    pollRequested = true;
    return;
  }
  pollRequested = false;
  void pollNow();
}

function handleRequestFailure(error) {
  if (!isAccessDenied(error)) return false;
  revokeCommandCenterAccess(error);
  return true;
}

function revokeCommandCenterAccess() {
  if (accessRevoked) return;
  accessRevoked = true;
  root.classList.add('d-none');
  invalidatePoll(false);
  window.clearInterval(sampleTimer);
  window.clearTimeout(searchTimer);
  clearDialogState();
  if (actionDialog.open) actionDialog.close();
  redirectLostSignal();
}

function renderOffline(error) {
  healthBadge.textContent = 'OFFLINE';
  healthBadge.className = 'command-health-badge is-offline';
  connectionStatus.textContent = `${error?.message || 'Connection lost'}. Reconnecting.`;
}

function renderSnapshot(snapshot) {
  const health = String(snapshot?.health || 'OFFLINE');
  healthBadge.textContent = health.replaceAll('_', ' ');
  healthBadge.className = `command-health-badge is-${health.toLowerCase().replaceAll('_', '-')}`;
  version.textContent = snapshot?.applicationVersion
    ? `Build ${snapshot.applicationVersion}` : 'Version unavailable';
  renderAlerts(snapshot?.alerts || []);
  renderMetrics(snapshot?.metrics || [], snapshot?.history || {}, snapshot?.applicationUptimeSeconds);
  renderPendingAction(snapshot?.pendingAction);
  updateTimeDisplays();
}

function renderAlerts(alerts) {
  alertStrip.replaceChildren();
  alertStrip.classList.toggle('d-none', alerts.length === 0);
  alerts.forEach(alert => {
    const item = document.createElement('p');
    item.className = `command-alert severity-${String(alert.severity || 'warning').toLowerCase()}`;
    const code = document.createElement('strong');
    code.textContent = alert.code || 'ALERT';
    item.append(code, document.createTextNode(` — ${alert.message || 'Operational warning'}`));
    alertStrip.append(item);
  });
}

function renderMetrics(metrics, history, uptimeSeconds) {
  metricGrid.replaceChildren();
  metrics.forEach(reading => metricGrid.append(metricCard(reading, history[reading.key] || [])));
  metricGrid.append(metricCard({
    key: 'application.uptime', label: 'Application uptime', value: uptimeSeconds,
    unit: 'seconds', status: uptimeSeconds == null ? 'UNAVAILABLE' : 'AVAILABLE', detail: null,
  }, []));
}

function metricCard(reading, points) {
  const state = metricState(reading);
  const card = document.createElement('article');
  card.className = `command-metric-card is-${state}`;
  const label = document.createElement('span');
  label.className = 'command-metric-label';
  label.textContent = reading.label || reading.key || 'Metric';
  const valueNode = document.createElement('strong');
  valueNode.className = 'command-metric-value';
  valueNode.textContent = displayMetric(reading);
  const status = document.createElement('span');
  status.className = 'command-metric-status';
  status.textContent = state === 'available' ? 'Live' : state;
  card.append(label, valueNode, status, sparkline(points, `${reading.label || reading.key} 15 minute trend`));
  if (reading.detail) {
    const detail = document.createElement('small');
    detail.textContent = reading.detail;
    card.append(detail);
  }
  return card;
}

function sparkline(points, label) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'command-sparkline');
  svg.setAttribute('viewBox', '0 0 160 42');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', label);
  const values = points.map(point => Number(point.value)).filter(Number.isFinite);
  if (values.length < 2) return svg;
  const min = Math.min(...values);
  const range = Math.max(Math.max(...values) - min, 1);
  const polyline = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
  polyline.setAttribute('points', values.map((value, index) => {
    const x = index * 160 / (values.length - 1);
    const y = 38 - ((value - min) / range * 34);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(' '));
  svg.append(polyline);
  return svg;
}

async function pollLogs(generation, controller) {
  const request = { generation, cursor: logState.cursor };
  const params = new URLSearchParams({ level: logLevel.value, query: logQuery.value });
  if (request.cursor) params.set('cursor', request.cursor);
  try {
    const page = await fetchJson(`${API.admin.commandCenter.logs}?${params}`, {
      headers: authHeaders(), signal: controller.signal,
    });
    if (!isCurrentPoll(generation, controller)) return;
    const decision = nextLogPageState(logState, request, page);
    if (!decision.apply) return;
    if (page.reset) logOutput.replaceChildren();
    (page.records || []).forEach(appendLogRecord);
    logState = {
      generation: decision.generation,
      cursor: decision.cursor,
      lastAppliedNextCursor: decision.lastAppliedNextCursor,
    };
    logStatus.textContent = page.status || `${(page.records || []).length} new records`;
    if (logAutoscroll.checked) logOutput.scrollTop = logOutput.scrollHeight;
  } catch (error) {
    if (error?.name === 'AbortError') return;
    if (handleRequestFailure(error)) return;
    logStatus.textContent = error?.message || 'Log stream unavailable';
  }
}

/** Insert server logs only through the DOM text channel. */
function appendLogRecord(record) {
  const line = document.createElement('div');
  line.className = `command-log-line level-${String(record.level || 'INFO').toLowerCase()}`;
  line.textContent = record.text || '';
  logOutput.append(line);
}

async function openActionDialog(action) {
  if (actionInFlight || !Object.hasOwn(ACTION_PHRASES, action)) return;
  clearDialogState();
  actionStatus.textContent = 'Requesting a fresh action challenge…';
  try {
    currentChallenge = await fetchJson(API.admin.commandCenter.challenges, {
      method: 'POST', headers: authHeaders(), body: JSON.stringify({ action }),
    });
    requiredPhrase.textContent = currentChallenge.confirmationPhrase;
    phraseInput.value = '';
    passwordInput.value = '';
    actionStatus.textContent = '';
    actionDialog.showModal();
    passwordInput.focus();
  } catch (error) {
    if (handleRequestFailure(error)) return;
    actionStatus.textContent = error?.message || 'Unable to create action challenge';
  }
}

function closeActionDialog() {
  clearDialogState();
  if (actionDialog.open) actionDialog.close();
}

function clearDialogState() {
  currentChallenge = clearActionDialogState({
    passwordInput, phraseInput, requiredPhrase, dialogStatus,
  });
}

async function confirmAction(event) {
  event.preventDefault();
  if (actionInFlight || !currentChallenge) return;
  const password = passwordInput.value;
  passwordInput.value = '';
  const confirmationPhrase = phraseInput.value;
  if (confirmationPhrase !== currentChallenge.confirmationPhrase) {
    dialogStatus.textContent = 'The confirmation phrase must match exactly.';
    phraseInput.focus();
    return;
  }
  actionInFlight = true;
  actionSubmit.disabled = true;
  try {
    const result = await fetchJson(API.admin.commandCenter.actions, {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({
        challengeId: currentChallenge.id,
        action: currentChallenge.action,
        password,
        confirmationPhrase,
      }),
    });
    actionStatus.textContent = `${String(result.action).replaceAll('_', ' ')} accepted.`;
    closeActionDialog();
    restartPollGeneration();
  } catch (error) {
    if (handleRequestFailure(error)) return;
    dialogStatus.textContent = error?.message || 'Action was not accepted';
  } finally {
    actionInFlight = false;
    actionSubmit.disabled = false;
  }
}

function renderPendingAction(pendingAction) {
  pendingPanel.classList.toggle('d-none', !pendingAction);
  cancelAction.disabled = !pendingAction?.cancellable;
  if (pendingAction) updatePendingCountdown(pendingAction);
}

function updatePendingCountdown(pendingAction = latestSnapshot?.pendingAction) {
  if (!pendingAction) return;
  const countdown = actionCountdown(pendingAction.executeAt, Date.now(), pendingAction.cancellable);
  pendingText.textContent = `${String(pendingAction.action).replaceAll('_', ' ')} in ${countdown.seconds}s`;
  cancelAction.classList.toggle('d-none', !countdown.cancellable || countdown.expired);
}

async function cancelPendingAction() {
  if (actionInFlight) return;
  actionInFlight = true;
  cancelAction.disabled = true;
  try {
    await fetchJson(API.admin.commandCenter.cancel, { method: 'POST', headers: authHeaders() });
    actionStatus.textContent = 'Pending machine action cancelled.';
    restartPollGeneration();
  } catch (error) {
    if (handleRequestFailure(error)) return;
    actionStatus.textContent = error?.message || 'Unable to cancel pending action';
  } finally {
    actionInFlight = false;
    cancelAction.disabled = false;
  }
}

function updateTimeDisplays() {
  const sampledAt = Date.parse(latestSnapshot?.sampledAt);
  sampleAge.textContent = Number.isFinite(sampledAt)
    ? `Sample ${Math.max(0, Math.floor((Date.now() - sampledAt) / 1000))}s old`
    : 'No sample';
  updatePendingCountdown();
}

if (root) gateCommandCenter();
