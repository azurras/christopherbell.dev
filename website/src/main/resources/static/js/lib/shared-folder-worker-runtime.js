/**
 * Forward one native shared-folder request without cloning it. The original
 * request retains browser-managed cookies, credentials mode, and Range headers.
 */
export async function respondToSharedFolderFetch({
  request,
  clientId,
  clients,
  fetchFn = fetch,
}) {
  const response = await fetchFn(request, { cache: 'no-store' });
  if (response.status === 401 || response.status === 403) {
    await notifySharedFolderDenial(clients, clientId, response.status);
  }
  return response;
}

async function notifySharedFolderDenial(clients, clientId, status) {
  if (!clientId || !clients?.get) return;
  try {
    const client = await clients.get(clientId);
    client?.postMessage({ type: 'shared-folder-auth-denied', status });
  } catch (_) {
    // A lost client cannot receive a denial; the response still reaches the browser.
  }
}
