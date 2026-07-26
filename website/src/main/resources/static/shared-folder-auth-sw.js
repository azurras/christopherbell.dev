import { isSharedFolderApiRequest } from './js/lib/shared-folder-streaming.js';
import { respondToSharedFolderFetch } from './js/lib/shared-folder-worker-runtime.js';

self.addEventListener('install', event => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', event => {
  if (!isSharedFolderApiRequest(event.request, self.location.origin)) return;
  event.respondWith(respondToSharedFolderFetch({
    request: event.request,
    clientId: event.clientId,
    clients: self.clients,
  }));
});
