import {
  SHARED_FOLDER_API_PREFIX,
  attachSharedFolderAuthorization,
} from './shared-folder-streaming.js';

const TOKEN_RECOVERY_TIMEOUT_MS = 5000;
const DOWNLOAD_AUTHORIZATION_TTL_MS = 10000;
const MEDIA_AUTHORIZATION_TTL_MS = 4 * 60 * 60 * 1000;
const MAX_PENDING_DOWNLOADS = 64;
const MAX_MEDIA_AUTHORIZATIONS = 32;
const DOWNLOAD_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MEDIA_STREAM_PATH = /^\/api\/shared-folder\/2026-07-17\/media\/jobs\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\/stream$/i;

/**
 * Authorize one exact shared-folder request in the worker. A worker restart loses the
 * in-memory token map, so recovery asks only the initiating controlled client over a
 * transient message port. It never retries the request without a bearer token.
 */
export async function respondToSharedFolderFetch({
  request,
  clientId,
  clientTokens,
  downloadTokens = new Map(),
  mediaAuthorizations = new Map(),
  clients,
  origin,
  fetchFn = fetch,
  createMessageChannel = () => new MessageChannel(),
  setTimeoutFn = setTimeout,
  clearTimeoutFn = clearTimeout,
  timeoutMs = TOKEN_RECOVERY_TIMEOUT_MS,
  nowFn = Date.now,
}) {
  const nowMs = nowFn();
  let token = consumeSharedFolderDownloadAuthorization(
    request.url,
    downloadTokens,
    nowMs,
  );
  if (!token) token = requestBearerToken(request);
  if (!token && !clientId) {
    token = sharedFolderMediaAuthorization(
      request.url,
      mediaAuthorizations,
      nowMs,
    );
  }
  if (!token) token = clientTokens.get(clientId);
  if (!token) {
    token = await recoverClientToken({
      clientId,
      clientTokens,
      clients,
      createMessageChannel,
      setTimeoutFn,
      clearTimeoutFn,
      timeoutMs,
    });
  }

  if (!token) {
    await notifySharedFolderDenial(clients, clientId, 401);
    return new Response(null, {
      status: 401,
      headers: { 'Cache-Control': 'private, no-store' },
    });
  }

  const response = await fetchFn(
    attachSharedFolderAuthorization(request, token, origin),
    { cache: 'no-store' },
  );
  if (response.status === 401 || response.status === 403) {
    if (response.status === 401) clientTokens.delete(clientId);
    mediaAuthorizations.delete(request.url);
    await notifySharedFolderDenial(clients, clientId, response.status);
  }
  return response;
}

function requestBearerToken(request) {
  const authorization = request.headers.get('Authorization') || '';
  if (!authorization.startsWith('Bearer ')) return '';
  return authorization.substring('Bearer '.length).trim();
}

/** Stage one exact short-lived download token without persisting it or putting it in the URL. */
export function stageSharedFolderDownloadAuthorization({
  requestUrl,
  token,
  downloadTokens,
  origin,
  nowMs = Date.now(),
}) {
  const normalizedToken = typeof token === 'string' ? token.trim() : '';
  let url;
  try {
    url = new URL(requestUrl, origin);
  } catch (_) {
    return false;
  }
  if (!normalizedToken || url.origin !== origin
      || url.pathname !== `${SHARED_FOLDER_API_PREFIX}content`
      || !DOWNLOAD_ID.test(url.searchParams.get('downloadId') || '')) {
    return false;
  }
  for (const [key, authorization] of downloadTokens) {
    if (authorization.expiresAt <= nowMs) downloadTokens.delete(key);
  }
  if (downloadTokens.size >= MAX_PENDING_DOWNLOADS) return false;
  downloadTokens.set(url.href, {
    token: normalizedToken,
    expiresAt: nowMs + DOWNLOAD_AUTHORIZATION_TTL_MS,
  });
  return true;
}

/**
 * Stage one exact native media URL for repeated clientless range requests. The
 * authorization remains volatile, bounded, and owned by the page that staged it.
 */
export function stageSharedFolderMediaAuthorization({
  requestUrl,
  token,
  clientId,
  mediaAuthorizations,
  origin,
  nowMs = Date.now(),
}) {
  const normalizedToken = typeof token === 'string' ? token.trim() : '';
  const normalizedClientId = typeof clientId === 'string' ? clientId.trim() : '';
  let url;
  try {
    url = new URL(requestUrl, origin);
  } catch (_) {
    return false;
  }
  if (!normalizedToken || !normalizedClientId || url.origin !== origin
      || !isNativeMediaUrl(url)) {
    return false;
  }
  removeExpiredMediaAuthorizations(mediaAuthorizations, nowMs);
  if (!mediaAuthorizations.has(url.href)
      && mediaAuthorizations.size >= MAX_MEDIA_AUTHORIZATIONS) {
    return false;
  }
  mediaAuthorizations.set(url.href, {
    token: normalizedToken,
    clientId: normalizedClientId,
    expiresAt: nowMs + MEDIA_AUTHORIZATION_TTL_MS,
  });
  return true;
}

/** Remove volatile media authorizations owned by one controlled page. */
export function clearSharedFolderMediaAuthorizations(mediaAuthorizations, clientId) {
  for (const [url, authorization] of mediaAuthorizations) {
    if (authorization.clientId === clientId) mediaAuthorizations.delete(url);
  }
}

function consumeSharedFolderDownloadAuthorization(requestUrl, downloadTokens, nowMs) {
  const authorization = downloadTokens.get(requestUrl);
  if (!authorization) return '';
  downloadTokens.delete(requestUrl);
  return authorization.expiresAt > nowMs ? authorization.token : '';
}

function sharedFolderMediaAuthorization(requestUrl, mediaAuthorizations, nowMs) {
  const authorization = mediaAuthorizations.get(requestUrl);
  if (!authorization) return '';
  if (authorization.expiresAt <= nowMs) {
    mediaAuthorizations.delete(requestUrl);
    return '';
  }
  return authorization.token;
}

function removeExpiredMediaAuthorizations(mediaAuthorizations, nowMs) {
  for (const [url, authorization] of mediaAuthorizations) {
    if (authorization.expiresAt <= nowMs) mediaAuthorizations.delete(url);
  }
}

function isNativeMediaUrl(url) {
  const previewPath = url.searchParams.get('path') || '';
  return (url.pathname === `${SHARED_FOLDER_API_PREFIX}preview` && previewPath !== '')
    || MEDIA_STREAM_PATH.test(url.pathname);
}

async function recoverClientToken({
  clientId,
  clientTokens,
  clients,
  createMessageChannel,
  setTimeoutFn,
  clearTimeoutFn,
  timeoutMs,
}) {
  if (!clientId) return '';
  let client;
  try {
    client = await clients.get(clientId);
  } catch (_) {
    return '';
  }
  if (!client) return '';

  return new Promise(resolve => {
    const channel = createMessageChannel();
    let settled = false;
    const finish = token => {
      if (settled) return;
      settled = true;
      clearTimeoutFn(timeout);
      const recoveredToken = typeof token === 'string' ? token.trim() : '';
      if (recoveredToken) clientTokens.set(clientId, recoveredToken);
      resolve(recoveredToken);
    };
    const timeout = setTimeoutFn(() => finish(''), timeoutMs);
    channel.port1.onmessage = event => {
      const reply = event.data;
      finish(reply?.type === 'shared-folder-auth-recovery' ? reply.token : '');
    };
    channel.port1.start?.();
    try {
      client.postMessage({ type: 'shared-folder-auth-request-token' }, [channel.port2]);
    } catch (_) {
      finish('');
    }
  });
}

async function notifySharedFolderDenial(clients, clientId, status) {
  if (!clientId) return;
  try {
    const client = await clients.get(clientId);
    client?.postMessage({ type: 'shared-folder-auth-denied', status });
  } catch (_) {
    // A lost client cannot receive a denial, but the worker still returns its controlled response.
  }
}
