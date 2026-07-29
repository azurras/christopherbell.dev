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

class RecordingStorage {
  #values = new Map();
  operations = [];

  getItem(key) {
    this.operations.push(['get', key]);
    return this.#values.get(key) ?? null;
  }

  setItem(key, value) {
    this.operations.push(['set', key]);
    this.#values.set(key, String(value));
  }

  removeItem(key) {
    this.operations.push(['remove', key]);
    this.#values.delete(key);
  }

  value(key) {
    return this.#values.get(key) ?? null;
  }

  resetOperations() {
    this.operations = [];
  }
}

const upload = {
  id: 'upload-1', parentPath: 'docs', name: 'report.pdf', expectedBytes: 42,
};

test('account-scoped resume storage isolates two accounts, removes legacy state unread, and resumes same-account work', () => {
  assert.equal(typeof sharedFolderPage.createUploadResumeStore, 'function');
  const storage = new RecordingStorage();
  const legacyKey = 'shared-folder-upload-resume-v1';
  const aliceKey = 'shared-folder-upload-resume-v2:account-alice';
  const bobKey = 'shared-folder-upload-resume-v2:account-bob';
  storage.setItem(legacyKey, JSON.stringify({ ...upload, id: 'legacy-upload' }));
  storage.resetOperations();

  const alice = sharedFolderPage.createUploadResumeStore({
    accountId: 'account-alice', storage,
  });
  assert.equal(storage.value(legacyKey), null);
  assert.equal(storage.operations.some(([operation, key]) =>
    operation === 'get' && key === legacyKey), false);

  alice.store(upload, true);
  const bob = sharedFolderPage.createUploadResumeStore({
    accountId: 'account-bob', storage,
  });
  assert.deepEqual(bob.load(), null);
  assert.deepEqual(alice.load(), { ...upload, replace: true });
  bob.store({ ...upload, id: 'upload-2' });

  assert.equal(storage.value(aliceKey), JSON.stringify({ ...upload, replace: true }));
  assert.equal(storage.value(bobKey), JSON.stringify({ ...upload, id: 'upload-2', replace: false }));
  assert.deepEqual(alice.load(), { ...upload, replace: true });
});

test('resume storage does not access browser state without an authenticated account identity', () => {
  assert.equal(typeof sharedFolderPage.createUploadResumeStore, 'function');
  const storage = new RecordingStorage();
  const anonymous = sharedFolderPage.createUploadResumeStore({ accountId: '', storage });

  assert.equal(anonymous.load(), null);
  anonymous.store(upload);
  anonymous.clear();

  assert.deepEqual(storage.operations, []);
});

function replaceGlobal(name, value) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, name);
  Object.defineProperty(globalThis, name, { configurable: true, value });
  return () => {
    if (previous) Object.defineProperty(globalThis, name, previous);
    else delete globalThis[name];
  };
}

function uploadPanelDocument() {
  const node = () => ({
    addEventListener() {},
    classList: { add() {}, remove() {} },
  });
  const nodes = new Map([
    ['shared-upload-panel', node()],
    ['shared-upload-form', node()],
    ['shared-upload-file', node()],
    ['shared-upload-cancel', node()],
    ['shared-upload-pause', node()],
    ['shared-upload-progress', node()],
    ['shared-upload-detail', node()],
    ['shared-folder-status', node()],
  ]);
  return {
    cookie: '',
    getElementById: id => nodes.get(id) ?? null,
    querySelector: () => ({ setAttribute() {} }),
  };
}

test('terminal upload cleanup clears only the current account resume record', async () => {
  assert.equal(typeof sharedFolderPage.createUploadResumeStore, 'function');
  assert.equal(typeof sharedFolderPage.configureUploadPanel, 'function');
  const storage = new RecordingStorage();
  const alice = sharedFolderPage.createUploadResumeStore({
    accountId: 'account-alice', storage,
  });
  const bob = sharedFolderPage.createUploadResumeStore({
    accountId: 'account-bob', storage,
  });
  alice.store(upload);
  bob.store({ ...upload, id: 'upload-2' });
  const restoreDocument = replaceGlobal('document', uploadPanelDocument());
  const restoreFetch = replaceGlobal('fetch', async () => ({
    ok: true,
    status: 200,
    json: async () => ({ payload: { ...upload, state: 'COMPLETED' } }),
  }));

  try {
    await sharedFolderPage.configureUploadPanel({
      role: 'USER', permissions: ['SHARED_FOLDER_WRITE'],
    }, () => '', alice);
  } finally {
    restoreFetch();
    restoreDocument();
  }

  assert.equal(alice.load(), null);
  assert.deepEqual(bob.load(), { ...upload, id: 'upload-2', replace: false });
});

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
