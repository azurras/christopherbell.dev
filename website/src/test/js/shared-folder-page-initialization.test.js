import assert from 'node:assert/strict';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import * as sharedFolderPage from '../../main/resources/static/js/shared-folder.js';
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

test('folder navigation replaces only folder chrome and pushes the canonical path', async () => {
  assert.equal(typeof sharedFolderPage.createSharedFolderNavigator, 'function');
  const events = [];
  const navigator = sharedFolderPage.createSharedFolderNavigator({
    load: async path => {
      events.push(['load', path]);
      return { path: 'music/live', entries: [{ name: 'set.flac' }] };
    },
    render: async response => events.push(['render', response.path]),
    pushPath: path => events.push(['push', path]),
    onError: error => events.push(['error', error.message]),
  });

  assert.equal(await navigator.open('music/live'), true);
  assert.deepEqual(events, [
    ['load', 'music/live'],
    ['render', 'music/live'],
    ['push', 'music/live'],
  ]);
});

test('folder navigation ignores stale responses and restores history without another push', async () => {
  assert.equal(typeof sharedFolderPage.createSharedFolderNavigator, 'function');
  const pending = new Map();
  const rendered = [];
  const pushed = [];
  const navigator = sharedFolderPage.createSharedFolderNavigator({
    load: path => new Promise(resolve => pending.set(path, resolve)),
    render: async response => rendered.push(response.path),
    pushPath: path => pushed.push(path),
    onError: error => assert.fail(error),
  });

  const slower = navigator.open('music');
  const faster = navigator.restore('video');
  pending.get('video')({ path: 'video', entries: [] });
  assert.equal(await faster, true);
  pending.get('music')({ path: 'music', entries: [] });
  assert.equal(await slower, false);

  assert.deepEqual(rendered, ['video']);
  assert.deepEqual(pushed, []);
});
