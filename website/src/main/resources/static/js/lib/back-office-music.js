import { sanitize } from './util.js';

export function musicAccessAttemptMarkup(attempts) {
  if (!Array.isArray(attempts) || attempts.length === 0) {
    return '<div class="empty-state">No denied Music access attempts were recorded.</div>';
  }
  return attempts.map(attempt => `
    <article class="queue-card">
      <div class="queue-card-main">
        <strong>${sanitize(attempt.reason || 'ACCESS_DENIED')}</strong>
        <span>${sanitize(attempt.principalType || 'UNKNOWN')}: ${sanitize(attempt.principal || 'unknown')}</span>
      </div>
      <div class="queue-card-meta">
        <span>${sanitize(attempt.count || 0)} attempt(s)</span>
        <time>${sanitize(attempt.lastAttemptAt ? new Date(attempt.lastAttemptAt).toLocaleString() : '—')}</time>
      </div>
    </article>`).join('');
}
