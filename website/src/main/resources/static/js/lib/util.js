/**
 * Small, focused utilities used across page scripts (KISS/SOLID).
 *
 * Each function addresses a single concern and can be imported
 * individually without side effects.
 */

/** Whether a login token exists in localStorage. */
export function isLoggedIn() {
  return !!localStorage.getItem('cbellLoginToken');
}

/** Authorization header using the stored token (if present). */
export function authHeaders() {
  const token = localStorage.getItem('cbellLoginToken');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Clear cached auth data when the token is missing, stale, or explicitly removed. */
export function clearAuthState() {
  localStorage.removeItem('cbellLoginToken');
  localStorage.removeItem('cbellUsername');
  localStorage.removeItem('cbellRole');
}

/**
 * Fetch JSON from an endpoint and return the payload.
 * Throws an Error when HTTP status is not OK or when the
 * API envelope indicates success=false.
 *
 * Set redirectOnUnauthorized when a protected page should send the
 * visitor to the login page. Public pages should receive the error
 * without navigation so tools keep working for anonymous visitors.
 */
export async function fetchJson(url, options = {}) {
  const { redirectOnUnauthorized = false, ...fetchOptions } = options;
  const resp = await fetch(url, {
    ...fetchOptions,
    headers: {
      'Content-Type': 'application/json',
      ...(fetchOptions.headers || {}),
    },
  });
  if (resp.status === 401) {
    clearAuthState();
    if (redirectOnUnauthorized && !window.location.pathname.startsWith('/login')) {
      window.location.href = '/login';
    }
    throw new Error('Authentication required.');
  }
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || data.success === false) {
    const msg = data?.messages?.[0]?.description || `Request failed: ${resp.status}`;
    throw new Error(msg);
  }
  return data.payload ?? data;
}

/** Escape HTML metacharacters for safe text and attribute injection. */
export function sanitize(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Convert an ISO datetime (or now) into a localized string. */
export function formatWhen(isoString) {
  return new Date(isoString || Date.now()).toLocaleString();
}

/**
 * Close menus when clicking anywhere outside them.
 * Adds a capture-phase document click listener that hides all elements
 * matching the given selector by adding the 'd-none' class.
 * @param {string} selector CSS selector for menu containers
 */
export function closeOnOutside(selector) {
  document.addEventListener('click', () => {
    document.querySelectorAll(selector).forEach(el => el.classList.add('d-none'));
  }, { capture: true });
}
