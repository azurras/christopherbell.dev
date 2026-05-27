import { API } from './lib/api.js';
import { fetchJson, sanitize } from './lib/util.js';

export const ACTIVE_POST_REFRESH_MS = 5000;
export const SIGNAL_RAIL_LIMIT = 5;
const ACTIVE_POST_FEED_LIMIT = 20;
const activePostMount = typeof document !== 'undefined' && typeof document.getElementById === 'function'
    ? document.getElementById('homeActivePost')
    : null;

export function activeScore(post) {
  return Math.max(0, Number(post?.likesCount || 0)) + Math.max(0, Number(post?.replyCount || 0));
}

function activityTime(post) {
  const value = post?.lastUpdatedOn || post?.createdOn || '';
  const timestamp = new Date(value).getTime();
  return Number.isFinite(timestamp) ? timestamp : 0;
}

export function selectMostActivePosts(posts = []) {
  return posts
      .filter(post => post?.id)
      .toSorted((a, b) => {
        const scoreDelta = activeScore(b) - activeScore(a);
        if (scoreDelta !== 0) return scoreDelta;
        return activityTime(b) - activityTime(a);
      })
      .slice(0, SIGNAL_RAIL_LIMIT);
}

function pluralize(count, label) {
  if (label === 'reply') {
    return `${count} ${count === 1 ? 'reply' : 'replies'}`;
  }
  return `${count} ${label}${count === 1 ? '' : 's'}`;
}

function signalRailItemMarkup(post) {
  const likes = Math.max(0, Number(post.likesCount || 0));
  const replies = Math.max(0, Number(post.replyCount || 0));
  const username = post.username || 'user';
  const text = post.text || 'This post is moving through the Void.';

  return `
    <a class="home-void-signal-item" href="/p/${encodeURIComponent(post.id)}">
      <span class="home-void-signal-copy">
        <strong>@${sanitize(username)}</strong>
        <span class="home-void-active-text">${sanitize(text)}</span>
        <span class="home-void-active-stats">
          <span>${pluralize(likes, 'like')}</span>
          <span>${pluralize(replies, 'reply')}</span>
        </span>
      </span>
    </a>
  `;
}

export function signalRailMarkup(posts = []) {
  if (!posts.length) {
    return `
      <div class="home-void-empty">
        <span class="home-void-eyebrow">Quiet signal</span>
        <strong>No active posts yet.</strong>
        <span>Drop into the Void and start the noise.</span>
      </div>
    `;
  }

  return `
    <div class="home-void-signal-list" aria-label="Signal Rail">
      <span class="visually-hidden">Signal Rail</span>
      ${posts.slice(0, SIGNAL_RAIL_LIMIT).map(signalRailItemMarkup).join('')}
    </div>
  `;
}

async function loadActivePost() {
  if (!activePostMount) return;

  try {
    const params = new URLSearchParams({ limit: String(ACTIVE_POST_FEED_LIMIT) });
    const posts = await fetchJson(`${API.posts.feed}?${params.toString()}`);
    activePostMount.innerHTML = signalRailMarkup(selectMostActivePosts(posts || []));
  } catch (_) {
    activePostMount.innerHTML = `
      <div class="home-void-empty">
        <span class="home-void-eyebrow">Signal interrupted</span>
        <strong>Could not load the active post.</strong>
        <span>The rail will try again shortly.</span>
      </div>
    `;
  }
}

if (activePostMount) {
  loadActivePost();
  window.setInterval(loadActivePost, ACTIVE_POST_REFRESH_MS);
}
