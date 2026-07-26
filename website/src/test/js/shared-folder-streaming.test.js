import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import {
  isSharedFolderApiRequest,
  prepareSharedFolderStreamingAuth,
  sharedFolderDownloadRequestUrl,
  sharedFolderStreamingDenial,
} from '../../main/resources/static/js/lib/shared-folder-streaming.js';

const origin = 'https://example.test';
const sharedContent = `${origin}/api/shared-folder/2026-07-17/content?path=music%2Ftrack.flac`;

test('native shared-folder interception is restricted to the exact same-origin API prefix', () => {
  assert.equal(isSharedFolderApiRequest(sharedContent, origin), true);
  assert.equal(isSharedFolderApiRequest(`${origin}/api/shared-folder/2026-07-17x/content`, origin), false);
  assert.equal(isSharedFolderApiRequest(`${origin}/api/accounts/me`, origin), false);
  assert.equal(isSharedFolderApiRequest(`https://outside.test/api/shared-folder/2026-07-17/content`, origin), false);
});

test('download correlation is an exact non-secret same-origin URL', () => {
  const downloadId = '11111111-1111-4111-8111-111111111111';
  const result = sharedFolderDownloadRequestUrl(sharedContent, downloadId, origin);

  assert.equal(new URL(result).searchParams.get('downloadId'), downloadId);
  assert.equal(new URL(result).searchParams.has('token'), false);
  assert.throws(
    () => sharedFolderDownloadRequestUrl('https://outside.test/file', downloadId, origin),
    /invalid/i,
  );
});

test('native denial states remain actionable', () => {
  assert.deepEqual(sharedFolderStreamingDenial(401), {
    message: 'Your session expired. Redirecting to login.',
    redirectToLogin: true,
  });
  assert.equal(sharedFolderStreamingDenial(403).redirectToLogin, false);
});

test('worker registration requires no readable credential or message staging', async () => {
  const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  const registrations = [];
  const controller = {
    scriptURL: `${origin}/shared-folder-auth-sw.js?v=20260725`,
  };
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: {
      serviceWorker: {
        controller,
        ready: Promise.resolve(),
        register: async (path, options) => { registrations.push({ path, options }); },
      },
    },
  });
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location: { origin } },
  });

  try {
    assert.equal(await prepareSharedFolderStreamingAuth(), controller);
  } finally {
    if (originalNavigator) Object.defineProperty(globalThis, 'navigator', originalNavigator);
    else delete globalThis.navigator;
    if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
    else delete globalThis.window;
  }

  assert.deepEqual(registrations, [{
    path: '/shared-folder-auth-sw.js?v=20260725',
    options: { scope: '/', type: 'module' },
  }]);
});

test('shared-folder streaming code contains no bearer-token transport', () => {
  const paths = [
    'website/src/main/resources/static/shared-folder-auth-sw.js',
    'website/src/main/resources/static/js/lib/shared-folder-streaming.js',
    'website/src/main/resources/static/js/lib/shared-folder-worker-runtime.js',
    'website/src/main/resources/static/js/shared-folder.js',
    'website/src/main/resources/static/js/components/site-media-player.js',
  ];
  const sources = paths.map(path => fs.readFileSync(path, 'utf8')).join('\n');

  assert.doesNotMatch(sources, /cbellLoginToken|Authorization:\s*`Bearer|shared-folder-auth-token/);
  assert.match(sources, /prepareSharedFolderDownloadAuth\(requestUrl\)/);
  assert.match(sources, /prepareSharedFolderMediaAuth\(url\)/);
  assert.match(sources, /shared-folder-auth-denied/);
});
