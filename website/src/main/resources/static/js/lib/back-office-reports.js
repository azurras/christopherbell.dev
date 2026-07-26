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
export function reportPageNavigation(page) {
  const totalPages = Math.max(0, Number(page?.totalPages || 0));
  const current = Math.max(0, Number(page?.page || 0));
  return {
    previousDisabled: current <= 0,
    nextDisabled: totalPages === 0 || current + 1 >= totalPages,
    label: totalPages === 0 ? 'Page 0 of 0' : `Page ${current + 1} of ${totalPages}`,
  };
}

/** Convert local date controls to the inclusive Instant query contract. */
export function reportFilterValue(form) {
  const values = new FormData(form);
  return {
    status: String(values.get('status') || ''),
    reportType: String(values.get('reportType') || ''),
    targetType: String(values.get('targetType') || ''),
    reporter: String(values.get('reporter') || '').trim(),
    from: toInstant(values.get('from')),
    to: toInstant(values.get('to')),
  };
}

function toInstant(value) {
  if (!value) return '';
  const parsed = new Date(String(value));
  return Number.isFinite(parsed.getTime()) ? parsed.toISOString() : '';
}
