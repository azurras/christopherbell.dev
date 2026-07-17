import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import {
  attachSharedFolderAuthorization,
  isSharedFolderApiRequest,
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
  assert.match(worker, /attachSharedFolderAuthorization/);
  assert.match(worker, /shared-folder-auth-denied/);
});
