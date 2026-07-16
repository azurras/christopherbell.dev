/** Pure display and scheduling helpers for Mission Control. */
export const POLL_INTERVAL_MS = 5000;

export const ACTION_PHRASES = Object.freeze({
  RESTART_SITE: 'RESTART SITE',
  RESTART_COMPUTER: 'RESTART COMPUTER',
  SHUTDOWN_COMPUTER: 'SHUTDOWN COMPUTER',
});

/** Format an API metric while preserving explicit unavailable states. */
export function displayMetric(reading) {
  if (!reading || reading.status === 'UNAVAILABLE' || reading.status === 'ERROR'
      || reading.value == null || !Number.isFinite(Number(reading.value))) {
    return 'Unavailable';
  }
  if (reading.key === 'production.service.running') {
    return Number(reading.value) === 1 ? 'Running' : 'Stopped';
  }
  if (reading.unit === 'epoch-seconds') {
    const timestamp = new Date(Number(reading.value) * 1000);
    return Number.isNaN(timestamp.getTime())
      ? 'Unavailable'
      : timestamp.toISOString().replace('T', ' ').replace('.000Z', ' UTC');
  }
  if (reading.unit === 'commit') {
    const detail = reading.detail == null ? '' : String(reading.detail);
    if (!detail) return 'Unavailable';
    return /^[A-Za-z0-9._-]{1,64}$/.test(detail) ? detail.slice(0, 8) : detail;
  }
  if (reading.unit === 'bytes') return formatBinaryMetric(Number(reading.value), false);
  if (reading.unit === 'bytes/second') return formatBinaryMetric(Number(reading.value), true);
  if (reading.unit === 'seconds') return formatDuration(Number(reading.value));
  const value = Number(reading.value).toLocaleString(undefined, { maximumFractionDigits: 1 });
  const units = {
    percent: '%',
    celsius: '°C',
    megabytes: 'MB',
    watts: 'W',
    'bytes/second': 'B/s',
    state: '',
  };
  const suffix = Object.hasOwn(units, reading.unit) ? units[reading.unit] : (reading.unit || '');
  return suffix ? `${value} ${suffix}` : value;
}

/** Return optional secondary metric text without duplicating commit values. */
export function metricDetail(reading) {
  const detail = reading?.detail == null ? '' : String(reading.detail);
  if (!detail || reading?.unit === 'commit') return null;
  return detail;
}

/** Preserve the complete commit identifier as accessible title text. */
export function metricTitle(reading) {
  if (reading?.unit !== 'commit' || !reading?.detail) return null;
  return String(reading.detail);
}

function formatDuration(value) {
  const totalSeconds = Math.max(0, Math.floor(value));
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const totalMinutes = Math.floor(totalSeconds / 60);
  if (totalMinutes < 60) return `${totalMinutes}m ${totalSeconds % 60}s`;
  const totalHours = Math.floor(totalMinutes / 60);
  if (totalHours < 24) return `${totalHours}h ${totalMinutes % 60}m`;
  return `${Math.floor(totalHours / 24)}d ${totalHours % 24}h`;
}

function formatBinaryMetric(value, perSecond) {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let scaled = Math.abs(value);
  let unitIndex = 0;
  while (scaled >= 1024 && unitIndex < units.length - 1) {
    scaled /= 1024;
    unitIndex += 1;
  }
  if (value < 0) scaled *= -1;
  const formatted = scaled.toLocaleString(undefined, { maximumFractionDigits: 1 });
  return `${formatted} ${units[unitIndex]}${perSecond ? '/s' : ''}`;
}

/** Resolve the CSS/display state for a reading. */
export function metricState(reading) {
  if (reading?.status === 'STALE') return 'stale';
  if (!reading || reading.status === 'UNAVAILABLE' || reading.status === 'ERROR'
      || reading.value == null) return 'unavailable';
  return 'available';
}

/** Five-second polling with bounded reconnect backoff. */
export function nextPollDelay(failures) {
  const exponent = Math.min(Math.max(Number(failures) || 0, 0), 3);
  return Math.min(30_000, POLL_INTERVAL_MS * (2 ** exponent));
}

/** Poll only while the document and selected stream are active. */
export function shouldPoll(documentHidden, paused) {
  return !documentHidden && !paused;
}

/** Decide whether a poll request starts, queues behind the active request, or is ignored. */
export function pollRequestDecision({ hidden, revoked, inFlight }) {
  if (hidden || revoked) return 'ignore';
  return inFlight ? 'queue' : 'start';
}

/** Classify poll failures without allowing stale generations to mask access loss. */
export function pollFailureDecision(error, current) {
  if (isAccessDenied(error)) return 'revoke';
  if (error?.name === 'AbortError' || !current) return 'ignore';
  return 'retry';
}

/** Identify authorization failures that require tearing down the private console. */
export function isAccessDenied(error) {
  return error?.status === 401 || error?.status === 403;
}

/**
 * Decide whether a log page still belongs to the active filter/poll generation.
 * Returned cursor presence is significant: an explicit empty cursor resets state.
 */
export function nextLogPageState(state, request, page) {
  if (request.generation !== state.generation || request.cursor !== state.cursor) {
    return { ...state, apply: false };
  }
  const cursor = Object.hasOwn(page, 'nextCursor') ? page.nextCursor : state.cursor;
  const records = Array.isArray(page.records) ? page.records : [];
  if (records.length > 0 && cursor === state.cursor && state.lastAppliedNextCursor === cursor) {
    return { ...state, apply: false };
  }
  return {
    apply: true,
    generation: state.generation,
    cursor,
    lastAppliedNextCursor: cursor,
  };
}

/** Build clipboard-safe plain text from the visible rendered log rows. */
export function visibleLogText(rows) {
  return Array.from(rows || [], row => String(row?.textContent ?? '')).join('\n');
}

/** Clear every sensitive or transient value owned by the native action dialog. */
export function clearActionDialogState({
  passwordInput, phraseInput, requiredPhrase, dialogStatus,
}) {
  passwordInput.value = '';
  phraseInput.value = '';
  requiredPhrase.textContent = '';
  dialogStatus.textContent = '';
  return null;
}

/** Calculate a stable pending-action countdown from an absolute execution time. */
export function actionCountdown(executeAt, now = Date.now(), cancellable = false) {
  const remaining = Date.parse(executeAt) - Number(now);
  const seconds = Number.isFinite(remaining) ? Math.max(0, Math.ceil(remaining / 1000)) : 0;
  return { seconds, cancellable: Boolean(cancellable), expired: seconds === 0 };
}
