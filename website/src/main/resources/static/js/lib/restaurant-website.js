export function restaurantWebsiteUrl(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  try {
    const normalized = value.trim();
    const url = new URL(normalized);
    return url.protocol === 'http:' || url.protocol === 'https:' ? normalized : null;
  } catch {
    return null;
  }
}
