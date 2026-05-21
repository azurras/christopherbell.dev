import { fetchJson, sanitize, authHeaders, isLoggedIn, formatWhen, closeOnOutside, loginRedirectUrl } from './lib/util.js';
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

let ME = { id: null, role: null, username: null };
let SCROLLER = null;
let RENDER_CTX = null;
let PROFILE = null;
let VISIBLE_POSTS = 0;

function setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value ?? '';
}

function renderProfile(profile) {
  PROFILE = profile;
  const username = profile?.username || getUsernameFromPath();
  const handle = username ? `@${username}` : '@user';
  setText('userHeroTitle', handle);
  setText('userHeroMeta', `${handle}'s posts and profile activity.`);
  setText('userHandle', handle);
  setText('userMeta', handle);
  setText('userInitials', initialsFromUsername(username));
  setText('userFollowerCount', String(profile?.followerCount ?? 0));
  setText('userFollowingCount', String(profile?.followingCount ?? 0));
  renderFollowButton(profile);
  renderMessageLink(profile);
}

function renderFollowButton(profile) {
  const button = document.getElementById('followBtn');
  if (!button) return;
  if (!profile || profile.self) {
    button.classList.add('d-none');
    return;
  }
  button.classList.remove('d-none');
  button.classList.toggle('btn-dark', !profile.followedByMe);
  button.classList.toggle('btn-outline-dark', !!profile.followedByMe);
  button.textContent = profile.followedByMe ? 'Following' : 'Follow';
}

function renderMessageLink(profile) {
  const link = document.getElementById('messageUserLink');
  if (!link) return;
  if (!profile || profile.self) {
    link.classList.add('d-none');
    return;
  }
  link.href = `/messages?to=${encodeURIComponent(profile.username || '')}`;
  link.classList.remove('d-none');
}

function renderEmptyFeed() {
  const list = document.getElementById('userFeed');
  if (!list) return;
  list.innerHTML = `
    <div class="feed-empty-state user-empty-state">
      <h2>No posts yet</h2>
      <p>This profile has not posted anything into the Void yet.</p>
    </div>`;
}

function showAlert(message) {
  const alert = document.getElementById('userAlert');
  if (alert) {
    alert.textContent = message;
    alert.classList.remove('d-none');
  }
}

async function toggleFollow() {
  if (!PROFILE) return;
  if (!isLoggedIn()) {
    window.location.href = loginRedirectUrl();
    return;
  }
  const button = document.getElementById('followBtn');
  try {
    if (button) button.disabled = true;
    const method = PROFILE.followedByMe ? 'DELETE' : 'POST';
    const updated = await fetchJson(API.accounts.follow(PROFILE.username), {
      method,
      headers: authHeaders()
    });
    renderProfile(updated);
  } catch (err) {
    showAlert(err.message);
  } finally {
    if (button) button.disabled = false;
  }
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
    try {
      const profile = await fetchJson(API.accounts.profile(username), { headers: authHeaders() });
      renderProfile(profile);
    } catch (err) {
      showAlert(err.message);
      renderProfile({ username });
    }
    document.getElementById('followBtn')?.addEventListener('click', toggleFollow);
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
        VISIBLE_POSTS += items.length;
        setText('userPostCount', String(VISIBLE_POSTS));
        for (const p of items) list.appendChild(createFeedItem(p, RENDER_CTX));
      },
      getCursor: (it) => it.createdOn || it.lastUpdatedOn,
      onEmpty: renderEmptyFeed
    });
    SCROLLER.attach();
    SCROLLER.loadInitial();
  }
  closeOnOutside('.post-menu');
});
