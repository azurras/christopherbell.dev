/**
 * Create a DOM element representing a post in a feed.
 *
 * The caller supplies minimal callbacks to keep this module focused on
 * rendering and simple event wiring.
 *
 * @param {object} post feed item ({ id, username, accountId, text, createdOn, lastUpdatedOn, level, rootId, likesCount, liked })
 * @param {object} ctx  context with small helpers:
 *  - sanitize(text): string
 *  - formatWhen(iso): string
 *  - isLoggedIn(): boolean
 *  - canDelete(post): boolean
 *  - onLike(postId): Promise<{likesCount:number, liked:boolean}>
 *  - onDelete(postId): Promise<void>
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

  const item = document.createElement('div');
  item.className = 'list-group-item py-3 post-item';
  item.innerHTML = `
    <div class="d-flex w-100">
      <div class="post-avatar flex-shrink-0 me-1">
        <span class="post-avatar-text">${avatarInitial}</span>
      </div>
      <div class="post-content flex-grow-1">
        <div class="post-header d-flex align-items-center gap-2 w-100">
          <div class="fw-semibold post-handle">
            <a href="/u/${encodeURIComponent(post.username || '')}" class="link-underline link-underline-opacity-0">${handle}</a>
          </div>
          <div class="post-meta ms-auto d-flex align-items-center gap-2 position-relative">
            <small class="text-muted">${when}</small>
            <button class="btn btn-sm btn-light post-menu-btn" data-post="${post.id}" aria-label="More">⋯</button>
            <div class="post-menu d-none card p-2" style="position:absolute; right:0; top:100%; z-index:1000;">
              <button class="btn btn-link text-danger p-0 post-report-btn" data-post="${post.id}">Report</button>
              ${ctx.canDelete(post) ? `<button class="btn btn-link text-danger p-0 post-delete-btn" data-post="${post.id}">Delete</button>` : ''}
            </div>
          </div>
        </div>
      </div>
    </div>
    <p class="post-text"><a href="/p/${encodeURIComponent(post.id)}" class="post-link text-body post-body">${s(post.text)}</a></p>
    ${post.level && post.level > 0 && post.parentId && !ctx.suppressParentContext ? `<div class="parent-context card mt-2 w-100">
        <div class="card-body py-2">
          <div class="fw-semibold"><a href="/u/" class="link-underline link-underline-opacity-0" data-parent-handle="${post.parentId}">@user</a></div>
          <p class="mb-0 post-context-text fw-semibold" data-parent="${post.parentId}">Loading…</p>
          <a href="/p/${encodeURIComponent(post.parentId)}" class="small">View thread</a>
        </div>
      </div>` : ''}
    <div class="d-flex align-items-center gap-4 border-top pt-2 mt-2 post-actions">
      <button class="btn btn-link btn-sm text-decoration-none text-muted post-reply-btn" data-post="${post.id}" aria-label="Reply">
        <i class="fa fa-comment-o" aria-hidden="true"></i>
        <span class="reply-count ms-1">${repliesCount}</span>
        <span class="visually-hidden">Reply</span>
      </button>
      <button class="btn btn-link btn-sm text-decoration-none post-like-btn ${liked ? 'text-danger' : 'text-muted'}" data-post="${post.id}" data-liked="${liked}" aria-label="Like">
        <i class="fa ${liked ? 'fa-heart' : 'fa-heart-o'}" aria-hidden="true"></i>
        <span class="like-count ms-1">${likes}</span>
      </button>
      <button class="btn btn-link btn-sm text-decoration-none text-muted post-replies-toggle" data-post="${post.id}" aria-expanded="false">
        <i class="fa fa-comments-o" aria-hidden="true"></i>
        <span class="toggle-label">Show replies</span>
      </button>
    </div>
    <div class="reply-composer d-none mt-2">
      <textarea class="form-control reply-text" rows="2" maxlength="280" placeholder="Write a reply..."></textarea>
      <div class="d-flex justify-content-end gap-2 mt-2">
        <button class="btn btn-sm btn-secondary reply-cancel" type="button">Cancel</button>
        <button class="btn btn-sm btn-primary reply-submit" type="button">Reply</button>
      </div>
    </div>
    <div class="replies d-none"></div>
  `;

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
      if (!ctx.isLoggedIn()) { window.location.href = '/login'; return; }
      try {
        const updated = await ctx.onLike(post.id);
        const countEl = likeBtn.querySelector('.like-count');
        if (countEl) countEl.textContent = updated.likesCount ?? 0;
        const isLiked = !!updated.liked;
        likeBtn.dataset.liked = isLiked;
        likeBtn.classList.toggle('text-danger', isLiked);
        likeBtn.classList.toggle('text-muted', !isLiked);
        const icon = likeBtn.querySelector('i');
        if (icon) {
          icon.classList.toggle('fa-heart', isLiked);
          icon.classList.toggle('fa-heart-o', !isLiked);
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
      if (!ctx.isLoggedIn()) { window.location.href = '/login'; return; }
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
      if (!ctx.isLoggedIn()) { window.location.href = '/login'; return; }
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
          row.className = 'mt-2';
          row.innerHTML = `
            <div class="d-flex">
              <div class="flex-grow-1">
                <div class="small text-muted"><span class="fw-semibold">${who}</span> · ${whenStr}</div>
                <div class="post-body">${s(text)}</div>
              </div>
            </div>`;
          replies.appendChild(row);
        }
        // Quick inline toast
        const toast = document.createElement('div');
        toast.className = 'small text-success mt-1';
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
                row.className = 'mt-2';
                row.innerHTML = `
                  <div class="d-flex">
                    <div class="flex-grow-1">
                      <div class="small text-muted"><a href="/u/${encodeURIComponent(r.username || '')}" class="link-underline link-underline-opacity-0 fw-semibold">@${s(r.username || 'user')}</a> · ${ctx.formatWhen(r.createdOn || r.lastUpdatedOn)}</div>
                      <div><a href="/p/${encodeURIComponent(r.id)}" class="link-underline link-underline-opacity-0 text-body post-body">${s(r.text || '')}</a></div>
                    </div>
                  </div>`;
                replies.appendChild(row);
              }
              // View full thread link
              const more = document.createElement('div');
              more.className = 'mt-2';
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
        if (label) label.textContent = 'Hide replies';
      } else {
        replies.classList.add('d-none');
        replToggle.setAttribute('aria-expanded', 'false');
        if (label) label.textContent = 'Show replies';
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
          ctxEl.textContent = parent.text ? `${parent.text}` : '';
        } catch (e) {
          ctxEl.textContent = 'Context unavailable';
        }
      })();
    }
  }

  return item;
}
