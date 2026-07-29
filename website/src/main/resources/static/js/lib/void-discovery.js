const SECTION_ERROR = 'This section could not load. Try again.';

/** Create isolated mutable state for one discovery panel. */
export function createDiscoverySectionState(key) {
  return { key, items: [], nextCursor: null, loading: false, error: null };
}

/** Validate and apply one server page without sharing state across panels. */
export function applyDiscoveryPage(state, page, append = false) {
  if (!page || !Array.isArray(page.items)
      || (page.nextCursor !== null && page.nextCursor !== undefined
          && typeof page.nextCursor !== 'string')) {
    throw new Error('Invalid discovery response.');
  }
  state.items = append ? [...state.items, ...page.items] : [...page.items];
  state.nextCursor = page.nextCursor || null;
  state.loading = false;
  state.error = null;
  return state;
}

/** Load one panel; failure is contained to only the supplied state object. */
export async function loadDiscoverySection(state, fetchPage, append = false) {
  if (state.loading) return state;
  state.loading = true;
  state.error = null;
  try {
    return applyDiscoveryPage(state, await fetchPage(state.nextCursor), append);
  } catch (_) {
    state.loading = false;
    state.error = SECTION_ERROR;
    return state;
  }
}

/** Encode a canonical topic as exactly one public route segment. */
export function topicHref(canonical) {
  return `/void/topic/${encodeURIComponent(String(canonical || ''))}`;
}
