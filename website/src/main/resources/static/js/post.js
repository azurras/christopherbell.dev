import { sanitize, authHeaders, fetchJson, isLoggedIn, formatWhen, closeOnOutside } from './lib/util.js';
import { API } from './lib/api.js';
import { createFeedItem } from './lib/feed-render.js';
import { makeRendererContext, canDeleteFor } from './lib/feed-context.js';
/** Extract the post id from the /p/{id} path. */
function getPostId() { const m = location.pathname.match(/\/p\/(.+)$/); return m ? decodeURIComponent(m[1]) : null; }

function setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value ?? '-';
}

function statusFor(post) {
  if (!post.expiresOn) return { label: 'Live', tone: 'live' };
  const delta = new Date(post.expiresOn).getTime() - Date.now();
  if (delta <= 0) return { label: 'Expired', tone: 'expired' };
  if (delta < 24 * 60 * 60 * 1000) return { label: 'Expires soon', tone: 'soon' };
  return { label: 'Live', tone: 'live' };
}

function renderThreadSummary(post, directReplies) {
  const author = post.username ? `@${post.username}` : '@user';
  setText('threadReplyPill', `${directReplies.length} ${directReplies.length === 1 ? 'reply' : 'replies'}`);

  const status = statusFor(post);
  const statusEl = document.getElementById('threadStatus');
  if (statusEl) {
    statusEl.textContent = status.label;
    statusEl.className = `thread-status-pill is-${status.tone}`;
  }

  const heroTitle = document.getElementById('postHeroTitle');
  if (heroTitle) heroTitle.textContent = 'Post';

  const heroMeta = document.getElementById('postHeroMeta');
  if (heroMeta) {
    heroMeta.textContent = `${author} · ${directReplies.length} ${directReplies.length === 1 ? 'reply' : 'replies'}`;
  }
}

function renderExpiredRootState(root) {
  root.innerHTML = `
    <div class="thread-expired-state" role="status">
      <p class="thread-label">Signal lost</p>
      <h2>This post expired in the Void.</h2>
      <p>The thread context may still show active replies until their own lifespan ends.</p>
      <a class="btn btn-outline-light btn-sm" href="/void">Back to feed</a>
    </div>`;

  const heroMeta = document.getElementById('postHeroMeta');
  if (heroMeta) heroMeta.textContent = 'The selected post reached the end of its lifespan.';

  const statusEl = document.getElementById('threadStatus');
  if (statusEl) {
    statusEl.textContent = 'Expired';
    statusEl.className = 'thread-status-pill is-expired';
  }
}

function contextCard(kind, postId) {
  return `
    <a class="void-context-echo" href="/p/${encodeURIComponent(postId)}" data-context-kind="${kind}">
      <span class="void-context-line" aria-hidden="true"></span>
      <span class="void-context-copy">
        <span class="void-context-label">${kind === 'root' ? 'Thread root' : 'In reply to'}</span>
        <span class="void-context-handle" data-context-handle>@user</span>
        <span class="void-context-text" data-context-text>Loading context...</span>
      </span>
    </a>`;
}

async function fillContext(root, kind, postId) {
  try {
    const context = await fetchJson(API.posts.byId(postId), { headers: authHeaders() });
    const card = root.querySelector(`[data-context-kind="${kind}"]`);
    const handleEl = card?.querySelector('[data-context-handle]');
    const textEl = card?.querySelector('[data-context-text]');
    if (handleEl) {
      const username = context.username || '';
      handleEl.textContent = username ? `@${username}` : '@user';
    }
    if (textEl) textEl.textContent = context.text || '';
  } catch (_) {
    const textEl = root.querySelector(`[data-context-kind="${kind}"] [data-context-text]`);
    if (textEl) textEl.textContent = 'Context unavailable';
  }
}

/**
 * Render the root post (with context cards) using the shared feed renderer.
 * @param {object} post root post feed item
 * @param {{id?:string,role?:string,username?:string}|null} currentUser
 */
function renderRoot(post, currentUser) {
  const root = document.querySelector('#rootPost .thread-root-body');
  if (!root) return;
  root.innerHTML = '';
  const contextStack = document.createElement('div');
  contextStack.className = 'thread-context-stack';
  if (post.rootId && post.rootId !== post.id && post.rootId !== post.parentId) {
    contextStack.insertAdjacentHTML('beforeend', contextCard('root', post.rootId));
  }
  if (post.parentId) {
    contextStack.insertAdjacentHTML('beforeend', contextCard('parent', post.parentId));
  }
  if (contextStack.children.length > 0) root.appendChild(contextStack);

  const ctx = makeRendererContext({
    fetchJson,
    authHeaders,
    sanitize,
    formatWhen,
    isLoggedIn,
    canDelete: canDeleteFor(currentUser),
    currentUserName: currentUser?.username || null,
    suppressParentContext: true,
    onExpire: () => renderExpiredRootState(root)
  });
  const focusedPost = createFeedItem(post, ctx);
  focusedPost.classList.add('void-thread-selected-post');
  root.appendChild(focusedPost);

  if (post.parentId) {
    fillContext(root, 'parent', post.parentId);
  }

  if (post.rootId && post.rootId !== post.id && post.rootId !== post.parentId) {
    fillContext(root, 'root', post.rootId);
  }
}

function showReplyComposer(currentUser) {
  const composer = document.getElementById('replyComposer');
  const meta = document.getElementById('replyComposerMeta');
  if (!composer) return;

  composer.classList.remove('d-none');
  if (meta) {
    meta.textContent = currentUser ? 'Replies extend the signal.' : 'Log in to reply.';
  }

  const replyButton = document.getElementById('replyBtn');
  const replyText = document.getElementById('replyText');
  if (!currentUser) {
    replyText?.setAttribute('disabled', 'disabled');
    replyButton?.setAttribute('disabled', 'disabled');
  } else {
    replyText?.removeAttribute('disabled');
    replyButton?.removeAttribute('disabled');
  }
}

async function submitReply(parentId) {
  const replyText = document.getElementById('replyText');
  const replyButton = document.getElementById('replyBtn');
  const alert = document.getElementById('postAlert');
  const text = replyText?.value?.trim() || '';
  if (!text) return;

  replyButton?.setAttribute('disabled', 'disabled');
  if (alert) alert.classList.add('d-none');
  try {
    await fetchJson(API.posts.create, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ text, parentId })
    });
    window.location.reload();
  } catch (err) {
    if (alert) {
      alert.textContent = err.message || 'Could not post reply.';
      alert.classList.remove('d-none');
    }
  } finally {
    replyButton?.removeAttribute('disabled');
  }
}

/**
 * Render the replies list, excluding the currentId item if present.
 * @param {Array} items thread feed items
 * @param {{id?:string,role?:string}} currentUser current viewer (optional)
 * @param {string} currentId post id to omit from replies
 */
function renderThread(items, currentUser, currentId) {
  const list = document.getElementById('threadList');
  if (!list) return;
  list.innerHTML = '';
  const directReplies = (items || []).filter(p => p.parentId === currentId);
  if (directReplies.length === 0) {
    list.innerHTML = `
      <div class="feed-empty-state thread-empty-state">
        <h2>No replies yet</h2>
        <p>This thread is quiet for now. Use the reply action on the post to add context.</p>
      </div>`;
    return directReplies;
  }
  const ctx = makeRendererContext({ fetchJson, authHeaders, sanitize, formatWhen, isLoggedIn, canDelete: canDeleteFor(currentUser), currentUserName: currentUser?.username || null, suppressParentContext: true });
  for (const p of directReplies) list.appendChild(createFeedItem(p, ctx));
  return directReplies;
}

/** Wire page once DOM is ready. */
document.addEventListener('DOMContentLoaded', async () => {
  closeOnOutside('.post-menu');
  const id = getPostId();
  if (!id) return;
  const alert = document.getElementById('postAlert');
  try {
    const [post, thread] = await Promise.all([
      fetchJson(API.posts.byId(id), { headers: authHeaders() }),
      fetchJson(API.posts.thread(id), { headers: authHeaders() })
    ]);
    let me = null;
    if (localStorage.getItem('cbellLoginToken')) {
      try { me = await fetchJson(API.accounts.me, { headers: authHeaders() }); } catch (_) {}
    }
    renderRoot(post, me);
    showReplyComposer(me);
    document.getElementById('replyBtn')?.addEventListener('click', () => submitReply(id));
    const directReplies = renderThread(thread, me, id) || [];
    renderThreadSummary(post, directReplies);
  } catch (err) {
    if (alert) { alert.textContent = err.message; alert.classList.remove('d-none'); }
  }
});
