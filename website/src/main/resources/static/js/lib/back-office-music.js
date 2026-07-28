function escapeHtml(value) {
  return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
}

export function musicAccessAttemptMarkup(attempts) {
  if (!Array.isArray(attempts) || attempts.length === 0) {
    return '<div class="empty-state">No denied Music access attempts were recorded.</div>';
  }
  return attempts.map(attempt => `
    <article class="queue-card">
      <div class="queue-card-main">
        <strong>${escapeHtml(attempt.reason || 'ACCESS_DENIED')}</strong>
        <span>${escapeHtml(attempt.principalType || 'UNKNOWN')}: ${escapeHtml(attempt.principal || 'unknown')}</span>
      </div>
      <div class="queue-card-meta">
        <span>${escapeHtml(attempt.count || 0)} attempt(s)</span>
        <time>${escapeHtml(attempt.lastAttemptAt ? new Date(attempt.lastAttemptAt).toLocaleString() : '—')}</time>
      </div>
    </article>`).join('');
}
