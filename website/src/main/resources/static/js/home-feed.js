import { authHeaders, fetchJson, sanitize, isLoggedIn, formatWhen, closeOnOutside, loginRedirectUrl } from './lib/util.js';
import { API } from './lib/api.js';
import { createFeedItem } from './lib/feed-render.js';
import { canDeleteFor, makeRendererContext } from './lib/feed-context.js';
import { createInfiniteScroller } from './lib/infinite.js';
import { initComposer } from './lib/composer.js';
/**
 * Home feed page script.
 * - Renders global feed with infinite scroll and 15s polling
 * - Provides a top composer for authenticated users
 * - Wires item actions (reply/like/delete) via feed-render + feed-context
 */

let USER_STATE = { id: null, role: null, username: null };
let LATEST_TS = null;
let SCROLLER = null;
let RENDER_CTX = null;
let FEED_ITEMS = [];
let ACTIVE_FILTER = 'all';
let ACTIVE_SORT = 'newest';

function feedList() {
  return document.getElementById('feedList');
}

function showSkeleton() {
  const list = feedList();
  if (!list) return;
  list.innerHTML = Array.from({ length: 4 }).map(() => `
    <div class="feed-skeleton">
      <div class="skeleton-avatar"></div>
      <div class="skeleton-lines">
        <span></span>
        <span></span>
        <span></span>
      </div>
    </div>
  `).join('');
}

function renderEmpty(message = 'Nothing in the Void yet.') {
  const list = feedList();
  if (!list) return;
  list.innerHTML = `
    <div class="feed-empty-state">
      <p class="home-kicker mb-2">Quiet</p>
      <h2>${sanitize(message)}</h2>
      <p>Start the thread, check back later, or switch filters.</p>
    </div>
  `;
}

function filteredItems() {
  let items = [...FEED_ITEMS];
  if (ACTIVE_FILTER === 'mine') {
    items = items.filter(item => item.accountId === USER_STATE.id || item.username === USER_STATE.username);
  } else if (ACTIVE_FILTER === 'following') {
    items = items.filter(item => item.accountId !== USER_STATE.id);
  } else if (ACTIVE_FILTER === 'replies') {
    items = items.filter(item => item.level && item.level > 0);
  }

  if (ACTIVE_SORT === 'active') {
    items.sort((a, b) => ((b.likesCount || 0) + (b.replyCount || 0)) - ((a.likesCount || 0) + (a.replyCount || 0)));
  } else if (ACTIVE_SORT === 'expiring') {
    items.sort((a, b) => {
      const aTime = a.expiresOn ? new Date(a.expiresOn).getTime() : Number.MAX_SAFE_INTEGER;
      const bTime = b.expiresOn ? new Date(b.expiresOn).getTime() : Number.MAX_SAFE_INTEGER;
      return aTime - bTime;
    });
  } else {
    items.sort((a, b) => new Date(b.createdOn || b.lastUpdatedOn) - new Date(a.createdOn || a.lastUpdatedOn));
  }
  return items;
}

function renderFeed() {
  const list = feedList();
  if (!list || !RENDER_CTX) return;
  const items = filteredItems();
  list.innerHTML = '';
  if (!items.length) {
    const message = ACTIVE_FILTER === 'replies'
        ? 'No replies in this view yet.'
        : ACTIVE_FILTER === 'following'
            ? 'No posts from followed accounts yet.'
        : ACTIVE_FILTER === 'mine'
            ? 'You have not posted anything yet.'
            : 'Nothing in the Void yet.';
    renderEmpty(message);
    return;
  }
  for (const post of items) {
    list.appendChild(createFeedItem(post, RENDER_CTX));
  }
}

function optimisticPost(text) {
  return {
    id: `optimistic-${Date.now()}`,
    accountId: USER_STATE.id,
    username: USER_STATE.username || 'you',
    text,
    rootId: null,
    parentId: null,
    level: 0,
    likesCount: 0,
    liked: false,
    replyCount: 0,
    createdOn: new Date().toISOString(),
    lastUpdatedOn: new Date().toISOString(),
    optimistic: true,
  };
}

async function reloadFeed() {
  FEED_ITEMS = [];
  LATEST_TS = null;
  showSkeleton();
  SCROLLER?.loadInitial();
}

/** Wire page once DOM is ready. */
document.addEventListener('DOMContentLoaded', async () => {
  // Load current user to determine delete permissions before first render
  const token = localStorage.getItem('cbellLoginToken');
  if (token) {
    try {
      const me = await fetchJson(API.accounts.me, { headers: authHeaders() });
      USER_STATE = { id: me.id, role: me.role, username: me.username };
    } catch (_) { /* ignore */ }
  }
  // Close any open menus on outside click
  closeOnOutside('.post-menu');
  // Composer
  initComposer({
    selectors: {
      composer: '#composer',
      prompt: '#composerPrompt',
      textarea: '#postText',
      counter: '#charCount',
      button: '#postBtn',
      alert: '#homeAlert',
    },
    isLoggedIn,
    onSubmit: async (text) => {
      const optimistic = optimisticPost(text);
      FEED_ITEMS.unshift(optimistic);
      renderFeed();
      window.scrollTo({ top: 0, behavior: 'smooth' });
      try {
        await fetchJson(API.posts.create, { method: 'POST', headers: authHeaders(), body: JSON.stringify({ text }) });
        await reloadFeed();
      } catch (err) {
        FEED_ITEMS = FEED_ITEMS.filter(item => item.id !== optimistic.id);
        renderFeed();
        throw err;
      }
    },
  });
  // Show new posts banner when available
  const banner = document.getElementById('newPostsBanner');
  const showBtn = document.getElementById('showNewPostsBtn');
  if (showBtn) {
    showBtn.addEventListener('click', async () => {
      if (banner) banner.classList.add('d-none');
      await reloadFeed();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }
  const filterButtons = document.querySelectorAll('.feed-filter');
  filterButtons.forEach(button => {
    button.addEventListener('click', async () => {
      if (button.disabled) return;
      ACTIVE_FILTER = button.dataset.filter || 'all';
      filterButtons.forEach(btn => btn.classList.toggle('active', btn === button));
      if ((ACTIVE_FILTER === 'mine' || ACTIVE_FILTER === 'following') && !isLoggedIn()) {
        window.location.href = loginRedirectUrl();
        return;
      }
      await reloadFeed();
    });
  });
  const sortSelect = document.getElementById('feedSort');
  sortSelect?.addEventListener('change', () => {
    ACTIVE_SORT = sortSelect.value || 'newest';
    renderFeed();
  });
  showSkeleton();
  RENDER_CTX = makeRendererContext({ fetchJson, authHeaders, sanitize, formatWhen, isLoggedIn, canDelete: canDeleteFor(USER_STATE), currentUserName: USER_STATE?.username || null });
  SCROLLER = createInfiniteScroller({
    thresholdPx: 200,
    limit: 20,
    fetchPage: async ({ before, limit }) => {
      const params = new URLSearchParams();
      params.set('limit', String(limit));
      if (before) params.set('before', before);
      const endpoint = ACTIVE_FILTER === 'mine'
        ? API.posts.meFeed
        : ACTIVE_FILTER === 'following'
            ? API.posts.followingFeed
            : API.posts.feed;
      return await fetchJson(`${endpoint}?${params.toString()}`, { headers: authHeaders() });
    },
    onPage: (items) => {
      if (!items || items.length === 0) {
        renderFeed();
        return;
      }
      if (!LATEST_TS) {
        const first = items[0];
        LATEST_TS = first.createdOn || first.lastUpdatedOn;
      }
      FEED_ITEMS.push(...items);
      renderFeed();
    },
    getCursor: (it) => it.createdOn || it.lastUpdatedOn,
    onEmpty: () => renderFeed()
  });
  SCROLLER.attach();
  SCROLLER.loadInitial();
  // Poll for new posts every 15 seconds
  setInterval(async () => {
    try {
      const latest = await fetchJson('/api/posts/2025-09-14/feed?limit=1', { headers: authHeaders() });
      const top = latest && latest[0];
/**
 * Global home feed behavior.
 *
 * Responsibilities:
 * - Load newest posts with infinite scroll and 15s polling
 * - Render each item with user handle, content, parent-context (when reply)
 * - Allow posting (when authenticated), liking, and deleting (owner/admin)
 */
      if (!top) return;
      const topTs = top.createdOn || top.lastUpdatedOn;
      if (LATEST_TS && new Date(topTs) > new Date(LATEST_TS)) {
        if (banner) banner.classList.remove('d-none');
      }
    } catch (_) { /* ignore polling failures */ }
  }, 15000);
});
