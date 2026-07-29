/** Returns a copied Void feed ordered only by time, never engagement totals. */
export function sortVoidFeedItems(items, sort) {
  const ordered = [...items];
  if (sort === 'expiring') {
    ordered.sort((a, b) => {
      const aTime = a.expiresOn ? new Date(a.expiresOn).getTime() : Number.MAX_SAFE_INTEGER;
      const bTime = b.expiresOn ? new Date(b.expiresOn).getTime() : Number.MAX_SAFE_INTEGER;
      return aTime - bTime;
    });
    return ordered;
  }

  ordered.sort((a, b) =>
    new Date(b.createdOn || b.lastUpdatedOn) - new Date(a.createdOn || a.lastUpdatedOn));
  return ordered;
}
