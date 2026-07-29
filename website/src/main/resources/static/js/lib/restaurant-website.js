export function restaurantWebsiteUrl(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  try {
    const normalized = value.trim();
    const url = new URL(normalized);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
    return url.href;
  } catch {
    return null;
  }
}

export function appendRestaurantWebsiteLink(container, value, {
  className = '',
  label = 'Website',
} = {}) {
  const href = restaurantWebsiteUrl(value);
  if (!container || !href) return null;
  const link = container.ownerDocument.createElement('a');
  link.href = href;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  link.className = className;
  link.textContent = label;
  container.append(link);
  return link;
}
