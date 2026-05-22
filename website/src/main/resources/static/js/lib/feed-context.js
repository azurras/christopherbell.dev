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
    fetchRoot: fetchPost,
    fetchParent: fetchPost,
    fetchThread: createThreadFetcher(fetchJson, authHeaders),
    onLike: onLikeAction(fetchJson, authHeaders),
    onDelete: onDeleteAction(fetchJson, authHeaders),
    onReply: onReplyAction(fetchJson, authHeaders),
    currentUserName: currentUserName || null,
    suppressParentContext,
    onExpire
  };
}
