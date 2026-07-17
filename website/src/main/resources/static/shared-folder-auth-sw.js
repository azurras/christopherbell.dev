import {
  attachSharedFolderAuthorization,
  isSharedFolderApiRequest,
} from './js/lib/shared-folder-streaming.js';

const clientTokens = new Map();

self.addEventListener('install', event => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('message', event => {
  if (event.origin !== self.location.origin || !event.source?.id) return;
  const clientId = event.source.id;
  if (event.data?.type === 'shared-folder-auth-clear') {
    clientTokens.delete(clientId);
    return;
  }
  if (event.data?.type !== 'shared-folder-auth-token' || typeof event.data.token !== 'string'
      || !event.data.token) {
    return;
  }
  clientTokens.set(clientId, event.data.token);
  event.ports[0]?.postMessage({ type: 'shared-folder-auth-ready' });
});

self.addEventListener('fetch', event => {
  if (!isSharedFolderApiRequest(event.request, self.location.origin)) return;
  const token = clientTokens.get(event.clientId);
  if (!token) return;
  event.respondWith(fetch(attachSharedFolderAuthorization(event.request, token, self.location.origin),
      { cache: 'no-store' })
    .then(async response => {
      if (response.status === 401 || response.status === 403) {
        if (response.status === 401) clientTokens.delete(event.clientId);
        const client = event.clientId ? await self.clients.get(event.clientId) : null;
        client?.postMessage({ type: 'shared-folder-auth-denied', status: response.status });
      }
      return response;
    }));
});
