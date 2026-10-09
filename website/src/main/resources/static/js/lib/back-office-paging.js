/** Validate one server page of Back Office records, or throw with the caller's message. */
export function parseServerPage(payload, invalidMessage) {
  if (!payload || !Array.isArray(payload.items)
      || !Number.isInteger(payload.page) || payload.page < 0
      || !Number.isInteger(payload.size) || payload.size < 1
      || !Number.isFinite(payload.totalElements) || payload.totalElements < 0
      || !Number.isInteger(payload.totalPages) || payload.totalPages < 0) {
    throw new Error(invalidMessage);
  }
  return { ...payload, items: [...payload.items] };
}

/** Derive exact previous/next state and label from authoritative page totals. */
export function serverPageNavigation(page) {
  const totalPages = Math.max(0, Number(page?.totalPages || 0));
  const currentPageIndex = Math.max(0, Number(page?.page || 0));
  return {
    previousDisabled: currentPageIndex <= 0,
    nextDisabled: totalPages === 0 || currentPageIndex + 1 >= totalPages,
    label: totalPages === 0 ? 'Page 0 of 0' : `Page ${currentPageIndex + 1} of ${totalPages}`,
  };
}
