import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import {
  attachSharedFolderAuthorization,
  isSharedFolderApiRequest,
  prepareSharedFolderStreamingAuth,
  sharedFolderStreamingDenial,
} from '../../main/resources/static/js/lib/shared-folder-streaming.js';

const origin = 'https://example.test';
const sharedContent = `${origin}/api/shared-folder/2026-07-17/content?path=music%2Ftrack.flac`;

test('native shared-folder requests attach authorization only to the exact versioned API prefix', () => {
  assert.equal(isSharedFolderApiRequest(sharedContent, origin), true);
  assert.equal(isSharedFolderApiRequest(`${origin}/api/shared-folder/2026-07-17x/content`, origin), false);
  assert.equal(isSharedFolderApiRequest(`${origin}/api/accounts/me`, origin), false);
  assert.equal(isSharedFolderApiRequest(`https://outside.test/api/shared-folder/2026-07-17/content`, origin), false);
});

test('native shared-folder authorization preserves Range and never puts a bearer token in a URL', () => {
  const original = new Request(sharedContent, { headers: { Range: 'bytes=4-9' } });
  const authorized = attachSharedFolderAuthorization(original, 'jwt-value', origin);

  assert.equal(authorized.headers.get('Authorization'), 'Bearer jwt-value');
  assert.equal(authorized.headers.get('Range'), 'bytes=4-9');
  assert.equal(authorized.url, sharedContent);
  assert.equal(new URL(authorized.url).searchParams.has('token'), false);
  assert.equal(new URL(authorized.url).searchParams.has('access_token'), false);
});

test('worker bypasses HTTP caches and clears the per-client token only after 401', () => {
  const worker = fs.readFileSync('website/src/main/resources/static/shared-folder-auth-sw.js', 'utf8');

  assert.match(worker, /fetch\(attachSharedFolderAuthorization\([\s\S]*?\{ cache: 'no-store' \}\)/);
  assert.match(worker, /response\.status === 401[\s\S]*?clientTokens\.delete\(event\.clientId\)/);
  assert.match(worker, /shared-folder-auth-clear[\s\S]*?clientTokens\.delete\(clientId\)/);
});

test('download and binary-preview denial states are actionable without a rejected Blob request', () => {
  assert.deepEqual(sharedFolderStreamingDenial(401), {
    message: 'Your session expired. Redirecting to login.',
    redirectToLogin: true,
  });
  assert.deepEqual(sharedFolderStreamingDenial(403), {
    message: 'Shared-folder access was denied. Your access may have been revoked.',
    redirectToLogin: false,
  });
});

test('shared-folder page starts native anchor and media requests without Blob buffering', () => {
  const page = fs.readFileSync('website/src/main/resources/static/js/shared-folder.js', 'utf8');
  const worker = fs.readFileSync('website/src/main/resources/static/shared-folder-auth-sw.js', 'utf8');

  assert.doesNotMatch(page, /\.blob\(/);
  assert.doesNotMatch(page, /URL\.createObjectURL/);
  assert.match(page, /link\.href = API\.sharedFolder\.content\(entry\.path\)/);
  assert.match(page, /element\.src = API\.sharedFolder\.preview\(entry\.path\)/);
  assert.match(page, /prepareSharedFolderStreamingAuth/);
  assert.match(page, /function handleSharedFolderAccessLoss\(statusCode\)/);
  assert.match(page, /handleSharedFolderAccessLoss\(error\.status\)/);
  assert.match(page, /handleSharedFolderAccessLoss\(event\.data\.status\)/);
  assert.match(page, /redirectOnUnauthorized: false/);
  assert.match(worker, /attachSharedFolderAuthorization/);
  assert.match(worker, /shared-folder-auth-denied/);
});

test('worker registration waits for the root-scoped controller and its token acknowledgement', async () => {
  const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  const originalMessageChannel = Object.getOwnPropertyDescriptor(globalThis, 'MessageChannel');
  const registrations = [];
  const messages = [];
  const controller = {
    scriptURL: `${origin}/shared-folder-auth-sw.js`,
    postMessage(message, ports) {
      messages.push(message);
      ports[0].deliver({ type: 'shared-folder-auth-ready' });
    },
  };

  class BrowserMessageChannel {
    constructor() {
      this.port1 = { onmessage: null };
      this.port2 = {
        deliver: data => this.port1.onmessage?.({ data }),
      };
    }
  }

  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: {
      serviceWorker: {
        controller,
        ready: Promise.resolve(),
        register: async (path, options) => registrations.push({ path, options }),
        addEventListener() {},
        removeEventListener() {},
      },
    },
  });
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { setTimeout, clearTimeout },
  });
  Object.defineProperty(globalThis, 'MessageChannel', {
    configurable: true,
    value: BrowserMessageChannel,
  });

  try {
    await prepareSharedFolderStreamingAuth('jwt-value');
  } finally {
    if (originalNavigator) Object.defineProperty(globalThis, 'navigator', originalNavigator);
    else delete globalThis.navigator;
    if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
    else delete globalThis.window;
    if (originalMessageChannel) Object.defineProperty(globalThis, 'MessageChannel', originalMessageChannel);
    else delete globalThis.MessageChannel;
  }

  assert.deepEqual(registrations, [{
    path: '/shared-folder-auth-sw.js',
    options: { scope: '/', type: 'module' },
  }]);
  assert.deepEqual(messages, [{ type: 'shared-folder-auth-token', token: 'jwt-value' }]);
});
