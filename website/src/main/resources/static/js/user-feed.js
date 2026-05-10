import { fetchJson, sanitize, authHeaders, isLoggedIn, formatWhen, closeOnOutside } from './lib/util.js';
import { API } from './lib/api.js';
import { createFeedItem } from './lib/feed-render.js';
/**
 * User feed page script.
 * - Resolves username from URL and loads their posts
 * - Renders items with actions and context
 * - Uses shared renderer context to reduce duplication
 */
import { canDeleteFor, makeRendererContext } from './lib/feed-context.js';
import { createInfiniteScroller } from './lib/infinite.js';

/** Extract the username from the /u/{username} path. */
function getUsernameFromPath() {
  const m = window.location.pathname.match(/\/u\/(.+)$/);
  return m ? decodeURIComponent(m[1]) : null;
}

function initialsFromUsername(username) {
  if (!username) return '';
  return username[0].toUpperCase();
}


let STATE = { before: null, limit: 20, loading: false, done: false };
let ME = { id: null, role: null, username: null };
let SCROLLER = null;
let RENDER_CTX = null;

/**
 * Load the user's feed page slice.
 * @param {boolean} initial whether to reset pagination
 */
async function loadUserFeed(initial = false) {
  const list = document.getElementById('userFeed');
  const alert = document.getElementById('userAlert');
  const username = getUsernameFromPath();
  if (!list || !username) return;
  if (initial) {
    STATE = { before: null, limit: 20, loading: false, done: false };
    list.innerHTML = '';
    document.getElementById('userHandle').textContent = `@${username}`;
  }
  if (STATE.loading || STATE.done) return;
  STATE.loading = true;
  try {
    const params = new URLSearchParams();
    params.set('limit', STATE.limit.toString());
    if (STATE.before) params.set('before', STATE.before);
    const items = await fetchJson(`${API.posts.userFeed(username)}?${params}`, { headers: authHeaders() });
    if (!items || items.length === 0) {
      if (list.children.length === 0) {
        list.innerHTML = '<div class="list-group-item">No posts yet.</div>';
      }
      STATE.done = true;
      STATE.loading = false;
      return;
    }
    const ctx = makeRendererContext({ fetchJson, authHeaders, sanitize, formatWhen, isLoggedIn, canDelete: canDeleteFor(ME), currentUserName: ME?.username || null });
    for (const p of items) list.appendChild(createFeedItem({ ...p, username }, ctx));
    const last = items[items.length - 1];
    STATE.before = last.createdOn || last.lastUpdatedOn;
    if (items.length < STATE.limit) STATE.done = true;
  } catch (err) {
    if (alert) {
      alert.textContent = err.message;
      alert.classList.remove('d-none');
    }
  }
  STATE.loading = false;
}

/** Wire page once DOM is ready. */
document.addEventListener('DOMContentLoaded', async () => {
  // Try to resolve current user to determine delete permissions
  if (localStorage.getItem('cbellLoginToken')) {
    try {
      const me = await fetchJson('/api/accounts/2025-09-03/me', { headers: authHeaders() });
      ME = { id: me.id, role: me.role, username: me.username };
    } catch (_) {}
  }
  const list = document.getElementById('userFeed');
  const username = getUsernameFromPath();
  if (list && username) {
    const handle = document.getElementById('userHandle');
    if (handle) handle.textContent = `@${username}`;
    const initialsEl = document.getElementById('userInitials');
    if (initialsEl) initialsEl.textContent = initialsFromUsername(username);
    list.innerHTML = '';
    RENDER_CTX = makeRendererContext({ fetchJson, authHeaders, sanitize, formatWhen, isLoggedIn, canDelete: canDeleteFor(ME), currentUserName: ME?.username || null });
    SCROLLER = createInfiniteScroller({
      thresholdPx: 200,
      limit: 20,
      fetchPage: async ({ before, limit }) => {
        const params = new URLSearchParams();
        params.set('limit', String(limit));
        if (before) params.set('before', before);
        return await fetchJson(`${API.posts.userFeed(username)}?${params.toString()}`, { headers: authHeaders() });
      },
      onPage: (items) => {
        if (!items || items.length === 0) return;
        for (const p of items) list.appendChild(createFeedItem({ ...p, username }, RENDER_CTX));
      },
      getCursor: (it) => it.createdOn || it.lastUpdatedOn,
      onEmpty: () => { list.innerHTML = '<div class="list-group-item">No posts yet.</div>'; }
    });
    SCROLLER.attach();
    SCROLLER.loadInitial();
  }
  closeOnOutside('.post-menu');
});
