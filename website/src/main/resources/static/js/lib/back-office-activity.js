const AUDIT_STATE_KEYS = ['role', 'status', 'resolution'];

/** Validate one server audit page before it reaches Back Office rendering. */
export function parseActivityPage(payload) {
  if (!payload || !Array.isArray(payload.items)
      || !Number.isInteger(payload.page) || payload.page < 0
      || !Number.isInteger(payload.size) || payload.size < 1
      || !Number.isFinite(payload.totalElements) || payload.totalElements < 0
      || !Number.isInteger(payload.totalPages) || payload.totalPages < 0) {
    throw new Error('Invalid audit page response.');
  }
  return { ...payload, items: [...payload.items] };
}

/** Derive exact navigation state from authoritative audit totals. */
export function activityPageNavigation(page) {
  const totalPages = Math.max(0, Number(page?.totalPages || 0));
  const current = Math.max(0, Number(page?.page || 0));
  return {
    previousDisabled: current <= 0,
    nextDisabled: totalPages === 0 || current + 1 >= totalPages,
    label: totalPages === 0 ? 'Page 0 of 0' : `Page ${current + 1} of ${totalPages}`,
  };
}

/** Convert audit form controls to the inclusive Instant query contract. */
export function activityFilterValue(form) {
  const values = new FormData(form);
  return {
    action: String(values.get('action') || '').trim(),
    targetType: String(values.get('targetType') || '').trim(),
    actor: String(values.get('actor') || '').trim(),
    from: toInstant(values.get('from')),
    to: toInstant(values.get('to')),
  };
}

/** Normalize a required moderator reason before sending any mutation. */
export function moderationReasonValue(value) {
  const reason = String(value || '').trim();
  return reason.length > 0 && reason.length <= 500 ? reason : '';
}

/** Present only the server's allowlisted moderation state and reason. */
export function moderationActivitySummary(activity) {
  const before = activity?.beforeValues || {};
  const after = activity?.afterValues || {};
  const transitions = AUDIT_STATE_KEYS
      .filter(key => Object.hasOwn(before, key) || Object.hasOwn(after, key))
      .map(key => `${key}: ${before[key] || '—'} → ${after[key] || '—'}`);
  return {
    reason: String(activity?.reason || '').trim(),
    transition: transitions.join('; '),
  };
}

function toInstant(value) {
  if (!value) return '';
  const parsed = new Date(String(value));
  return Number.isFinite(parsed.getTime()) ? parsed.toISOString() : '';
}
