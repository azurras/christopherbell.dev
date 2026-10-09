import { parseServerPage, serverPageNavigation } from './back-office-paging.js';

/** Validate one server report page before it reaches Back Office rendering. */
export function parseReportPage(payload) {
  return parseServerPage(payload, 'Invalid report page response.');
}

/** Derive exact navigation state from authoritative report totals. */
export function reportPageNavigation(reportPage) {
  return serverPageNavigation(reportPage);
}

/** Convert local date controls to the inclusive Instant query contract. */
export function reportFilterValue(filterForm) {
  const filterValues = new FormData(filterForm);
  return {
    status: String(filterValues.get('status') || ''),
    reportType: String(filterValues.get('reportType') || ''),
    targetType: String(filterValues.get('targetType') || ''),
    reporter: String(filterValues.get('reporter') || '').trim(),
    from: isoInstantFromLocalDateTime(filterValues.get('from')),
    to: isoInstantFromLocalDateTime(filterValues.get('to')),
  };
}

/** Returns the ISO instant for a local date-time control value, or '' when empty or invalid. */
function isoInstantFromLocalDateTime(localDateTimeText) {
  if (!localDateTimeText) return '';
  const localDateTime = new Date(String(localDateTimeText));
  return Number.isFinite(localDateTime.getTime()) ? localDateTime.toISOString() : '';
}
