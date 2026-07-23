import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import {
  respondToSharedFolderFetch,
  stageSharedFolderDownloadAuthorization,
} from '../../main/resources/static/js/lib/shared-folder-worker-runtime.js';

const origin = 'https://example.test';
const apiUrl = `${origin}/api/shared-folder/2026-07-17/content?path=music%2Ftrack.flac`;

class WorkerMessageChannel {
  constructor() {
    this.port1 = { onmessage: null };
    this.port2 = {
      deliver: data => this.port1.onmessage?.({ data }),
    };
  }
}

test('lost worker token is rehydrated from the initiating controlled client before an authorized fetch', async () => {
  const tokens = new Map();
  const messages = [];
  const requests = [];
  const client = {
    postMessage(message, ports) {
      messages.push(message);
      if (message.type === 'shared-folder-auth-request-token') {
        ports[0].deliver({ type: 'shared-folder-auth-recovery', token: 'fresh-jwt' });
      }
    },
  };
  const response = await respondToSharedFolderFetch({
    request: new Request(apiUrl, { headers: { Range: 'bytes=4-9' } }),
    clientId: 'client-1',
    clientTokens: tokens,
    clients: { get: async id => id === 'client-1' ? client : null },
    origin,
    createMessageChannel: () => new WorkerMessageChannel(),
    fetchFn: async (request, options) => {
      requests.push({ request, options });
      return new Response('stream', { status: 200 });
    },
  });

  assert.equal(response.status, 200);
  assert.equal(tokens.get('client-1'), 'fresh-jwt');
  assert.deepEqual(messages, [{ type: 'shared-folder-auth-request-token' }]);
  assert.equal(requests[0].request.headers.get('Authorization'), 'Bearer fresh-jwt');
  assert.equal(requests[0].request.headers.get('Range'), 'bytes=4-9');
  assert.equal(requests[0].request.url, apiUrl);
  assert.equal(requests[0].options.cache, 'no-store');
});

test('an authenticated shared-folder API fetch keeps its bearer token across page navigation', async () => {
  const requests = [];
  const response = await respondToSharedFolderFetch({
    request: new Request(apiUrl, {
      headers: { Authorization: 'Bearer back-office-jwt' },
    }),
    clientId: 'back-office-client',
    clientTokens: new Map(),
    clients: {
      get: async () => assert.fail('an explicit bearer token must not require client recovery'),
    },
    origin,
    fetchFn: async request => {
      requests.push(request);
      return new Response('admin data', { status: 200 });
    },
  });

  assert.equal(response.status, 200);
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer back-office-jwt');
});

test('a token-recovery timeout returns a controlled denial instead of falling through unauthenticated', async () => {
  const tokens = new Map();
  const messages = [];
  let timeout;
  const client = {
    postMessage(message) { messages.push(message); },
  };
  const pending = respondToSharedFolderFetch({
    request: new Request(apiUrl),
    clientId: 'client-1',
    clientTokens: tokens,
    clients: { get: async () => client },
    origin,
    createMessageChannel: () => new WorkerMessageChannel(),
    setTimeoutFn: callback => {
      timeout = callback;
      return 1;
    },
    clearTimeoutFn() {},
    fetchFn: async () => assert.fail('unauthenticated shared-folder request must not be fetched'),
  });
  await Promise.resolve();
  timeout();
  const response = await pending;

  assert.equal(response.status, 401);
  assert.equal(tokens.has('client-1'), false);
  assert.deepEqual(messages, [
    { type: 'shared-folder-auth-request-token' },
    { type: 'shared-folder-auth-denied', status: 401 },
  ]);
});

test('a client lookup failure returns a controlled denial instead of rejecting the worker fetch', async () => {
  const response = await respondToSharedFolderFetch({
    request: new Request(apiUrl),
    clientId: 'client-1',
    clientTokens: new Map(),
    clients: { get: async () => { throw new Error('client unavailable'); } },
    origin,
    fetchFn: async () => assert.fail('unauthenticated shared-folder request must not be fetched'),
  });

  assert.equal(response.status, 401);
  assert.equal(response.headers.get('Cache-Control'), 'private, no-store');
});

test('one exact download without a client id consumes one bounded in-memory authorization', async () => {
  const downloadUrl = `${apiUrl}&downloadId=11111111-1111-4111-8111-111111111111`;
  const downloads = new Map();
  const requests = [];
  assert.equal(stageSharedFolderDownloadAuthorization({
    requestUrl: downloadUrl,
    token: 'download-jwt',
    downloadTokens: downloads,
    origin,
    nowMs: 1000,
  }), true);

  const response = await respondToSharedFolderFetch({
    request: new Request(downloadUrl),
    clientId: '',
    clientTokens: new Map(),
    downloadTokens: downloads,
    clients: { get: async () => null },
    origin,
    nowFn: () => 1001,
    fetchFn: async request => {
      requests.push(request);
      return new Response('download', { status: 200 });
    },
  });

  assert.equal(response.status, 200);
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer download-jwt');
  assert.equal(requests[0].url, downloadUrl);
  assert.equal(downloads.size, 0);
});

test('worker recovery keeps tokens out of URLs and persistent browser storage', () => {
  const runtime = fs.readFileSync(
    'website/src/main/resources/static/js/lib/shared-folder-worker-runtime.js', 'utf8');

  assert.doesNotMatch(runtime, /localStorage|sessionStorage|indexedDB|caches\./);
  assert.doesNotMatch(runtime, /searchParams\.set\([^)]*token|access_token/);
});
