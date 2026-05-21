import { appendTextWithMentionLinks, sanitize, authHeaders, fetchJson, isLoggedIn, formatWhen, closeOnOutside } from './lib/util.js';
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
  appendTextWithMentionLinks(document.getElementById('threadAuthor'), author);
  setText('threadCreated', formatWhen(post.createdOn || post.lastUpdatedOn));
  setText('threadReplyCount', String(directReplies.length));
  setText('threadLikeCount', String(post.likesCount || 0));
  setText('threadReplyPill', `${directReplies.length} ${directReplies.length === 1 ? 'reply' : 'replies'}`);

  const status = statusFor(post);
  const statusEl = document.getElementById('threadStatus');
  if (statusEl) {
    statusEl.textContent = status.label;
    statusEl.className = `thread-status-pill is-${status.tone}`;
  }

  const heroTitle = document.getElementById('postHeroTitle');
  if (heroTitle) heroTitle.textContent = `${author}'s post`;
  const heroMeta = document.getElementById('postHeroMeta');
  if (heroMeta) {
    heroMeta.textContent = directReplies.length > 0
      ? `${directReplies.length} ${directReplies.length === 1 ? 'reply' : 'replies'} in this conversation.`
      : 'No replies yet. Start the conversation from the post actions.';
  }
}

function contextCard(kind, postId) {
  return `
    <div class="thread-context-card" data-context-kind="${kind}">
      <div>
        <span>${kind === 'root' ? 'Thread root' : 'In reply to'}</span>
        <a href="/u/" data-context-handle>@user</a>
      </div>
      <p data-context-text>Loading context...</p>
      <a href="/p/${encodeURIComponent(postId)}">View ${kind === 'root' ? 'root' : 'parent'}</a>
    </div>`;
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
      handleEl.setAttribute('href', `/u/${encodeURIComponent(username)}`);
    }
    appendTextWithMentionLinks(textEl, context.text || '');
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

  const ctx = makeRendererContext({ fetchJson, authHeaders, sanitize, formatWhen, isLoggedIn, canDelete: canDeleteFor(currentUser), currentUserName: currentUser?.username || null, suppressParentContext: true });
  root.appendChild(createFeedItem(post, ctx));

  if (post.parentId) {
    fillContext(root, 'parent', post.parentId);
  }

  if (post.rootId && post.rootId !== post.id && post.rootId !== post.parentId) {
    fillContext(root, 'root', post.rootId);
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
    const directReplies = renderThread(thread, me, id) || [];
    renderThreadSummary(post, directReplies);
    // The inline reply composer is available under the root; no need to show the top card.
    // If desired, we could still expose #replyComposer; keeping it hidden for a cleaner UI.
  } catch (err) {
    if (alert) { alert.textContent = err.message; alert.classList.remove('d-none'); }
  }
});
