import { parseServerPage, serverPageNavigation } from './back-office-paging.js';

const AUDIT_STATE_KEYS = ['role', 'status', 'resolution'];

/** Validate one server audit page before it reaches Back Office rendering. */
export function parseActivityPage(payload) {
  return parseServerPage(payload, 'Invalid audit page response.');
}

/** Derive exact navigation state from authoritative audit totals. */
export function activityPageNavigation(page) {
  return serverPageNavigation(page);
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
