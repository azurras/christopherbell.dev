import { attachSharedFolderAuthorization } from './shared-folder-streaming.js';

const TOKEN_RECOVERY_TIMEOUT_MS = 5000;

/**
 * Authorize one exact shared-folder request in the worker. A worker restart loses the
 * in-memory token map, so recovery asks only the initiating controlled client over a
 * transient message port. It never retries the request without a bearer token.
 */
export async function respondToSharedFolderFetch({
  request,
  clientId,
  clientTokens,
  clients,
  origin,
  fetchFn = fetch,
  createMessageChannel = () => new MessageChannel(),
  setTimeoutFn = setTimeout,
  clearTimeoutFn = clearTimeout,
  timeoutMs = TOKEN_RECOVERY_TIMEOUT_MS,
}) {
  let token = clientTokens.get(clientId);
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
    await notifySharedFolderDenial(clients, clientId, response.status);
  }
  return response;
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
