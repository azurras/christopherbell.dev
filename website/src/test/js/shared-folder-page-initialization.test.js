import assert from 'node:assert/strict';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import { initializeSharedFolderPage } from '../../main/resources/static/js/shared-folder.js';

function pageRoot() {
  return {
    classList: {
      removed: false,
      remove() { this.removed = true; },
    },
  };
}

test('page initialization unwraps the account DTO and renders entries for shared-folder READ', async () => {
  const root = pageRoot();
  const requests = [];
  const rendered = [];
  const account = { role: 'USER', permissions: ['SHARED_FOLDER_READ'] };
  const entries = {
    path: 'music',
    entries: [{ name: 'track.flac', path: 'music/track.flac', type: 'FILE' }],
  };

  await initializeSharedFolderPage({
    pageRoot: root,
    getAuthTokenFn: () => 'jwt-value',
    authHeadersFn: () => ({ Authorization: 'Bearer jwt-value' }),
    requestedPath: () => 'music',
    fetchJsonFn: async (url, options) => {
      requests.push({ url, options });
      return url === API.accounts.me ? account : entries;
    },
    renderBreadcrumbsFn: path => rendered.push(['breadcrumbs', path]),
    renderToolbarFn: path => rendered.push(['toolbar', path]),
    renderEntriesFn: response => rendered.push(['entries', response]),
    statusFn: message => rendered.push(['status', message]),
  });

  assert.deepEqual(requests.map(request => request.url), [
    API.accounts.me,
    API.sharedFolder.entries('music'),
  ]);
  assert.equal(requests[1].options.redirectOnUnauthorized, false);
  assert.equal(root.classList.removed, true);
  assert.deepEqual(rendered, [
    ['breadcrumbs', 'music'],
    ['toolbar', 'music'],
    ['entries', entries],
    ['status', '1 item'],
  ]);
});

test('page initialization stops before entries when the unwrapped account lacks shared-folder read', async () => {
  const root = pageRoot();
  const requests = [];
  const rendered = [];

  await initializeSharedFolderPage({
    pageRoot: root,
    getAuthTokenFn: () => 'jwt-value',
    authHeadersFn: () => ({ Authorization: 'Bearer jwt-value' }),
    fetchJsonFn: async url => {
      requests.push(url);
      return { role: 'USER', permissions: [] };
    },
    renderEntriesFn: () => rendered.push('entries'),
    statusFn: message => rendered.push(message),
  });

  assert.deepEqual(requests, [API.accounts.me]);
  assert.equal(root.classList.removed, true);
  assert.deepEqual(rendered, ['Your account does not have shared-folder read access.']);
});
