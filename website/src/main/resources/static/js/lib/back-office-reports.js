/** Validate one server report page before it reaches Back Office rendering. */
export function parseReportPage(payload) {
  if (!payload || !Array.isArray(payload.items)
      || !Number.isInteger(payload.page) || payload.page < 0
      || !Number.isInteger(payload.size) || payload.size < 1
      || !Number.isFinite(payload.totalElements) || payload.totalElements < 0
      || !Number.isInteger(payload.totalPages) || payload.totalPages < 0) {
    throw new Error('Invalid report page response.');
  }
  return { ...payload, items: [...payload.items] };
}

/** Derive exact navigation state from authoritative report totals. */
export function reportPageNavigation(reportPage) {
  const totalPages = Math.max(0, Number(reportPage?.totalPages || 0));
  const currentPageIndex = Math.max(0, Number(reportPage?.page || 0));
  return {
    previousDisabled: currentPageIndex <= 0,
    nextDisabled: totalPages === 0 || currentPageIndex + 1 >= totalPages,
    label: totalPages === 0 ? 'Page 0 of 0' : `Page ${currentPageIndex + 1} of ${totalPages}`,
  };
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
