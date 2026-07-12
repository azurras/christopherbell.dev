import { API } from './lib/api.js';
import { authHeaders, fetchJson } from './lib/util.js';
import {
  ACTION_PHRASES,
  actionCountdown,
  displayMetric,
  metricState,
  nextPollDelay,
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
const pendingPanel = document.querySelector('#commandPendingAction');
const pendingText = document.querySelector('#commandPendingText');
const cancelAction = document.querySelector('#commandCancelAction');

let pollTimer;
let sampleTimer;
let searchTimer;
let failures = 0;
let logsPaused = false;
let logCursor = null;
let latestSnapshot = null;
let currentChallenge = null;
let actionInFlight = false;

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
    const response = await fetch(API.accounts.me, { headers: authHeaders() });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body?.payload?.role !== 'ADMIN') {
      redirectLostSignal();
      return;
    }
    localStorage.setItem('cbellRole', 'ADMIN');
    root.classList.remove('d-none');
    wireControls();
    await pollNow();
  } catch (_) {
    redirectLostSignal();
  }
}

function wireControls() {
  logPause.addEventListener('click', () => {
    logsPaused = !logsPaused;
    logPause.textContent = logsPaused ? 'Resume' : 'Pause';
    logPause.setAttribute('aria-pressed', String(logsPaused));
    logStatus.textContent = logsPaused ? 'Log stream paused' : 'Log stream resumed';
    if (!logsPaused && !document.hidden) pollNow();
  });
  logLevel.addEventListener('change', resetLogStream);
  logQuery.addEventListener('input', () => {
    window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(resetLogStream, 300);
  });
  document.querySelector('#commandLogClear').addEventListener('click', () => {
    logOutput.replaceChildren();
    logStatus.textContent = 'Visible log records cleared';
  });
  document.querySelector('#commandLogCopy').addEventListener('click', copyVisibleLogs);
  document.querySelectorAll('[data-command-action]').forEach(button => {
    button.addEventListener('click', () => openActionDialog(button.dataset.commandAction));
  });
  document.querySelector('#commandActionDismiss').addEventListener('click', closeActionDialog);
  actionForm.addEventListener('submit', confirmAction);
  cancelAction.addEventListener('click', cancelPendingAction);
  document.addEventListener('visibilitychange', handleVisibilityChange);
  sampleTimer = window.setInterval(updateTimeDisplays, 1000);
}

function resetLogStream() {
  logCursor = null;
  logOutput.replaceChildren();
  if (!logsPaused && !document.hidden) pollNow();
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
  window.clearTimeout(pollTimer);
  if (document.hidden) {
    connectionStatus.textContent = 'Mission Control polling paused while this tab is hidden';
    return;
  }
  connectionStatus.textContent = 'Mission Control polling resumed';
  pollNow();
}

async function pollNow() {
  window.clearTimeout(pollTimer);
  if (!shouldPoll(document.hidden, false)) return;
  try {
    const snapshot = await fetchJson(API.admin.commandCenter.snapshot, { headers: authHeaders() });
    latestSnapshot = snapshot;
    failures = 0;
    renderSnapshot(snapshot);
    connectionStatus.textContent = 'Mission Control connected';
    if (shouldPoll(document.hidden, logsPaused)) await pollLogs();
  } catch (error) {
    failures += 1;
    renderOffline(error);
  } finally {
    if (!document.hidden) pollTimer = window.setTimeout(pollNow, nextPollDelay(failures));
  }
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

async function pollLogs() {
  const params = new URLSearchParams({ level: logLevel.value, query: logQuery.value });
  if (logCursor) params.set('cursor', logCursor);
  try {
    const page = await fetchJson(`${API.admin.commandCenter.logs}?${params}`, { headers: authHeaders() });
    if (page.reset) logOutput.replaceChildren();
    (page.records || []).forEach(appendLogRecord);
    logCursor = page.nextCursor || logCursor;
    logStatus.textContent = page.status || `${(page.records || []).length} new records`;
    if (logAutoscroll.checked) logOutput.scrollTop = logOutput.scrollHeight;
  } catch (error) {
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
    actionStatus.textContent = error?.message || 'Unable to create action challenge';
  }
}

function closeActionDialog() {
  currentChallenge = null;
  passwordInput.value = '';
  phraseInput.value = '';
  actionDialog.close();
}

async function confirmAction(event) {
  event.preventDefault();
  if (actionInFlight || !currentChallenge) return;
  const password = passwordInput.value;
  passwordInput.value = '';
  const confirmationPhrase = phraseInput.value;
  if (confirmationPhrase !== currentChallenge.confirmationPhrase) {
    actionStatus.textContent = 'The confirmation phrase must match exactly.';
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
    await pollNow();
  } catch (error) {
    actionStatus.textContent = error?.message || 'Action was not accepted';
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
    await pollNow();
  } catch (error) {
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
