function escapeHtml(value) {
  return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
}

function when(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

export function sharedAuditMarkup(events) {
  if (!Array.isArray(events) || events.length === 0) {
    return '<div class="empty-state">No shared-folder audit events match these filters.</div>';
  }
  return events.map(event => `
    <article class="queue-card shared-audit-card">
      <div class="queue-card-main">
        <strong>${escapeHtml(event.action || 'UNKNOWN')}</strong>
        <span>${escapeHtml(event.relativePath || 'unknown')}</span>
      </div>
      <div class="queue-card-meta">
        <span>${escapeHtml(event.accountId || 'unknown')}</span>
        <span>${escapeHtml(event.outcome || 'unknown')}</span>
        <time>${escapeHtml(when(event.occurredAt))}</time>
      </div>
    </article>
  `).join('');
}

export function sharedRecycleMarkup(items) {
  if (!Array.isArray(items) || items.length === 0) {
    return '<div class="empty-state">The recycle area is empty.</div>';
  }
  return items.map(item => {
    const id = escapeHtml(item.id);
    return `
      <article class="queue-card shared-recycle-card" data-recycle-id="${id}">
        <div class="queue-card-main">
          <strong>${escapeHtml(item.originalPath || 'unknown')}</strong>
          <span>${escapeHtml(item.size ?? 0)} bytes · deleted by ${escapeHtml(item.deletedByAccountId || 'unknown')}</span>
        </div>
        <div class="queue-card-meta">
          <span>Deleted ${escapeHtml(when(item.deletedAt))}</span>
          <span>Expires ${escapeHtml(when(item.expiresAt))}</span>
        </div>
        <div class="operation-actions">
          <button class="btn btn-sm btn-outline-primary" type="button" data-shared-recycle-action="restore" data-id="${id}">Restore</button>
          <button class="btn btn-sm btn-outline-warning" type="button" data-shared-recycle-action="replace" data-id="${id}">Restore and replace</button>
          <button class="btn btn-sm btn-outline-danger" type="button" data-shared-recycle-action="purge" data-id="${id}">Permanently purge</button>
        </div>
      </article>
    `;
  }).join('');
}

export function sharedAuditFilters(form) {
  const result = {};
  for (const name of ['accountId', 'action', 'outcome', 'path']) {
    const value = String(form?.elements?.[name]?.value || '').trim();
    if (value) result[name] = value;
  }
  for (const name of ['from', 'to']) {
    const value = String(form?.elements?.[name]?.value || '').trim();
    if (!value) continue;
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) result[name] = date.toISOString();
  }
  return result;
}

export function purgeConfirmation(id, value) {
  return String(value || '') === `PURGE ${id}`;
}

export async function runSharedRecycleAction({
  id,
  action,
  confirmReplace,
  promptPurge,
  restore,
  purge,
}) {
  if (!id || !['restore', 'replace', 'purge'].includes(action)) return false;
  if (action === 'restore') {
    await restore(id, false);
    return true;
  }
  if (action === 'replace') {
    if (!confirmReplace()) return false;
    await restore(id, true);
    return true;
  }
  const typed = promptPurge() || '';
  if (!purgeConfirmation(id, typed)) {
    throw new Error('Permanent purge confirmation did not match.');
  }
  await purge(id, typed);
  return true;
}
