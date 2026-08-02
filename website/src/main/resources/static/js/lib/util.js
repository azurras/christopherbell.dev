/**
 * Small, focused utilities used across page scripts (KISS/SOLID).
 *
 * Each function addresses a single concern and can be imported
 * individually without side effects.
 */

const AUTH_REDIRECT_FALLBACK_PATHS = new Set(['/login', '/signup', '/forgot-password', '/reset-password']);
const USERNAME_MENTION_RE = /(^|[^A-Za-z0-9._@-])@([A-Za-z0-9](?:[A-Za-z0-9._-]{1,30}[A-Za-z0-9])?)(?=$|[^A-Za-z0-9_-])/g;
const WEB_URL_RE = /\bhttps?:\/\/[^\s<>()]+/gi;
const URL_TRAILING_PUNCTUATION = /[.,!?;:]$/;

/** Return non-authoritative browser session metadata for UI-only decisions. */
export function getAuthClaims() {
  if (!isLoggedIn()) return null;
  return {
    sub: 'browser-session',
    role: String(localStorage.getItem('cbellRole') || ''),
  };
}

/**
 * Compatibility shim for older UI gates. The returned value is a non-secret
 * cookie marker, never an authentication credential.
 */
export function getAuthToken() {
  return readCookie('CBELL_AUTH_STATE');
}

/** Whether the non-secret browser session marker is present. */
export function isLoggedIn() {
  return !!getAuthToken();
}

/** Verify a readable session marker against an authenticated server endpoint. */
export async function hasAuthenticatedSession(accountUrl) {
  if (!isLoggedIn()) return false;
  try {
    await fetchJson(accountUrl);
    return true;
  } catch (_) {
    clearAuthState();
    return false;
  }
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
 * Build same-origin request headers. Authentication is carried by an HttpOnly
 * cookie; the readable CSRF cookie is echoed for unsafe Spring Security flows.
 *
 * @param {Record<string, string>} extraHeaders caller-specific headers
 * @returns {Record<string, string>} headers for authenticated API calls
 */
export function authHeaders(extraHeaders = {}) {
  const csrfToken = readCookie('XSRF-TOKEN');
  return {
    ...extraHeaders,
    ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}),
  };
}

/** Clear non-secret UI state after logout or an authentication failure. */
export function clearAuthState() {
  localStorage.removeItem('cbellUsername');
  localStorage.removeItem('cbellRole');
  if (typeof document !== 'undefined') {
    document.cookie = 'CBELL_AUTH_STATE=; Max-Age=0; Path=/; SameSite=Lax';
  }
}

/**
 * Fetch JSON from an endpoint and return the payload.
 * Throws an Error when HTTP status is not OK or when the
 * API envelope indicates success=false. HTTP failures preserve their numeric
 * status on the thrown Error so protected pages can distinguish access loss.
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
  const method = String(fetchOptions.method || 'GET').toUpperCase();
  const unsafeMethod = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method);
  const resp = await fetch(url, {
    ...fetchOptions,
    credentials: fetchOptions.credentials || 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(unsafeMethod ? authHeaders() : {}),
      ...(fetchOptions.headers || {}),
    },
  });
  if (resp.status === 401) {
    clearAuthState();
    if (redirectOnUnauthorized && !window.location.pathname.startsWith('/login')) {
      window.location.href = loginRedirectUrl();
    }
    const error = new Error('Authentication required.');
    error.status = resp.status;
    throw error;
  }
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || data.success === false) {
    const msg = data?.messages?.[0]?.description || `Request failed: ${resp.status}`;
    const error = new Error(msg);
    error.status = resp.status;
    throw error;
  }
  return data.payload ?? data;
}

function readCookie(name) {
  if (typeof document === 'undefined') return '';
  const prefix = `${encodeURIComponent(name)}=`;
  for (const item of String(document.cookie || '').split(';')) {
    const cookie = item.trim();
    if (!cookie.startsWith(prefix)) continue;
    try {
      return decodeURIComponent(cookie.substring(prefix.length));
    } catch (_) {
      return '';
    }
  }
  return '';
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
  for (const link of textLinks(value)) {
    html += sanitize(value.slice(lastIndex, link.start));
    html += link.type === 'mention'
      ? `<a href="${mentionProfileUrl(link.username)}" class="mention-link">@${sanitize(link.username)}</a>`
      : `<a href="${sanitize(link.url)}" class="text-link" target="_blank" rel="noopener noreferrer">${sanitize(link.url)}</a>`;
    lastIndex = link.end;
  }
  return html + sanitize(value.slice(lastIndex));
}

/** Fill an element with text while linking @username mentions to profiles. */
export function appendTextWithMentionLinks(container, text) {
  if (!container) return;
  container.textContent = '';
  const value = String(text ?? '');
  let lastIndex = 0;
  for (const linkMatch of textLinks(value)) {
    if (linkMatch.start > lastIndex) {
      container.appendChild(document.createTextNode(value.slice(lastIndex, linkMatch.start)));
    }

    const link = document.createElement('a');
    if (linkMatch.type === 'mention') {
      link.href = mentionProfileUrl(linkMatch.username);
      link.className = 'mention-link';
      link.textContent = `@${linkMatch.username}`;
    } else {
      link.href = linkMatch.url;
      link.className = 'text-link';
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = linkMatch.url;
    }
    container.appendChild(link);
    lastIndex = linkMatch.end;
  }

  if (lastIndex < value.length) {
    container.appendChild(document.createTextNode(value.slice(lastIndex)));
  }
}

function textLinks(value) {
  const links = [];
  USERNAME_MENTION_RE.lastIndex = 0;
  let match;
  while ((match = USERNAME_MENTION_RE.exec(value)) !== null) {
    const prefix = match[1] || '';
    const username = match[2] || '';
    const start = match.index + prefix.length;
    links.push({ type: 'mention', start, end: start + username.length + 1, username });
  }

  WEB_URL_RE.lastIndex = 0;
  while ((match = WEB_URL_RE.exec(value)) !== null) {
    const url = trimUrlPunctuation(match[0]);
    if (!url) continue;
    links.push({ type: 'url', start: match.index, end: match.index + url.length, url });
  }

  let lastEnd = -1;
  return links
    .sort((left, right) => left.start - right.start || left.end - right.end)
    .filter(link => {
      if (link.start < lastEnd) return false;
      lastEnd = link.end;
      return true;
    });
}

export function trimUrlPunctuation(url) {
  let value = String(url || '');
  while (URL_TRAILING_PUNCTUATION.test(value)) {
    value = value.slice(0, -1);
  }
  return value;
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
