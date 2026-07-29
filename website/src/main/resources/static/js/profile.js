import { authHeaders, fetchJson, sanitize, isLoggedIn, formatWhen, loginRedirectUrl } from './lib/util.js';
import { API } from './lib/api.js';
import { createFeedItem } from './lib/feed-render.js';
import { canEditFor, makeRendererContext } from './lib/feed-context.js';
import { initPostImageLightbox } from './lib/image-lightbox.js';
import { initLazyMedia } from './lib/lazy-media.js';
import { profileActivityStats } from './lib/profile-stats.js';
import {
  federationConsentControlModel,
  normalizeFederationConsentStatus,
} from './lib/federation-consent.js';

/** Get the alert element for error display. */
const alertBox = () => document.getElementById('profileAlert');
const displayValue = (value) => value || '-';
let federationStatus = null;

function initialsFor(detail) {
  const source = [detail.firstName, detail.lastName].filter(Boolean).join(' ') || detail.username || '?';
  return source
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map(part => part[0]?.toUpperCase() || '')
    .join('') || '?';
}

/**
 * Render the account summary panel.
 * @param {{username:string,email:string,firstName?:string,lastName?:string,role?:string,status?:string}} detail
 */
function renderAccount(detail) {
  const root = document.getElementById('accountDetails');
  if (!root) return;
  const set = (name, value) => {
    const el = root.querySelector(`[data-field="${name}"]`);
    if (el) el.textContent = displayValue(value);
  };
  const fullName = [detail.firstName, detail.lastName].filter(Boolean).join(' ').trim();
  const handle = detail.username ? `@${detail.username}` : '@user';

  const title = document.getElementById('profileTitle');
  if (title) title.textContent = handle;
  const heroMeta = document.getElementById('profileHeroMeta');
  if (heroMeta) heroMeta.textContent = fullName ? `${fullName}'s posts and account details.` : 'Your posts and account details.';

  const avatar = document.getElementById('profileAvatar');
  if (avatar) avatar.textContent = initialsFor(detail);
  const profileHandle = document.getElementById('profileHandle');
  if (profileHandle) profileHandle.textContent = handle;
  const profileName = document.getElementById('profileName');
  if (profileName) profileName.textContent = fullName || 'Name unavailable';
  const role = document.getElementById('profileRole');
  if (role) role.textContent = displayValue(detail.role);
  const status = document.getElementById('profileStatus');
  if (status) {
    status.textContent = displayValue(detail.status);
    status.classList.toggle('is-suspended', detail.status === 'SUSPENDED');
    status.classList.toggle('is-active', detail.status === 'ACTIVE');
  }

  set('username', detail.username);
  set('email', detail.email);
  set('name', fullName);
  set('role', detail.role);
  set('status', detail.status);
}

function renderStats(profile, posts) {
  const items = Array.isArray(posts) ? posts : [];
  const stats = profileActivityStats({
    postCount: profile?.postCount ?? items.length,
    replyCount: profile?.replyCount ?? items.filter(post => post.level && post.level > 0).length,
    followerCount: profile?.followerCount ?? 0,
    followingCount: profile?.followingCount ?? 0
  });
  const set = (id, value) => {
    const el = document.getElementById(id);
    if (el) el.textContent = String(value);
  };
  set('profilePostCount', stats.postCount);
  set('profileReplyCount', stats.replyCount);
  set('profileFollowerCount', stats.followerCount);
  set('profileFollowingCount', stats.followingCount);
}

function renderFederationConsent(status, busy = false, overrideMessage = '') {
  federationStatus = normalizeFederationConsentStatus(status);
  const model = federationConsentControlModel(federationStatus, busy);
  const control = document.getElementById('profileFederationEnabled');
  const statusText = document.getElementById('profileFederationStatus');
  if (control) {
    control.checked = model.checked;
    control.disabled = model.disabled;
  }
  if (statusText) statusText.textContent = overrideMessage || model.message;
}

async function fetchFederationConsent() {
  const status = await fetchJson(API.accounts.federation, {
    headers: authHeaders(),
    redirectOnUnauthorized: true,
  });
  return normalizeFederationConsentStatus(status);
}

async function initializeFederationConsent() {
  const control = document.getElementById('profileFederationEnabled');
  if (!control) return;
  try {
    renderFederationConsent(await fetchFederationConsent());
  } catch (error) {
    control.disabled = true;
    const statusText = document.getElementById('profileFederationStatus');
    if (statusText) statusText.textContent = error.message || 'Could not load federation status.';
    return;
  }

  control.addEventListener('change', async () => {
    const previous = federationStatus;
    const requested = control.checked;
    renderFederationConsent(previous, true);
    try {
      await fetchJson(API.accounts.federation, {
        method: 'PATCH',
        headers: authHeaders(),
        body: JSON.stringify({ enabled: requested }),
      });
      renderFederationConsent(await fetchFederationConsent());
    } catch (error) {
      let authoritative = previous;
      try {
        authoritative = await fetchFederationConsent();
      } catch (_) {
        // The last confirmed state is safer than leaving the optimistic choice rendered.
      }
      renderFederationConsent(
        authoritative,
        false,
        error.message || 'Could not update federation. Your confirmed choice was restored.',
      );
    }
  });
}

const ROOT_CACHE = {};

/**
 * Render the profile feed list.
 * @param {Array} posts feed items
 * @param {string} username current user's handle
 */
function renderPosts(posts, username) {
  const container = document.getElementById('postsList');
  if (!container) return;
  container.innerHTML = '';
  if (!posts || posts.length === 0) {
    container.innerHTML = `
      <div class="feed-empty-state profile-empty-state">
        <h2>Nothing in the Void yet</h2>
        <p>Your posts will land here once you send something into the feed.</p>
      </div>`;
    return;
  }
  const canDelete = () => true; // current user owns their posts on /profile
  const ctx = makeRendererContext({
    fetchJson,
    authHeaders,
    sanitize,
    formatWhen,
    isLoggedIn,
    canDelete,
    canEdit: post => canEditFor({ id: post.accountId })(post),
    currentUserName: username
  });
  for (const p of posts) {
    const fetchRootCached = async (rootId) => {
      if (!ROOT_CACHE[rootId]) ROOT_CACHE[rootId] = await fetchJson(API.posts.byId(rootId));
      return ROOT_CACHE[rootId];
    };
    ctx.fetchRoot = fetchRootCached;
    ctx.fetchParent = fetchRootCached;
    const el = createFeedItem({ ...p, username }, ctx);
    container.appendChild(el);
  }
  initLazyMedia(container);
}

/** Wire page once DOM is ready. */
document.addEventListener('DOMContentLoaded', async () => {
  initPostImageLightbox();

  if (!isLoggedIn()) {
    // Must be logged in
    window.location.href = loginRedirectUrl();
    return;
  }
  const alert = alertBox();
  alert?.classList.add('d-none');
  try {
    const me = await fetchJson(API.accounts.me, {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    renderAccount(me);
    await initializeFederationConsent();
    const posts = await fetchJson(`${API.posts.meFeed}?limit=20`, {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    const publicProfile = me?.username
      ? await fetchJson(API.accounts.profile(me.username), { headers: authHeaders() })
      : null;
    renderStats(publicProfile, posts);
    renderPosts(posts, me?.username);
  } catch (err) {
    if (alert) {
      alert.textContent = err.message;
      alert.classList.remove('d-none');
    }
  }
  // Close menus on outside click
  document.addEventListener('click', () => {
    document.querySelectorAll('.post-menu').forEach(m => m.classList.add('d-none'));
  });
});
