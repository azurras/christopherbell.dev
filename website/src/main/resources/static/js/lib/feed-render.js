import { appendTextWithMentionLinks, loginRedirectUrl } from './util.js';

const LIFESPAN_TICK_MS = 1000;
const EXPIRY_ANIMATION_MS = 560;

export function remainingLifespanMs(expiresOn, now = Date.now()) {
  if (!expiresOn) return null;
  const delta = new Date(expiresOn).getTime() - now;
  if (!Number.isFinite(delta)) return null;
  return Math.max(0, delta);
}

export function formatLifespanCountdown(expiresOn, now = Date.now()) {
  const remaining = remainingLifespanMs(expiresOn, now);
  if (remaining === null) return '';

  const totalSeconds = Math.max(0, Math.ceil(remaining / 1000));
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const clock = [hours, minutes, seconds].map(value => String(value).padStart(2, '0')).join(':');
  return days > 0 ? `${days}d ${clock}` : clock;
}

export function linkPreviewCardMarkup(preview, sanitize) {
  if (!preview?.url || typeof sanitize !== 'function') return '';

  const domain = preview.domain || previewDomain(preview.url) || preview.url;
  const title = preview.title || domain;
  if (!title) return '';

  const image = preview.imageUrl
    ? `<span class="post-link-preview-image"><img src="${sanitize(preview.imageUrl)}" alt=""></span>`
    : '';
  const description = preview.description
    ? `<span class="post-link-preview-description">${sanitize(preview.description)}</span>`
    : '';
  return `<a class="post-link-preview${image ? '' : ' post-link-preview-no-image'}" href="${sanitize(preview.url)}" target="_blank" rel="noopener noreferrer">
    ${image}
    <span class="post-link-preview-copy">
      <span class="post-link-preview-domain">${sanitize(domain)}</span>
      <span class="post-link-preview-title">${sanitize(title)}</span>
      ${description}
    </span>
  </a>`;
}

function previewDomain(url) {
  try {
    return new URL(url).hostname;
  } catch (_) {
    return '';
  }
}

const COLLAPSE_AT = 180;

function isRecent(post) {
  const value = post.createdOn || post.lastUpdatedOn;
  if (!value) return false;
  return Date.now() - new Date(value).getTime() < 10 * 60 * 1000;
}

export function expiresSoon(post, now = Date.now()) {
  if (!post.expiresOn) return false;
  const delta = new Date(post.expiresOn).getTime() - now;
  return delta > 0 && delta <= 12 * 60 * 60 * 1000;
}

function postChips(post, liked) {
  const chips = [];
  if (post.level && post.level > 0) chips.push(['reply', 'Reply']);
  if (liked) chips.push(['liked', 'Liked']);
  if (isRecent(post)) chips.push(['new', 'New']);
  if (expiresSoon(post)) chips.push(['expires', 'Expires soon']);
  return chips.map(([type, label]) => `<span class="post-chip post-chip-${type}">${label}</span>`).join('');
}

function postPermalink(postId) {
  return `${window.location.origin}/p/${encodeURIComponent(postId)}`;
}

function expireFeedItem(item, post, ctx) {
  if (item.dataset.expiring === 'true') return null;
  item.dataset.expiring = 'true';
  item.classList.add('post-item-expiring');

  return window.setTimeout(() => {
    if (typeof ctx.onExpire === 'function') {
      ctx.onExpire(post, item);
      return;
    }
    item.remove();
  }, EXPIRY_ANIMATION_MS);
}

function startLifespanTimer(item, post, ctx) {
  let label = item.querySelector('[data-post-lifespan]');
  let expiresOn = post.expiresOn || null;
  let intervalId = null;
  let expiryTimeoutId = null;
  let wasConnected = item.isConnected;

  const ensureLabel = () => {
    if (label) return label;
    const author = item.querySelector('.post-author');
    if (!author) return null;

    label = document.createElement('span');
    label.className = 'post-lifespan';
    label.setAttribute('data-post-lifespan', '');
    label.setAttribute('aria-label', 'Post lifespan remaining');
    author.appendChild(label);
    return label;
  };

  const stop = () => {
    if (intervalId !== null) {
      window.clearInterval(intervalId);
      intervalId = null;
    }
  };

  const cancelExpiry = () => {
    if (expiryTimeoutId === null) return;
    window.clearTimeout(expiryTimeoutId);
    expiryTimeoutId = null;
    delete item.dataset.expiring;
    item.classList.remove('post-item-expiring');
  };

  const tick = () => {
    if (item.isConnected) {
      wasConnected = true;
    } else if (wasConnected) {
      stop();
      return false;
    }

    const remaining = remainingLifespanMs(expiresOn);
    if (remaining === null) {
      if (label) {
        label.textContent = '';
        label.classList.add('d-none');
      }
      stop();
      return false;
    }

    const currentLabel = ensureLabel();
    if (!currentLabel) {
      stop();
      return false;
    }

    currentLabel.classList.remove('d-none');
    currentLabel.textContent = formatLifespanCountdown(expiresOn);
    currentLabel.classList.toggle('is-ending', remaining <= 60 * 60 * 1000);

    if (remaining === 0) {
      stop();
      if (expiryTimeoutId === null) {
        expiryTimeoutId = expireFeedItem(item, post, ctx);
      }
      return false;
    }

    return true;
  };

  if (tick()) {
    intervalId = window.setInterval(tick, LIFESPAN_TICK_MS);
  }

  return {
    update(nextExpiresOn) {
      if (!nextExpiresOn) return;

      const remaining = remainingLifespanMs(nextExpiresOn);
      expiresOn = nextExpiresOn;
      post.expiresOn = nextExpiresOn;
      if (remaining !== null && remaining > 0) {
        cancelExpiry();
      }
      if (tick() && intervalId === null) {
        intervalId = window.setInterval(tick, LIFESPAN_TICK_MS);
      }
    },
    stop
  };
}

/**
 * Create a DOM element representing a post in a feed.
 *
 * The caller supplies minimal callbacks to keep this module focused on
 * rendering and simple event wiring.
 *
 * @param {object} post feed item ({ id, username, accountId, text, createdOn, lastUpdatedOn, expiresOn, level, rootId, likesCount, liked })
 * @param {object} ctx  context with small helpers:
 *  - sanitize(text): string
 *  - formatWhen(iso): string
 *  - isLoggedIn(): boolean
 *  - canDelete(post): boolean
 *  - onLike(postId): Promise<{likesCount:number, liked:boolean, expiresOn?:string}>
 *  - onDelete(postId): Promise<void>
 *  - onExpire(post, item): void (optional)
 *  - fetchRoot(rootId): Promise<{username:string, text:string}>
 * @returns {HTMLElement}
 */
export function createFeedItem(post, ctx) {
  const s = ctx.sanitize;
  const when = ctx.formatWhen(post.createdOn || post.lastUpdatedOn);
  const handle = post.username ? `@${s(post.username)}` : '@user';
  const avatarInitial = (post.username || 'U')[0].toUpperCase();
  const liked = !!post.liked;
  const likes = post.likesCount || 0;
  const repliesCount = post.replyCount || 0;
  const shouldCollapse = (post.text || '').length > COLLAPSE_AT;
  const previewMarkup = (post.linkPreviews || [])
    .map(preview => linkPreviewCardMarkup(preview, s))
    .filter(Boolean)
    .join('');

  const item = document.createElement('div');
  item.className = `post-item${isRecent(post) ? ' post-item-new' : ''}`;
  item.innerHTML = `
    <div class="post-accent" aria-hidden="true"></div>
    <div class="post-shell">
      <div class="post-avatar flex-shrink-0">
        <span class="post-avatar-text">${avatarInitial}</span>
      </div>
      <div class="post-content">
        <div class="post-header">
          <div class="post-author">
            <a href="/u/${encodeURIComponent(post.username || '')}" class="post-handle">${handle}</a>
            <small>${when}</small>
            ${post.expiresOn ? '<span class="post-lifespan" data-post-lifespan aria-label="Post lifespan remaining"></span>' : ''}
          </div>
          <div class="post-meta">
            <div class="post-chips">${postChips(post, liked)}</div>
            <button class="post-menu-btn" data-post="${post.id}" aria-label="More actions">
              <i class="fa fa-ellipsis-h" aria-hidden="true"></i>
            </button>
            <div class="post-menu d-none">
              <button class="post-copy-btn" type="button" data-post="${post.id}">Copy link</button>
              <button class="post-report-btn" type="button" data-post="${post.id}">Report</button>
              ${ctx.canDelete(post) ? `<button class="post-delete-btn danger" type="button" data-post="${post.id}">Delete</button>` : ''}
            </div>
          </div>
        </div>
        ${post.level && post.level > 0 && post.parentId && !ctx.suppressParentContext ? `<div class="parent-context inline-context">
          <div class="context-label">Replying to <a href="/u/" data-parent-handle="${post.parentId}">@user</a></div>
          <p data-parent="${post.parentId}">Loading context…</p>
          <a href="/p/${encodeURIComponent(post.parentId)}">View parent</a>
        </div>` : ''}
        <div class="post-text-wrap ${shouldCollapse ? 'is-collapsed' : ''}">
          <p class="post-text post-body"></p>
        </div>
        ${shouldCollapse ? '<button class="post-expand-btn" type="button">Show more</button>' : ''}
        ${previewMarkup ? `<div class="post-link-previews">${previewMarkup}</div>` : ''}
        <div class="post-actions">
          <button class="post-action post-reply-btn" data-post="${post.id}" aria-label="Reply">
            <i class="fa fa-comment-o" aria-hidden="true"></i>
            <span class="reply-count">${repliesCount}</span>
          </button>
          <button class="post-action post-like-btn ${liked ? 'is-liked' : ''}" data-post="${post.id}" data-liked="${liked}" aria-label="Like">
            <i class="fa ${liked ? 'fa-heart' : 'fa-heart-o'}" aria-hidden="true"></i>
            <span class="like-count">${likes}</span>
          </button>
          <button class="post-action post-replies-toggle" data-post="${post.id}" aria-expanded="false">
            <i class="fa fa-comments-o" aria-hidden="true"></i>
            <span class="toggle-label">Replies</span>
          </button>
        </div>
        <div class="reply-composer d-none">
          <textarea class="form-control reply-text" rows="2" maxlength="280" placeholder="Write a reply..."></textarea>
          <div class="reply-composer-actions">
            <button class="btn btn-sm btn-outline-secondary reply-cancel" type="button">Cancel</button>
            <button class="btn btn-sm btn-dark reply-submit" type="button">Reply</button>
          </div>
        </div>
        <div class="replies d-none"></div>
      </div>
    </div>
  `;

  const body = item.querySelector('.post-text');
  if (body) appendTextWithMentionLinks(body, post.text);
  const lifespanTimer = startLifespanTimer(item, post, ctx);
  const expandBtn = item.querySelector('.post-expand-btn');
  expandBtn?.addEventListener('click', () => {
    const wrap = item.querySelector('.post-text-wrap');
    const collapsed = wrap?.classList.toggle('is-collapsed');
    expandBtn.textContent = collapsed ? 'Show more' : 'Show less';
  });

  // Make the whole item (except action bar and existing links/buttons) navigate to the post
  item.addEventListener('click', (e) => {
    const target = e.target;
    // Ignore clicks on bottom actions, menus, composers or any anchor/button
    if (target.closest('.post-actions') || target.closest('.post-menu') ||
        target.closest('.reply-composer') || target.closest('a') || target.closest('button')) {
      return;
    }
    window.location.href = `/p/${encodeURIComponent(post.id)}`;
  });

  // Wire like toggle
  const likeBtn = item.querySelector('.post-like-btn');
  if (likeBtn) {
    likeBtn.addEventListener('click', async () => {
      if (!ctx.isLoggedIn()) { window.location.href = loginRedirectUrl(); return; }
      try {
        const updated = await ctx.onLike(post.id);
        const countEl = likeBtn.querySelector('.like-count');
        if (countEl) countEl.textContent = updated.likesCount ?? 0;
        const isLiked = !!updated.liked;
        likeBtn.dataset.liked = isLiked;
        likeBtn.classList.toggle('is-liked', isLiked);
        likeBtn.classList.add('like-pulse');
        setTimeout(() => likeBtn.classList.remove('like-pulse'), 420);
        const icon = likeBtn.querySelector('i');
        if (icon) {
          icon.classList.toggle('fa-heart', isLiked);
          icon.classList.toggle('fa-heart-o', !isLiked);
        }
        if (updated.expiresOn) {
          lifespanTimer.update(updated.expiresOn);
        }
        // Server is source of truth for liked state
      } catch (err) {
        alert(err.message);
      }
    });
  }

  // Wire reply inline composer
  const replyBtn = item.querySelector('.post-reply-btn');
  const replyBox = item.querySelector('.reply-composer');
  const replyText = item.querySelector('.reply-text');
  const replySubmit = item.querySelector('.reply-submit');
  const replyCancel = item.querySelector('.reply-cancel');
  if (replyBtn && replyBox && replyText && replySubmit && replyCancel) {
    replyBtn.addEventListener('click', () => {
      if (!ctx.isLoggedIn()) { window.location.href = loginRedirectUrl(); return; }
      replyBox.classList.toggle('d-none');
      if (!replyBox.classList.contains('d-none')) {
        setTimeout(() => replyText.focus(), 0);
      }
    });
    replyCancel.addEventListener('click', () => {
      replyText.value = '';
      replyBox.classList.add('d-none');
    });
    replySubmit.addEventListener('click', async () => {
      if (!ctx.isLoggedIn()) { window.location.href = loginRedirectUrl(); return; }
      const text = (replyText.value || '').trim();
      if (!text) return;
      try {
        replySubmit.disabled = true;
        if (typeof ctx.onReply === 'function') {
          await ctx.onReply(post.id, text);
        } else {
          // Fallback: navigate to full post view
          window.location.href = `/p/${encodeURIComponent(post.id)}`;
          return;
        }
        replyText.value = '';
        replyBox.classList.add('d-none');
        // Increment reply count in action bar
        const rc = item.querySelector('.reply-count');
        if (rc) {
          const curr = parseInt(rc.textContent || '0', 10) || 0;
          rc.textContent = String(curr + 1);
        }
        // Append a minimal inline reply row
        const replies = item.querySelector('.replies');
        if (replies) {
          const who = ctx.currentUserName ? `@${s(ctx.currentUserName)}` : 'You';
          const whenStr = ctx.formatWhen(new Date().toISOString());
          const row = document.createElement('div');
          row.className = 'inline-reply';
          row.innerHTML = `
            <div class="inline-reply-body">
                <div class="inline-reply-meta"><span>${who}</span> · ${whenStr}</div>
                <div class="post-body"></div>
            </div>`;
          const replyBody = row.querySelector('.post-body');
          if (replyBody) appendTextWithMentionLinks(replyBody, text);
          replies.appendChild(row);
        }
        // Quick inline toast
        const toast = document.createElement('div');
        toast.className = 'feed-toast';
        toast.textContent = 'Reply posted';
        replyBox.parentElement?.insertBefore(toast, replyBox.nextSibling);
        setTimeout(() => toast.remove(), 2000);
      } catch (err) {
        alert(err.message);
      } finally {
        replySubmit.disabled = false;
      }
    });
  }

  // Wire show/hide replies toggle with lazy fetch
  const replToggle = item.querySelector('.post-replies-toggle');
  const replies = item.querySelector('.replies');
  if (replToggle && replies) {
    replToggle.addEventListener('click', async () => {
      const expanded = replToggle.getAttribute('aria-expanded') === 'true';
      const label = replToggle.querySelector('.toggle-label');
      if (!expanded) {
        // First expansion: fetch if not already loaded
        if (!replies.dataset.loaded) {
          try {
            const thread = await (typeof ctx.fetchThread === 'function' ? ctx.fetchThread(post.id) : Promise.resolve([]));
            // Render only direct replies to this post for a compact view
            const direct = (thread || []).filter(r => r.parentId === post.id);
            replies.innerHTML = '';
            if (direct.length === 0) {
              const empty = document.createElement('div');
              empty.className = 'text-muted small mt-1';
              empty.textContent = 'No replies yet';
              replies.appendChild(empty);
            } else {
              for (const r of direct) {
                const row = document.createElement('div');
                row.className = 'inline-reply';
                row.innerHTML = `
                  <div class="inline-reply-body">
                      <div class="inline-reply-meta"><a href="/u/${encodeURIComponent(r.username || '')}">@${s(r.username || 'user')}</a> · ${ctx.formatWhen(r.createdOn || r.lastUpdatedOn)}</div>
                      <div class="post-body"></div>
                  </div>`;
                const replyBody = row.querySelector('.post-body');
                if (replyBody) appendTextWithMentionLinks(replyBody, r.text || '');
                replies.appendChild(row);
              }
              // View full thread link
              const more = document.createElement('div');
              more.className = 'inline-reply-more';
              more.innerHTML = `<a href="/p/${encodeURIComponent(post.id)}" class="small">View full thread</a>`;
              replies.appendChild(more);
            }
            replies.dataset.loaded = 'true';
          } catch (err) {
            replies.innerHTML = `<div class="text-danger small mt-1">${s(err.message)}</div>`;
            replies.dataset.loaded = 'true';
          }
        }
        replies.classList.remove('d-none');
        replToggle.setAttribute('aria-expanded', 'true');
        if (label) label.textContent = 'Hide';
      } else {
        replies.classList.add('d-none');
        replToggle.setAttribute('aria-expanded', 'false');
        if (label) label.textContent = 'Replies';
      }
    });
  }

  // Wire menu toggle, report, and delete
  const menuBtn = item.querySelector('.post-menu-btn');
  const menu = item.querySelector('.post-menu');
  if (menuBtn && menu) {
    menuBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      menu.classList.toggle('d-none');
    });
    const reportBtn = item.querySelector('.post-report-btn');
    reportBtn?.addEventListener('click', (e) => {
      e.stopPropagation();
      menu.classList.add('d-none');
      window.location.href = `/report?postId=${encodeURIComponent(post.id)}`;
    });
    const copyBtn = item.querySelector('.post-copy-btn');
    copyBtn?.addEventListener('click', async (e) => {
      e.stopPropagation();
      menu.classList.add('d-none');
      const url = postPermalink(post.id);
      try {
        await navigator.clipboard.writeText(url);
        copyBtn.textContent = 'Copied';
        setTimeout(() => { copyBtn.textContent = 'Copy link'; }, 1200);
      } catch (_) {
        window.prompt('Copy post link', url);
      }
    });
    const del = item.querySelector('.post-delete-btn');
    del?.addEventListener('click', async (e) => {
      e.stopPropagation();
      menu.classList.add('d-none');
      if (!confirm('Delete this post?')) return;
      try {
        await ctx.onDelete(post.id);
        item.remove();
      } catch (err) {
        alert(err.message);
      }
    });
  }

  // Fill parent context
  const fetchContext = ctx.fetchParent || ctx.fetchRoot;
  if (post.level && post.level > 0 && post.parentId && fetchContext && !ctx.suppressParentContext) {
    const ctxEl = item.querySelector(`[data-parent="${post.parentId}"]`);
    const handleEl = item.querySelector(`[data-parent-handle="${post.parentId}"]`);
    if (ctxEl) {
      (async () => {
        try {
          const parent = await fetchContext(post.parentId);
          const h = parent.username ? `@${s(parent.username)}` : '@user';
          if (handleEl) {
            handleEl.textContent = h;
            handleEl.setAttribute('href', `/u/${encodeURIComponent(parent.username || '')}`);
          }
          appendTextWithMentionLinks(ctxEl, parent.text || '');
        } catch (e) {
          ctxEl.textContent = 'Context unavailable';
        }
      })();
    }
  }

  return item;
}
