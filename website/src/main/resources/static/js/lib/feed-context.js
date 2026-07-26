/**
 * Small helpers to build feed rendering context functions.
 */
import { API } from './api.js';

/**
 * Create a post fetcher with simple in-memory caching.
 * @param {(url:string)=>Promise<object>} fetchJson
 * @returns {(postId:string)=>Promise<object>}
 */
export function createRootFetcher(fetchJson) {
  const cache = {};
  return async function fetchRoot(postId) {
    if (!cache[postId]) cache[postId] = await fetchJson(API.posts.byId(postId));
    return cache[postId];
  };
}

/**
 * Build a canDelete predicate for a given current user.
 * @param {{id?:string, role?:string}} currentUser
 * @returns {(post:{accountId?:string})=>boolean}
 */
export function canDeleteFor(currentUser) {
  return function (post) {
    if (!currentUser) return false;
    if (currentUser.role === 'ADMIN') return true;
    return !!currentUser.id && currentUser.id === post.accountId;
  };
}

/** Build the same 15-minute author/admin edit predicate exposed by the server. */
export function canEditFor(currentUser, now = Date.now()) {
  return function (post) {
    if (!currentUser || !post?.createdOn) return false;
    if (currentUser.role !== 'ADMIN' && currentUser.id !== post.accountId) return false;
    const created = new Date(post.createdOn).getTime();
    const expires = post.expiresOn ? new Date(post.expiresOn).getTime() : Number.POSITIVE_INFINITY;
    return Number.isFinite(created)
      && now < created + 15 * 60 * 1000
      && now < expires;
  };
}

/**
 * Build an onLike action that posts to the API and returns updated like state.
 * @param {(url:string, options?:object)=>Promise<object>} fetchJson
 * @param {()=>object} authHeaders
 * @returns {(postId:string)=>Promise<{likesCount:number, liked:boolean, expiresOn?:string}>}
 */
export function onLikeAction(fetchJson, authHeaders) {
  return (postId) => fetchJson(API.posts.like(postId), { method: 'POST', headers: authHeaders() });
}

/**
 * Build an onDelete action that deletes the post by id.
 * @param {(url:string, options?:object)=>Promise<object>} fetchJson
 * @param {()=>object} authHeaders
 * @returns {(postId:string)=>Promise<void>}
 */
export function onDeleteAction(fetchJson, authHeaders) {
  return (postId) => fetchJson(API.posts.byId(postId), { method: 'DELETE', headers: authHeaders() });
}

/** Build a bounded post-text edit action. */
export function onEditAction(fetchJson, authHeaders) {
  return (postId, text) => fetchJson(API.posts.edit(postId), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ text })
  });
}

/**
 * Build an onReply action that creates a reply under a given post id.
 * @param {(url:string, options?:object)=>Promise<object>} fetchJson
 * @param {()=>object} authHeaders
 * @returns {(postId:string, text:string)=>Promise<object>}
 */
export function onReplyAction(fetchJson, authHeaders) {
  return (postId, text) => fetchJson(API.posts.create, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ text, parentId: postId })
  });
}

/** Build an action that hides a full post thread for the current user. */
export function onHideThreadAction(fetchJson, authHeaders) {
  return (postId) => fetchJson(API.posts.hideThread(postId), { method: 'PUT', headers: authHeaders() });
}

/**
 * Build a thread fetcher for a given post id.
 * Returns the flat thread list (root first, then replies).
 * @param {(url:string)=>Promise<object>} fetchJson
 * @returns {(postId:string)=>Promise<Array<object>>}
 */
export function createThreadFetcher(fetchJson, authHeaders) {
  return async (postId) => fetchJson(API.posts.thread(postId), { headers: authHeaders() });
}

/**
 * Build a standard renderer context for feed items.
 * Centralizes wiring for like/delete/reply and context fetchers.
 *
 * @param {object} deps
 *  - fetchJson, authHeaders, sanitize, formatWhen, isLoggedIn
 *  - canDelete: (post)=>boolean
 *  - currentUserName: string|null
 * @returns {object} ctx for createFeedItem
 */
export function makeRendererContext({
  fetchJson,
  authHeaders,
  sanitize,
  formatWhen,
  isLoggedIn,
  canDelete,
  canEdit,
  currentUserName,
  suppressParentContext = false,
  onExpire = null
}) {
  const fetchPost = createRootFetcher(fetchJson);
  return {
    sanitize,
    formatWhen,
    isLoggedIn,
    canDelete,
    canEdit: canEdit || (() => false),
    fetchRoot: fetchPost,
    fetchParent: fetchPost,
    fetchThread: createThreadFetcher(fetchJson, authHeaders),
    onLike: onLikeAction(fetchJson, authHeaders),
    onDelete: onDeleteAction(fetchJson, authHeaders),
    onEdit: onEditAction(fetchJson, authHeaders),
    onReply: onReplyAction(fetchJson, authHeaders),
    onHideThread: onHideThreadAction(fetchJson, authHeaders),
    currentUserName: currentUserName || null,
    suppressParentContext,
    onExpire
  };
}
