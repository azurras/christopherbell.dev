import { imageFallbackMarkup } from './image-lightbox.js';
import { lazyIframeMarkup } from './lazy-media.js';
import { appendTextWithMentionLinks, loginRedirectUrl } from './util.js';

const LIFESPAN_TICK_MS = 1000;
const EXPIRY_ANIMATION_MS = 560;
const WEB_URL_RE = /\bhttps?:\/\/[^\s<>()]+/gi;
const URL_TRAILING_PUNCTUATION = /[.,!?;:]$/;
const YOUTUBE_VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;
const DIRECT_IMAGE_EXT_RE = /\.(jpe?g|png|gif|webp|avif)$/i;
const DIRECT_GIF_EXT_RE = /\.gif$/i;
const DIRECT_IMAGE_QUERY_KEYS = ['format', 'fm'];
const DIRECT_IMAGE_FORMATS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif']);
const SPOTIFY_TYPES = new Set(['track', 'album', 'playlist', 'episode', 'show']);
const YOUTUBE_HOSTS = new Set([
  'youtube.com',
  'www.youtube.com',
  'm.youtube.com',
  'music.youtube.com',
  'youtube-nocookie.com',
  'www.youtube-nocookie.com'
]);
let postMenuEscapeHandlerInitialized = false;

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

/** Build a privacy-enhanced YouTube embed URL for supported video links. */
export function youtubeEmbedUrl(url) {
  try {
    const parsed = new URL(trimUrlPunctuation(url));
    const host = parsed.hostname.toLowerCase();
    let videoId = '';

    if (host === 'youtu.be') {
      videoId = parsed.pathname.split('/').filter(Boolean)[0] || '';
    } else if (YOUTUBE_HOSTS.has(host)) {
      const pathParts = parsed.pathname.split('/').filter(Boolean);
      if (parsed.pathname === '/watch') {
        videoId = parsed.searchParams.get('v') || '';
      } else if (['embed', 'shorts', 'live'].includes(pathParts[0])) {
        videoId = pathParts[1] || '';
      }
    }

    return YOUTUBE_VIDEO_ID_RE.test(videoId)
      ? `https://www.youtube-nocookie.com/embed/${videoId}`
      : '';
  } catch (_) {
    return '';
  }
}

/** Extract distinct YouTube embeds from post text and stored link previews. */
export function youtubeEmbedUrlsForPost(post) {
  const urls = new Set();
  for (const url of urlsFromText(post?.text)) {
    const embedUrl = youtubeEmbedUrl(url);
    if (embedUrl) urls.add(embedUrl);
  }
  for (const preview of post?.linkPreviews || []) {
    const embedUrl = youtubeEmbedUrl(preview?.url);
    if (embedUrl) urls.add(embedUrl);
  }
  return [...urls];
}

/** Return a direct image URL for supported image links. */
export function directImageUrl(url) {
  try {
    const trimmed = trimUrlPunctuation(url);
    const parsed = new URL(trimmed);
    if (!['http:', 'https:'].includes(parsed.protocol)) return '';
    if (DIRECT_IMAGE_EXT_RE.test(parsed.pathname)) return parsed.href;
    return hasImageQueryFormat(parsed) ? parsed.href : '';
  } catch (_) {
    return '';
  }
}

/** Return a direct animated GIF URL when the link clearly points at GIF media. */
export function directGifUrl(url) {
  try {
    const trimmed = trimUrlPunctuation(url);
    const parsed = new URL(trimmed);
    if (!['http:', 'https:'].includes(parsed.protocol)) return '';
    if (DIRECT_GIF_EXT_RE.test(parsed.pathname)) return parsed.href;
    return hasGifQueryFormat(parsed) ? parsed.href : '';
  } catch (_) {
    return '';
  }
}

function hasImageQueryFormat(parsed) {
  return DIRECT_IMAGE_QUERY_KEYS
    .map(key => parsed.searchParams.get(key))
    .filter(Boolean)
    .some(value => DIRECT_IMAGE_FORMATS.has(value.toLowerCase()));
}

function hasGifQueryFormat(parsed) {
  return DIRECT_IMAGE_QUERY_KEYS
    .map(key => parsed.searchParams.get(key))
    .filter(Boolean)
    .some(value => value.toLowerCase() === 'gif');
}

/** Build a Spotify iframe URL for allowlisted Spotify content links. */
export function spotifyEmbedUrl(url) {
  try {
    const parsed = new URL(trimUrlPunctuation(url));
    if (parsed.hostname.toLowerCase() !== 'open.spotify.com') return '';
    const [type, id] = parsed.pathname.split('/').filter(Boolean);
    if (!SPOTIFY_TYPES.has(type) || !id) return '';
    return `https://open.spotify.com/embed/${type}/${id}`;
  } catch (_) {
    return '';
  }
}

/** Build a SoundCloud widget iframe URL for public SoundCloud links. */
export function soundCloudEmbedUrl(url) {
  try {
    const trimmed = trimUrlPunctuation(url);
    const parsed = new URL(trimmed);
    const host = parsed.hostname.toLowerCase();
    if (!['soundcloud.com', 'www.soundcloud.com'].includes(host)) return '';
    return `https://w.soundcloud.com/player/?url=${encodeURIComponent(trimmed)}`;
  } catch (_) {
    return '';
  }
}

/** Return first-party card data for GitHub repository, issue, and pull request links. */
export function githubCardDetail(url) {
  try {
    const parsed = new URL(trimUrlPunctuation(url));
    if (parsed.hostname.toLowerCase() !== 'github.com') return null;
    const [owner, repo, kind, number] = parsed.pathname.split('/').filter(Boolean);
    if (!owner || !repo) return null;

    let label = 'Repository';
    let suffix = '';
    if (kind === 'issues' && number) {
      label = `Issue #${number}`;
      suffix = `/issues/${number}`;
    } else if (kind === 'pull' && number) {
      label = `Pull request #${number}`;
      suffix = `/pull/${number}`;
    } else if (kind) {
      return null;
    }

    return {
      href: `https://github.com/${owner}/${repo}${suffix}`,
      owner,
      repo,
      label,
      title: `${owner}/${repo}`
    };
  } catch (_) {
    return null;
  }
}

function richEmbedForUrl(url) {
  const youtube = youtubeEmbedUrl(url);
  if (youtube) return { type: 'youtube', key: `youtube:${youtube}`, sourceUrl: trimUrlPunctuation(url), src: youtube };

  const gif = directGifUrl(url);
  if (gif) return { type: 'gif', key: `gif:${gif}`, sourceUrl: trimUrlPunctuation(url), src: gif };

  const image = directImageUrl(url);
  if (image) return { type: 'image', key: `image:${image}`, sourceUrl: trimUrlPunctuation(url), src: image };

  const spotify = spotifyEmbedUrl(url);
  if (spotify) return { type: 'spotify', key: `spotify:${spotify}`, sourceUrl: trimUrlPunctuation(url), src: spotify };

  const soundcloud = soundCloudEmbedUrl(url);
  if (soundcloud) return { type: 'soundcloud', key: `soundcloud:${soundcloud}`, sourceUrl: trimUrlPunctuation(url), src: soundcloud };

  const github = githubCardDetail(url);
  if (github) return { type: 'github', key: `github:${github.href}`, sourceUrl: trimUrlPunctuation(url), ...github };

  return null;
}

/** Extract rich embeds from post text and stored link previews. */
export function richEmbedsForPost(post) {
  const embeds = [];
  const seen = new Set();
  const urls = [
    ...urlsFromText(post?.text),
    ...(post?.linkPreviews || []).map(preview => preview?.url).filter(Boolean)
  ];

  for (const url of urls) {
    const embed = richEmbedForUrl(url);
    if (!embed || seen.has(embed.key)) continue;
    seen.add(embed.key);
    embeds.push(embed);
  }

  return embeds;
}

export function richEmbedMarkupForPost(post, sanitize) {
  if (typeof sanitize !== 'function') return '';

  const embeds = richEmbedsForPost(post);
  const imageEmbeds = embeds.filter(embed => embed.type === 'image' || embed.type === 'gif');
  const imageMarkup = imageEmbeds.length
    ? `<div class="post-rich-embeds-images" data-image-count="${imageEmbeds.length}">
      ${imageEmbeds.map(embed => {
        const isGif = embed.type === 'gif';
        return `<button type="button" class="post-rich-image-trigger${isGif ? ' post-rich-gif-trigger' : ''}" data-post-image-src="${sanitize(embed.src)}">
        <img class="post-rich-image" src="${sanitize(embed.src)}" alt="${isGif ? 'Animated GIF' : 'Post image'}" loading="lazy">
        ${isGif ? '<span class="post-rich-gif-badge">GIF</span>' : ''}
      </button>`;
      }).join('')}
    </div>`
    : '';

  const providerMarkup = embeds
    .filter(embed => embed.type !== 'image' && embed.type !== 'gif')
    .map((embed, index) => {
      if (embed.type === 'github') {
        return `<a class="post-rich-embed post-rich-card-github" href="${sanitize(embed.href)}" target="_blank" rel="noopener noreferrer">
          <span class="post-rich-provider">GitHub</span>
          <span class="post-rich-title">${sanitize(embed.title)}</span>
          <span class="post-rich-detail">${sanitize(embed.label)}</span>
        </a>`;
      }

      const provider = embed.type === 'youtube'
        ? 'YouTube'
        : embed.type === 'spotify'
          ? 'Spotify'
          : 'SoundCloud';
      const allow = embed.type === 'youtube'
        ? 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share'
        : 'autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture';

      return `<div class="post-rich-embed post-rich-embed-${embed.type}">
      ${lazyIframeMarkup({
        className: 'post-rich-iframe',
        src: embed.src,
        title: `${provider} embed ${index + 1}`,
        allow
      }, sanitize)}
      </div>`;
    })
    .join('');
  const markup = `${imageMarkup}${providerMarkup}`;
  return markup ? `<div class="post-rich-embeds">${markup}</div>` : '';
}

function hasRichEmbedForPreview(preview) {
  return !!richEmbedForUrl(preview?.url);
}

function urlsFromText(text) {
  const urls = [];
  WEB_URL_RE.lastIndex = 0;
  let match;
  while ((match = WEB_URL_RE.exec(String(text ?? ''))) !== null) {
    const url = trimUrlPunctuation(match[0]);
    if (url) urls.push(url);
  }
  return urls;
}

function trimUrlPunctuation(url) {
  let value = String(url || '');
  while (URL_TRAILING_PUNCTUATION.test(value)) {
    value = value.slice(0, -1);
  }
  return value;
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
  ensurePostMenuEscapeHandler();
  const s = ctx.sanitize;
  const when = ctx.formatWhen(post.createdOn || post.lastUpdatedOn);
  const handle = post.username ? `@${s(post.username)}` : '@user';
  const avatarInitial = (post.username || 'U')[0].toUpperCase();
  const liked = !!post.liked;
  const likes = post.likesCount || 0;
  const repliesCount = post.replyCount || 0;
  const shouldCollapse = (post.text || '').length > COLLAPSE_AT;
  const previewMarkup = (post.linkPreviews || [])
    .filter(preview => !hasRichEmbedForPreview(preview))
    .map(preview => linkPreviewCardMarkup(preview, s))
    .filter(Boolean)
    .join('');
  const richEmbedMarkup = richEmbedMarkupForPost(post, s);

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
              ${ctx.isLoggedIn() && typeof ctx.onHideThread === 'function' ? `<button class="post-hide-thread-btn" type="button" data-post="${post.id}">Hide thread</button>` : ''}
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
        ${richEmbedMarkup}
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
  for (const image of item.querySelectorAll('.post-rich-image')) {
    image.addEventListener('error', () => {
      const src = image.getAttribute('src') || '';
      const wrapper = document.createElement('div');
      wrapper.innerHTML = imageFallbackMarkup(src);
      image.closest('.post-rich-image-trigger')?.replaceWith(wrapper.firstElementChild);
    }, { once: true });
  }
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
    const hideThreadBtn = item.querySelector('.post-hide-thread-btn');
    hideThreadBtn?.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (typeof ctx.onHideThread !== 'function') return;
      menu.classList.add('d-none');
      try {
        hideThreadBtn.disabled = true;
        await ctx.onHideThread(post.id);
        item.remove();
      } catch (err) {
        hideThreadBtn.disabled = false;
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

function ensurePostMenuEscapeHandler() {
  if (postMenuEscapeHandlerInitialized || typeof document === 'undefined') return;
  postMenuEscapeHandlerInitialized = true;
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    document.querySelectorAll('.post-menu').forEach(menu => menu.classList.add('d-none'));
  });
}
