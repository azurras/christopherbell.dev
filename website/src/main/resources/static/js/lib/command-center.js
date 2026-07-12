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
  const value = Number(reading.value).toLocaleString(undefined, { maximumFractionDigits: 1 });
  const units = {
    percent: '%',
    celsius: '°C',
    seconds: 's',
    megabytes: 'MB',
    watts: 'W',
    'bytes/second': 'B/s',
    state: '',
  };
  const suffix = Object.hasOwn(units, reading.unit) ? units[reading.unit] : (reading.unit || '');
  return suffix ? `${value} ${suffix}` : value;
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

/** Calculate a stable pending-action countdown from an absolute execution time. */
export function actionCountdown(executeAt, now = Date.now(), cancellable = false) {
  const remaining = Date.parse(executeAt) - Number(now);
  const seconds = Number.isFinite(remaining) ? Math.max(0, Math.ceil(remaining / 1000)) : 0;
  return { seconds, cancellable: Boolean(cancellable), expired: seconds === 0 };
}
