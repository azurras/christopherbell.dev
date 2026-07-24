import { isSharedFolderApiRequest } from './js/lib/shared-folder-streaming.js';
import {
  clearSharedFolderMediaAuthorizations,
  respondToSharedFolderFetch,
  stageSharedFolderDownloadAuthorization,
  stageSharedFolderMediaAuthorization,
} from './js/lib/shared-folder-worker-runtime.js';

const clientTokens = new Map();
const downloadTokens = new Map();
const mediaAuthorizations = new Map();

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
    clearSharedFolderMediaAuthorizations(mediaAuthorizations, clientId);
    return;
  }
  if (event.data?.type === 'shared-folder-download-token') {
    const accepted = stageSharedFolderDownloadAuthorization({
      requestUrl: event.data.requestUrl,
      token: event.data.token,
      downloadTokens,
      origin: self.location.origin,
    });
    event.ports[0]?.postMessage({
      type: accepted ? 'shared-folder-download-ready' : 'shared-folder-download-rejected',
    });
    return;
  }
  if (event.data?.type === 'shared-folder-media-token') {
    const accepted = stageSharedFolderMediaAuthorization({
      requestUrl: event.data.requestUrl,
      token: event.data.token,
      clientId,
      mediaAuthorizations,
      origin: self.location.origin,
    });
    event.ports[0]?.postMessage({
      type: accepted ? 'shared-folder-media-ready' : 'shared-folder-media-rejected',
    });
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
    downloadTokens,
    mediaAuthorizations,
    clients: self.clients,
    origin: self.location.origin,
  }));
});
