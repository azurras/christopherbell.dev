import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { respondToSharedFolderFetch } from '../../main/resources/static/js/lib/shared-folder-worker-runtime.js';

const origin = 'https://example.test';
const apiUrl = `${origin}/api/shared-folder/2026-07-17/content?path=music%2Ftrack.flac`;

test('cookie-authenticated native requests preserve credentials and Range headers', async () => {
  const requests = [];
  const original = new Request(apiUrl, {
    credentials: 'same-origin',
    headers: { Range: 'bytes=4-9' },
  });

  const response = await respondToSharedFolderFetch({
    request: original,
    clientId: 'client-1',
    clients: { get: async () => null },
    fetchFn: async (request, options) => {
      requests.push({ request, options });
      return new Response('stream', { status: 200 });
    },
  });

  assert.equal(response.status, 200);
  assert.equal(requests[0].request, original);
  assert.equal(requests[0].request.credentials, 'same-origin');
  assert.equal(requests[0].request.headers.get('Range'), 'bytes=4-9');
  assert.equal(requests[0].request.headers.get('Authorization'), null);
  assert.deepEqual(requests[0].options, { cache: 'no-store' });
});

test('authentication and authorization denials are reported to the initiating page', async () => {
  for (const status of [401, 403]) {
    const messages = [];
    const response = await respondToSharedFolderFetch({
      request: new Request(apiUrl),
      clientId: 'client-1',
      clients: {
        get: async id => ({ postMessage: message => messages.push({ id, message }) }),
      },
      fetchFn: async () => new Response(null, { status }),
    });

    assert.equal(response.status, status);
    assert.deepEqual(messages, [{
      id: 'client-1',
      message: { type: 'shared-folder-auth-denied', status },
    }]);
  }
});

test('a lost client does not reject the native response', async () => {
  const response = await respondToSharedFolderFetch({
    request: new Request(apiUrl),
    clientId: 'lost-client',
    clients: { get: async () => { throw new Error('client unavailable'); } },
    fetchFn: async () => new Response(null, { status: 401 }),
  });

  assert.equal(response.status, 401);
});

test('the worker runtime never handles bearer tokens or persistent browser storage', () => {
  const runtime = fs.readFileSync(
    'website/src/main/resources/static/js/lib/shared-folder-worker-runtime.js', 'utf8');

  assert.doesNotMatch(runtime, /Bearer|Authorization|token/i);
  assert.doesNotMatch(runtime, /localStorage|sessionStorage|indexedDB|caches\./);
});
