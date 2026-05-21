/**
 * Small, focused utilities used across page scripts (KISS/SOLID).
 *
 * Each function addresses a single concern and can be imported
 * individually without side effects.
 */

const AUTH_REDIRECT_FALLBACK_PATHS = new Set(['/login', '/signup', '/forgot-password', '/reset-password']);
const USERNAME_MENTION_RE = /(^|[^A-Za-z0-9._@-])@([A-Za-z0-9](?:[A-Za-z0-9._-]{1,30}[A-Za-z0-9])?)(?=$|[^A-Za-z0-9_-])/g;

function decodeJwtPayload(token) {
  const payload = token.split('.')[1] || '';
  const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
  return JSON.parse(atob(padded));
}

/** Return decoded JWT claims for UI-only decisions, or null when no usable token exists. */
export function getAuthClaims() {
  const token = getAuthToken();
  if (!token) return null;

  try {
    return decodeJwtPayload(token);
  } catch (_) {
    clearAuthState();
    return null;
  }
}

/** Return the stored JWT when it is present and shaped like a usable token. */
export function getAuthToken() {
  const storedToken = String(localStorage.getItem('cbellLoginToken') || '').trim();
  const token = storedToken.startsWith('Bearer ')
    ? storedToken.substring('Bearer '.length).trim()
    : storedToken;

  if (!token || token === 'Bearer' || token === 'undefined' || token === 'null') {
    clearAuthState();
    return '';
  }

  if (token.split('.').length !== 3) {
    clearAuthState();
    return '';
  }

  try {
    const claims = decodeJwtPayload(token);
    const expiresAt = Number(claims?.exp || 0) * 1000;
    if (!expiresAt || expiresAt <= Date.now()) {
      clearAuthState();
      return '';
    }
  } catch (_) {
    clearAuthState();
    return '';
  }

  if (token !== storedToken) {
    localStorage.setItem('cbellLoginToken', token);
  }

  return token;
}

/** Whether a usable login token exists in localStorage. */
export function isLoggedIn() {
  return !!getAuthToken();
}

/** Current local path, query, and hash for post-login redirects. */
export function currentRedirectTarget() {
  const current = safeRedirectTarget(`${window.location.pathname || '/'}${window.location.search || ''}${window.location.hash || ''}`);
  const pathOnly = current.split(/[?#]/, 1)[0];
  return AUTH_REDIRECT_FALLBACK_PATHS.has(pathOnly) ? '/' : current;
}

/** Validate a local redirect target so login cannot bounce to another origin. */
export function safeRedirectTarget(target, fallback = '/') {
  const value = String(target || '').trim();
  if (!value || !value.startsWith('/') || value.startsWith('//')) {
    return fallback;
  }

  try {
    const url = new URL(value, window.location.origin);
    return url.origin === window.location.origin
      ? `${url.pathname}${url.search}${url.hash}` || fallback
      : fallback;
  } catch (_) {
    return fallback;
  }
}

/** Login URL that returns the user to the supplied or current local page. */
export function loginRedirectUrl(target = currentRedirectTarget()) {
  return `/login?redirect=${encodeURIComponent(safeRedirectTarget(target))}`;
}

/**
 * Build request headers with the stored bearer token when one exists.
 *
 * @param {Record<string, string>} extraHeaders caller-specific headers
 * @returns {Record<string, string>} headers for authenticated API calls
 */
export function authHeaders(extraHeaders = {}) {
  const token = getAuthToken();
  return {
    ...extraHeaders,
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
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
 * Set redirectOnUnauthorized when a protected page should send the visitor to
 * the login page. Public pages should receive the error without navigation so
 * tools keep working for anonymous visitors.
 */
export async function fetchJson(url, options = {}) {
  const {
    redirectOnUnauthorized = false,
    ...fetchOptions
  } = options;
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
      window.location.href = loginRedirectUrl();
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

/** Public profile URL for a normalized username. */
export function mentionProfileUrl(username) {
  return `/u/${encodeURIComponent(String(username || '').trim().replace(/^@/, ''))}`;
}

/**
 * Escape text and turn @username mentions into public profile links.
 *
 * Mentions use normalized username characters with 3-32 characters. The
 * leading boundary prevents email addresses from being linked, and sentence
 * punctuation stays outside the profile link.
 */
export function linkMentions(text) {
  const value = String(text ?? '');
  let html = '';
  let lastIndex = 0;
  USERNAME_MENTION_RE.lastIndex = 0;
  let match;
  while ((match = USERNAME_MENTION_RE.exec(value)) !== null) {
    const prefix = match[1] || '';
    const username = match[2] || '';
    const mentionStart = match.index + prefix.length;
    html += sanitize(value.slice(lastIndex, mentionStart));
    html += `<a href="${mentionProfileUrl(username)}" class="mention-link">@${sanitize(username)}</a>`;
    lastIndex = mentionStart + username.length + 1;
  }
  return html + sanitize(value.slice(lastIndex));
}

/** Fill an element with text while linking @username mentions to profiles. */
export function appendTextWithMentionLinks(container, text) {
  if (!container) return;
  container.textContent = '';
  const value = String(text ?? '');
  let lastIndex = 0;
  USERNAME_MENTION_RE.lastIndex = 0;
  let match;
  while ((match = USERNAME_MENTION_RE.exec(value)) !== null) {
    const prefix = match[1] || '';
    const username = match[2] || '';
    const mentionStart = match.index + prefix.length;
    if (mentionStart > lastIndex) {
      container.appendChild(document.createTextNode(value.slice(lastIndex, mentionStart)));
    }

    const link = document.createElement('a');
    link.href = mentionProfileUrl(username);
    link.className = 'mention-link';
    link.textContent = `@${username}`;
    container.appendChild(link);
    lastIndex = mentionStart + username.length + 1;
  }

  if (lastIndex < value.length) {
    container.appendChild(document.createTextNode(value.slice(lastIndex)));
  }
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
