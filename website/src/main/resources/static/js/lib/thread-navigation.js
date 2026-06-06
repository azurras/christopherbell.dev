const THREAD_SNIPPET_LENGTH = 88;

function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function snippet(text) {
  const value = String(text ?? '').replace(/\s+/g, ' ').trim();
  if (value.length <= THREAD_SNIPPET_LENGTH) return value || 'Untitled signal';
  return `${value.slice(0, THREAD_SNIPPET_LENGTH - 1).trim()}...`;
}

function depthFor(post) {
  const value = Number(post?.level || 0);
  if (!Number.isFinite(value) || value < 0) return 0;
  return Math.min(value, 6);
}

function replyTimestamp(post) {
  const value = post?.createdOn || post?.lastUpdatedOn;
  if (!value) return 0;
  const timestamp = new Date(value).getTime();
  return Number.isFinite(timestamp) ? timestamp : 0;
}

function isReply(post) {
  return !!post?.id && (!!post.parentId || Number(post.level || 0) > 0);
}

/**
 * Return the newest non-root reply in a thread, or null when the thread has no
 * replies.
 */
export function newestReplyInThread(thread = []) {
  const posts = Array.isArray(thread) ? thread : [];
  let newest = null;
  let newestTimestamp = -1;

  for (const post of posts) {
    if (!isReply(post)) continue;
    const timestamp = replyTimestamp(post);
    if (timestamp > newestTimestamp) {
      newest = post;
      newestTimestamp = timestamp;
    }
  }

  return newest;
}

/**
 * Return reply ids that have child posts. Root posts are excluded because the
 * post detail page uses this for reply-row branch toggles.
 */
export function replyIdsWithChildren(thread = []) {
  const posts = Array.isArray(thread) ? thread.filter(post => post?.id) : [];
  const replies = new Set(posts.filter(isReply).map(post => post.id));
  const ids = new Set();

  for (const post of posts) {
    if (post?.parentId && replies.has(post.parentId)) {
      ids.add(post.parentId);
    }
  }

  return ids;
}

/**
 * Hide descendants of collapsed posts while preserving the API-provided thread
 * order. The collapsed post itself remains visible.
 */
export function visibleThreadAfterCollapsedBranches(thread = [], collapsedIds = new Set()) {
  const posts = Array.isArray(thread) ? thread : [];
  const hiddenIds = new Set();
  const visible = [];

  for (const post of posts) {
    if (!post?.id) continue;
    const parentId = post.parentId || null;
    if (parentId && (collapsedIds.has(parentId) || hiddenIds.has(parentId))) {
      hiddenIds.add(post.id);
      continue;
    }
    visible.push(post);
  }

  return visible;
}

/**
 * Build previous/next links and flattened nodes for a thread navigation rail.
 *
 * The API already returns thread items in display order, so this helper keeps
 * ordering stable and only adds UI state needed by the post detail page.
 */
export function threadNavigationModel(items, currentId) {
  const posts = Array.isArray(items) ? items.filter(item => item?.id) : [];
  const currentIndex = posts.findIndex(item => item.id === currentId);

  return {
    previous: currentIndex > 0 ? posts[currentIndex - 1] : null,
    next: currentIndex >= 0 && currentIndex < posts.length - 1 ? posts[currentIndex + 1] : null,
    nodes: posts.map(item => ({
      id: item.id,
      username: item.username || 'user',
      text: snippet(item.text),
      depth: depthFor(item),
      selected: item.id === currentId
    }))
  };
}

function jumpLink(post, label, direction) {
  if (!post?.id) return '';
  return `<a class="void-thread-jump void-thread-jump-${direction}" href="/p/${encodeURIComponent(post.id)}">
    <span>${escapeHtml(label)}</span>
    <strong>@${escapeHtml(post.username || 'user')}</strong>
  </a>`;
}

function mapItem(node) {
  const selectedClass = node.selected ? ' is-selected' : '';
  const ariaCurrent = node.selected ? ' aria-current="true"' : '';
  return `<a class="void-thread-map-item${selectedClass}" href="/p/${encodeURIComponent(node.id)}" style="--thread-depth:${node.depth}"${ariaCurrent}>
    <span class="void-thread-map-rail" aria-hidden="true"></span>
    <span class="void-thread-map-copy">
      <span class="void-thread-map-handle">@${escapeHtml(node.username)}</span>
      <span class="void-thread-map-text">${escapeHtml(node.text)}</span>
    </span>
  </a>`;
}

/** Render the nested Signal Rail for a post detail page. */
export function renderThreadNavigation(items, currentId) {
  const model = threadNavigationModel(items, currentId);
  if (model.nodes.length === 0) return '';

  const jumps = [
    jumpLink(model.previous, 'Previous', 'previous'),
    jumpLink(model.next, 'Next', 'next')
  ].filter(Boolean).join('');

  return `
    ${jumps ? `<div class="void-thread-jump-links">${jumps}</div>` : ''}
    <div class="void-thread-map" aria-label="Signal Rail">
      <div class="void-thread-map-heading">
        <span>Signal Rail</span>
        <strong>${model.nodes.length}</strong>
      </div>
      <div class="void-thread-map-list">
        ${model.nodes.map(mapItem).join('')}
      </div>
    </div>`;
}
