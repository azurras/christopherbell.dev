import { isSharedFolderApiRequest } from './js/lib/shared-folder-streaming.js';
import { respondToSharedFolderFetch } from './js/lib/shared-folder-worker-runtime.js';

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
  event.respondWith(respondToSharedFolderFetch({
    request: event.request,
    clientId: event.clientId,
    clientTokens,
    clients: self.clients,
    origin: self.location.origin,
  }));
});
