import { API } from './lib/api.js';
import {
  authHeaders,
  clearAuthState,
  currentRedirectTarget,
  fetchJson,
  getAuthToken,
  loginRedirectUrl,
} from './lib/util.js';
import {
  accountHasSharedFolderRead,
  accountHasSharedFolderWrite,
  breadcrumbItems,
  formatSharedFolderModifiedAt,
  formatSharedFolderSize,
  internalSharedFolderUrl,
  isSharedFolderAccessDenied,
  moveMutationPayload,
  renderPreviewText,
  revealSharedFolderPreview,
  sharedFolderEntryKind,
  sharedFolderEntryParentPath,
  renderSharedFolderEntryParentPath,
  sharedFolderSearchResultDescription,
  uploadProgressPercent,
  uploadIsTerminal,
  createUploadOperationGate,
  runUploadWorkflow,
  cancelUploadWorkflow,
  sortSharedFolderEntries,
  validateSharedFolderSearchResponse,
} from './lib/shared-folder.js';
import {
  prepareSharedFolderDownloadAuth,
  prepareSharedFolderStreamingAuth,
  sharedFolderDownloadRequestUrl,
  sharedFolderStreamingDenial,
} from './lib/shared-folder-streaming.js';
import {
  playSharedFolderMedia,
  playSharedFolderRadio as joinSharedFolderRadio,
  stopSiteMediaPlayback,
} from './lib/site-media-loader.js';

const root = typeof document === 'undefined' ? null : document.getElementById('shared-folder-app');
let currentPreviewLostAccess = false;
const LEGACY_UPLOAD_RESUME_KEY = 'shared-folder-upload-resume-v1';
const UPLOAD_RESUME_KEY_PREFIX = 'shared-folder-upload-resume-v2:';
let uploadResumeStore = createUploadResumeStore();
const uploadOperationGate = createUploadOperationGate();

function clear(node) {
  while (node?.firstChild) node.removeChild(node.firstChild);
}

function status(message) {
  const target = document.getElementById('shared-folder-status');
  if (target) target.textContent = message;
}

function renderBreadcrumbs(path, navigatePath) {
  const host = document.getElementById('shared-breadcrumbs');
  clear(host);
  breadcrumbItems(path).forEach((item, index) => {
    if (index > 0) host.append(document.createTextNode('/'));
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-sm btn-link';
    button.textContent = item.label;
    button.addEventListener('click', () => { void navigatePath(item.path); });
    host.append(button);
  });
}

function handleSharedFolderAccessLoss(statusCode) {
  const denial = sharedFolderStreamingDenial(statusCode);
  currentPreviewLostAccess = true;
  void stopSiteMediaPlayback();
  status(denial.message);
  if (denial.redirectToLogin && !window.location.pathname.startsWith('/login')) {
    clearAuthState();
    window.location.replace(loginRedirectUrl(currentRedirectTarget()));
  }
}

async function prepareNativeStreaming() {
  try {
    await prepareSharedFolderStreamingAuth();
    return true;
  } catch (error) {
    status(error?.message || 'Secure shared-folder streaming is unavailable.');
    return false;
  }
}

async function download(entry) {
  status(`Preparing ${entry.name}`);
  if (!getAuthToken()) {
    status('Authentication is required for shared-folder downloads.');
    return;
  }
  const requestUrl = sharedFolderDownloadRequestUrl(
    API.sharedFolder.content(entry.path),
    crypto.randomUUID(),
    window.location.origin,
  );
  try {
    await prepareSharedFolderDownloadAuth(requestUrl);
  } catch (error) {
    status(error?.message || 'Secure shared-folder download is unavailable.');
    return;
  }
  const link = document.createElement('a');
  link.href = requestUrl;
  link.download = entry.name;
  document.body.append(link);
  link.click();
  link.remove();
  status('Download started');
}

async function copyLink(entry) {
  const relative = internalSharedFolderUrl(entry.path);
  await navigator.clipboard.writeText(new URL(relative, window.location.origin).href);
  status('Internal link copied');
}

async function preview(entry) {
  const host = document.getElementById('shared-preview');
  clear(host);
  host.hidden = false;
  const heading = document.createElement('h2');
  heading.className = 'shared-folder-preview-title';
  heading.textContent = entry.name;
  host.append(heading);
  const metadata = document.createElement('p');
  metadata.className = 'shared-folder-preview-metadata';
  metadata.textContent = [
    sharedFolderEntryKind(entry).toUpperCase(),
    formatSharedFolderSize(entry.size),
    formatSharedFolderModifiedAt(entry.modifiedAt),
  ].join(' · ');
  host.append(metadata);

  if (['AUDIO', 'VIDEO'].includes(entry.previewKind)) {
    await playSharedFolderMedia(entry);
    const message = document.createElement('p');
    message.className = 'shared-folder-player-message';
    message.textContent = 'Playing in the media bar. You can keep browsing anywhere on the site.';
    host.append(message);
    status(`Playing ${entry.name}`);
    return;
  }

  revealSharedFolderPreview(host, window.matchMedia('(max-width: 767px)').matches);

  if (entry.previewKind === 'TEXT') {
    let response;
    try {
      response = await fetchJson(API.sharedFolder.preview(entry.path), {
        headers: authHeaders(),
        redirectOnUnauthorized: false,
      });
    } catch (error) {
      if (isSharedFolderAccessDenied(error)) {
        handleSharedFolderAccessLoss(error.status);
        return;
      }
      throw error;
    }
    const pre = document.createElement('pre');
    renderPreviewText(pre, response.text);
    host.append(pre);
    if (response.truncated) host.append(document.createTextNode('Preview truncated.'));
    return;
  }

  if (!['IMAGE', 'PDF'].includes(entry.previewKind)) {
    host.append(document.createTextNode('No inline preview is available. Download the file to view it.'));
    return;
  }

  if (!await prepareNativeStreaming()) return;
  currentPreviewLostAccess = false;
  const element = entry.previewKind === 'IMAGE'
    ? document.createElement('img') : document.createElement('iframe');
  element.title = `${entry.name} preview`;
  if (entry.previewKind === 'PDF') element.setAttribute('sandbox', '');
  element.addEventListener('error', () => {
    if (!currentPreviewLostAccess) status('The preview could not be loaded.');
  });
  element.src = API.sharedFolder.preview(entry.path);
  host.append(element);
}

async function mutationRequest(url, method, body) {
  return fetchJson(url, {
    method,
    headers: authHeaders(),
    redirectOnUnauthorized: false,
    cache: 'no-store',
    body: JSON.stringify(body),
  });
}

function mutationFailure(error) {
  if (isSharedFolderAccessDenied(error)) {
    handleSharedFolderAccessLoss(error.status);
    return;
  }
  status(error?.status === 409
    ? 'The item changed or the target conflicts. Refresh and try again.'
    : error?.message || 'The folder action failed.');
}

function renderToolbar(path, canWrite = false) {
  const host = document.getElementById('shared-toolbar');
  clear(host);
  if (canWrite) {
    const upload = document.createElement('button');
    upload.type = 'button';
    upload.className = 'btn btn-sm btn-warning';
    upload.textContent = 'Upload';
    upload.dataset.sharedUploadToggle = '';
    upload.setAttribute('aria-controls', 'shared-upload-panel');
    upload.setAttribute('aria-expanded', 'false');
    upload.addEventListener('click', () => {
      const panel = document.getElementById('shared-upload-panel');
      if (!panel) return;
      panel.hidden = !panel.hidden;
      upload.setAttribute('aria-expanded', String(!panel.hidden));
      if (!panel.hidden) document.getElementById('shared-upload-file')?.focus();
    });
    host.append(upload);

    const create = document.createElement('button');
    create.type = 'button';
    create.className = 'btn btn-sm btn-outline-light';
    create.textContent = 'New folder';
    create.addEventListener('click', () => {
      const name = window.prompt('New folder name');
      if (!name?.trim()) return;
      void mutationRequest(API.sharedFolder.folders, 'POST', { parentPath: path, name: name.trim() })
        .then(() => window.location.reload())
        .catch(mutationFailure);
    });
    host.append(create);
  }
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'btn btn-sm btn-outline-light';
  copy.textContent = 'Copy link';
  copy.addEventListener('click', async () => {
    await navigator.clipboard.writeText(new URL(internalSharedFolderUrl(path), window.location.origin).href);
    status('Internal link copied');
  });
  host.append(copy);
}

/** Scope browser resume metadata to one authenticated account without storing credentials. */
export function createUploadResumeStore({ accountId, storage } = {}) {
  const normalizedAccountId = String(accountId ?? '').trim();
  if (!normalizedAccountId) {
    return Object.freeze({
      load: () => null,
      store: () => false,
      clear: () => false,
    });
  }

  let browserStorage = storage;
  if (!browserStorage) {
    try {
      browserStorage = globalThis.localStorage;
    } catch (_) {
      return Object.freeze({
        load: () => null,
        store: () => false,
        clear: () => false,
      });
    }
  }
  const key = `${UPLOAD_RESUME_KEY_PREFIX}${normalizedAccountId}`;

  try {
    browserStorage.removeItem(LEGACY_UPLOAD_RESUME_KEY);
  } catch (_) {
    // Browser storage is optional UI state; unavailable storage cannot block uploads.
  }

  const clear = () => {
    try {
      browserStorage.removeItem(key);
      return true;
    } catch (_) {
      return false;
    }
  };

  return Object.freeze({
    load: () => {
      try {
        return JSON.parse(browserStorage.getItem(key) || 'null');
      } catch (_) {
        clear();
        return null;
      }
    },
    store: (upload, replace = false) => {
      try {
        browserStorage.setItem(key, JSON.stringify({
          id: upload.id,
          parentPath: upload.parentPath,
          name: upload.name,
          expectedBytes: upload.expectedBytes,
          replace,
        }));
        return true;
      } catch (_) {
        return false;
      }
    },
    clear,
  });
}

function storedUpload(resumeStore = uploadResumeStore) {
  return resumeStore.load();
}

function storeUpload(upload, replace = false, resumeStore = uploadResumeStore) {
  resumeStore.store(upload, replace);
}

function clearStoredUpload(resumeStore = uploadResumeStore) {
  resumeStore.clear();
}

function renderUploadProgress(upload, detailText) {
  const progress = document.getElementById('shared-upload-progress');
  const detail = document.getElementById('shared-upload-detail');
  const cancel = document.getElementById('shared-upload-cancel');
  const percent = uploadProgressPercent(upload);
  if (progress) {
    progress.value = percent;
    progress.textContent = `${percent}%`;
  }
  if (detail) detail.textContent = detailText || `${upload.name}: ${percent}%`;
  if (cancel) cancel.hidden = !upload?.id
    || !['ACTIVE', 'CANCEL_PENDING'].includes(upload?.state);
}

function base64Url(bytes) {
  let binary = '';
  new Uint8Array(bytes).forEach(value => { binary += String.fromCharCode(value); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

async function chunkDigest(chunk) {
  return base64Url(await crypto.subtle.digest('SHA-256', await chunk.arrayBuffer()));
}

async function putUploadChunk(upload, offset, chunk, signal) {
  const response = await fetch(API.sharedFolder.uploadChunk(upload.id, offset), {
    method: 'PUT',
    headers: authHeaders({
      'Content-Type': 'application/octet-stream',
      'X-Chunk-SHA-256': await chunkDigest(chunk),
    }),
    body: chunk,
    cache: 'no-store',
    signal,
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data?.messages?.[0]?.description || `Upload failed: ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data.payload ?? data;
}

async function uploadFile(parentPath, file, signal, resumeStore = uploadResumeStore) {
  const upload = await runUploadWorkflow({
    parentPath,
    file,
    resume: storedUpload(resumeStore),
    signal,
    digest: chunkDigest,
    loadStatus: (id, requestSignal) => fetchJson(API.sharedFolder.uploadStatus(id), {
      headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store', signal: requestSignal,
    }),
    listEntries: (path, requestSignal) => fetchJson(API.sharedFolder.entries(path), {
      headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store', signal: requestSignal,
    }),
    confirmReplace: target => window.confirm(`Replace the existing ${target.name}?`),
    createUpload: (request, requestSignal) => fetchJson(API.sharedFolder.uploads, {
      method: 'POST', headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
      body: JSON.stringify(request), signal: requestSignal,
    }),
    putChunk: (current, offset, chunk, requestSignal) =>
      putUploadChunk(current, offset, chunk, requestSignal),
    completeUpload: (current, replace, requestSignal) =>
      fetchJson(API.sharedFolder.uploadComplete(current.id), {
        method: 'POST', headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
        body: JSON.stringify({ replace }), signal: requestSignal,
      }),
    onCreated: (createdUpload, replace) => storeUpload(createdUpload, replace, resumeStore),
    onProgress: current => renderUploadProgress(current, `Uploading ${file.name}`),
  });
  clearStoredUpload(resumeStore);
  renderUploadProgress(upload, `${file.name} uploaded.`);
  return upload;
}

export async function configureUploadPanel(account, currentPath, resumeStore = uploadResumeStore) {
  if (typeof document === 'undefined') return;
  const panel = document.getElementById('shared-upload-panel');
  if (!panel || !accountHasSharedFolderWrite(account)) return;
  const resume = storedUpload(resumeStore);
  panel.hidden = !resume;
  document.querySelector('[data-shared-upload-toggle]')
    ?.setAttribute('aria-expanded', String(!panel.hidden));
  const form = document.getElementById('shared-upload-form');
  const fileInput = document.getElementById('shared-upload-file');
  const cancel = document.getElementById('shared-upload-cancel');
  const pause = document.getElementById('shared-upload-pause');
  if (resume) {
    try {
      const upload = await fetchJson(API.sharedFolder.uploadStatus(resume.id), {
        headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
      });
      if (uploadIsTerminal(upload)) {
        clearStoredUpload(resumeStore);
        renderUploadProgress(upload, `${upload.name} is ${String(upload.state).toLowerCase()}.`);
      } else {
        renderUploadProgress(upload, `Resume ${upload.name} by choosing the same local file.`);
      }
    } catch (error) {
      if (error?.status === 409 || error?.status === 404) clearStoredUpload(resumeStore);
      else throw error;
    }
  }
  const startUpload = file => {
    const operation = uploadOperationGate.start(signal => uploadFile(currentPath(), file, signal, resumeStore));
    if (!operation) {
      status('An upload is already in progress. Pause it before choosing another file.');
      return;
    }
    void operation
      .then(() => status('Upload complete. Refresh to view the file.'))
      .catch(error => {
        if (error?.name === 'AbortError') status('Upload paused. Choose the same file to resume.');
        else if (isSharedFolderAccessDenied(error)) handleSharedFolderAccessLoss(error.status);
        else status(error?.message || 'Upload failed. You can resume or cancel it.');
      });
  };
  form?.addEventListener('submit', event => {
    event.preventDefault();
    const file = fileInput?.files?.[0];
    if (!file) {
      status('Choose a file to upload.');
      return;
    }
    startUpload(file);
  });
  panel.addEventListener('dragover', event => {
    event.preventDefault();
    panel.classList.add('is-dragging');
  });
  panel.addEventListener('dragleave', () => panel.classList.remove('is-dragging'));
  panel.addEventListener('drop', event => {
    event.preventDefault();
    panel.classList.remove('is-dragging');
    const file = event.dataTransfer?.files?.[0];
    if (!file) return;
    startUpload(file);
  });
  pause?.addEventListener('click', () => {
    if (uploadOperationGate.pause()) status('Pausing upload…');
  });
  cancel?.addEventListener('click', () => {
    const saved = storedUpload(resumeStore);
    if (!saved) return;
    void cancelUploadWorkflow(uploadOperationGate, () => fetchJson(API.sharedFolder.uploadCancel(saved.id), {
      method: 'DELETE', headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
    })).then(upload => {
      clearStoredUpload(resumeStore);
      renderUploadProgress(upload, `${upload.name} upload cancelled.`);
      status('Upload cancelled.');
    }).catch(error => {
      if (isSharedFolderAccessDenied(error)) handleSharedFolderAccessLoss(error.status);
      else status(error?.message || 'The upload could not be cancelled.');
    });
  });
}

async function findReplacement(destinationPath, name) {
  const listing = await fetchJson(API.sharedFolder.entries(destinationPath), {
    headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
  });
  return listing.entries?.find(entry =>
    String(entry.name).toLocaleLowerCase() === String(name).toLocaleLowerCase()) || null;
}

function renderEntries(response, canWrite = false, navigatePath, options = {}) {
  const host = document.getElementById('shared-list');
  const searchResults = Boolean(options.searchResults);
  clear(host);
  if (!response.entries?.length) {
    const empty = document.createElement('p');
    empty.className = 'shared-folder-list-empty';
    empty.textContent = searchResults ? `No results for “${response.query}”.` : 'This folder is empty.';
    host.append(empty);
    return;
  }
  sortSharedFolderEntries(response.entries).forEach(entry => {
    const kind = sharedFolderEntryKind(entry);
    const row = document.createElement('div');
    row.className = 'shared-folder-entry';
    row.dataset.kind = kind;

    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'shared-folder-entry-name';
    const icon = document.createElement('span');
    icon.className = 'shared-folder-entry-icon';
    icon.dataset.kind = kind;
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = ({
      folder: '▰', audio: '♫', video: '▶', image: '▧', pdf: 'PDF', text: 'TXT', file: '◇',
    })[kind];
    const identity = document.createElement('span');
    identity.className = 'shared-folder-entry-identity';
    const name = document.createElement('span');
    name.className = 'shared-folder-entry-label';
    name.textContent = entry.name;
    const type = document.createElement('span');
    type.className = 'shared-folder-entry-type';
    type.textContent = kind === 'folder' ? 'Folder' : kind;
    identity.append(name, type);
    if (searchResults) {
      const parentPath = document.createElement('span');
      parentPath.className = 'shared-folder-entry-parent-path';
      renderSharedFolderEntryParentPath(parentPath, entry);
      identity.append(parentPath);
    }
    open.append(icon, identity);
    open.addEventListener('click', () => {
      if (entry.type === 'DIRECTORY') {
        void navigatePath(entry.path);
      } else {
        host.querySelector('.shared-folder-entry.is-selected')?.classList.remove('is-selected');
        row.classList.add('is-selected');
        void preview(entry).catch(error => status(error?.message || 'The preview could not be loaded.'));
      }
    });

    const size = document.createElement('span');
    size.className = 'shared-folder-entry-size';
    size.textContent = entry.type === 'DIRECTORY' ? '—' : formatSharedFolderSize(entry.size);
    const modified = document.createElement('span');
    modified.className = 'shared-folder-entry-modified';
    modified.textContent = formatSharedFolderModifiedAt(entry.modifiedAt);

    const actions = document.createElement('div');
    actions.className = 'shared-folder-entry-actions';
    if (entry.type === 'FILE') {
      const save = document.createElement('button');
      save.type = 'button';
      save.className = 'shared-folder-entry-download';
      save.textContent = 'Download';
      save.setAttribute('aria-label', `Download ${entry.name}`);
      save.addEventListener('click', () => {
        void download(entry).catch(error =>
          status(error?.message || 'The download could not be started.'));
      });
      actions.append(save);
    }

    const menu = document.createElement('details');
    menu.className = 'shared-folder-entry-menu';
    const summary = document.createElement('summary');
    summary.textContent = '•••';
    summary.setAttribute('aria-label', `More actions for ${entry.name}`);
    const menuItems = document.createElement('div');
    menuItems.className = 'shared-folder-entry-menu-items';
    const menuButton = (label, action, danger = false) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = danger ? 'is-danger' : '';
      button.textContent = label;
      button.addEventListener('click', action);
      menuItems.append(button);
    };
    menuButton('Copy link', () => {
      menu.open = false;
      void copyLink(entry).catch(error => status(error?.message || 'The link could not be copied.'));
    });
    if (canWrite) {
      menuButton('Rename', () => {
        menu.open = false;
        const name = window.prompt('New name', entry.name);
        if (!name?.trim() || name.trim() === entry.name) return;
        void mutationRequest(API.sharedFolder.rename, 'PATCH', {
          path: entry.path, name: name.trim(), observedToken: entry.observedToken,
        }).then(() => window.location.reload()).catch(mutationFailure);
      });
      menuButton('Move', () => {
        menu.open = false;
        const destinationPath = window.prompt('Destination folder path',
          response.path ?? sharedFolderEntryParentPath(entry));
        if (destinationPath === null) return;
        const name = window.prompt('Destination name', entry.name);
        if (!name?.trim()) return;
        const initial = moveMutationPayload(entry, destinationPath.trim(), name.trim());
        void mutationRequest(API.sharedFolder.move, 'POST', initial).then(
          () => window.location.reload(),
          async error => {
            if (error?.status !== 409
                || !window.confirm('The target exists or changed. Replace the observed target?')) {
              mutationFailure(error);
              return;
            }
            try {
              const replacement = await findReplacement(destinationPath.trim(), name.trim());
              if (!replacement) throw error;
              await mutationRequest(API.sharedFolder.move, 'POST',
                moveMutationPayload(entry, destinationPath.trim(), name.trim(), replacement));
              window.location.reload();
            } catch (replacementError) {
              mutationFailure(replacementError);
            }
          });
      });
      menuButton('Delete', () => {
        menu.open = false;
        if (!window.confirm(`Delete ${entry.name}? This cannot be undone yet.`)) return;
        void mutationRequest(API.sharedFolder.delete, 'DELETE', {
          path: entry.path, observedToken: entry.observedToken,
        }).then(() => window.location.reload()).catch(mutationFailure);
      }, true);
    }
    menu.append(summary, menuItems);
    actions.append(menu);

    row.append(open, size, modified, actions);
    host.append(row);
  });
}

/** Own cancellable folder-list navigation while leaving the mounted preview untouched. */
export function createSharedFolderNavigator({ load, render, pushPath, onError }) {
  let generation = 0;

  const visit = async (path, push) => {
    const requestGeneration = ++generation;
    try {
      const response = await load(path);
      if (requestGeneration !== generation) return false;
      await render(response);
      if (requestGeneration !== generation) return false;
      if (push) pushPath(response.path);
      return true;
    } catch (error) {
      if (requestGeneration === generation) onError(error);
      return false;
    }
  };

  return Object.freeze({
    open: path => visit(path, true),
    restore: path => visit(path, false),
    invalidate: () => { generation += 1; },
  });
}

/** Bind the visible command to the top-document radio owner with safe status outcomes. */
export function bindSharedFolderRadioControl({
  button,
  playRadioFn = joinSharedFolderRadio,
  statusFn = status,
  handleAccessLossFn = handleSharedFolderAccessLoss,
}) {
  if (!button || typeof playRadioFn !== 'function') return false;
  button.addEventListener('click', async () => {
    if (button.disabled) return;
    button.disabled = true;
    statusFn('Connecting to shared-folder radio…');
    try {
      const response = await playRadioFn();
      statusFn(response?.status === 'EMPTY'
        ? 'The shared-folder radio has no audio tracks yet.'
        : 'Shared-folder radio is live.');
    } catch (error) {
      if (isSharedFolderAccessDenied(error)) handleAccessLossFn(error.status);
      else statusFn(error?.message || 'The shared-folder radio is unavailable.');
    } finally {
      button.disabled = false;
    }
  });
  return true;
}

function configureSharedFolderRadioControl() {
  if (typeof document === 'undefined') return false;
  return bindSharedFolderRadioControl({
    button: document.getElementById('shared-folder-radio'),
  });
}

/** Own search request cancellation so an older result cannot replace a newer page state. */
export function createSharedFolderSearchController({
  load, render, restore, onError, invalidateNavigation = () => {},
}) {
  let generation = 0;
  let active = false;
  let currentResponse = null;
  let currentController = null;

  const begin = () => {
    generation += 1;
    currentController?.abort();
    currentController = new AbortController();
    return { generation, signal: currentController.signal };
  };

  const search = async query => {
    const requestedQuery = String(query ?? '').trim();
    if (!requestedQuery) {
      onError(new Error('Enter a search term.'));
      return false;
    }
    active = true;
    invalidateNavigation();
    const request = begin();
    try {
      const response = validateSharedFolderSearchResponse(
        await load(requestedQuery, request.signal, null));
      if (request.generation !== generation) return false;
      currentResponse = response;
      render(response);
      return true;
    } catch (error) {
      if (request.generation !== generation || error?.name === 'AbortError') return false;
      onError(error);
      return false;
    }
  };

  const loadMore = async () => {
    if (!active || !currentResponse?.nextCursor) return false;
    const previous = currentResponse;
    const request = begin();
    try {
      const page = validateSharedFolderSearchResponse(
        await load(previous.query, request.signal, previous.nextCursor));
      if (request.generation !== generation) return false;
      if (page.query !== previous.query || page.generation !== previous.generation) {
        throw new Error('The shared folder catalog changed. Search again.');
      }
      const paths = new Set(previous.entries.map(entry => entry.path));
      if (page.entries.some(entry => paths.has(entry.path))) {
        throw new Error('The shared folder returned an invalid search response.');
      }
      currentResponse = Object.freeze({
        ...page,
        entries: Object.freeze([...previous.entries, ...page.entries]),
      });
      render(currentResponse);
      return true;
    } catch (error) {
      if (request.generation !== generation || error?.name === 'AbortError') return false;
      onError(error);
      return false;
    }
  };

  const clear = async () => {
    active = false;
    currentResponse = null;
    const request = begin();
    try {
      const restored = await restore(request.signal);
      return request.generation === generation && restored !== false;
    } catch (error) {
      if (request.generation !== generation || error?.name === 'AbortError') return false;
      onError(error);
      return false;
    }
  };

  const leave = () => {
    const wasActive = active;
    active = false;
    currentResponse = null;
    begin();
    return wasActive;
  };

  return Object.freeze({
    search, loadMore, clear, leave, active: () => active,
    hasMore: () => Boolean(active && currentResponse?.nextCursor),
  });
}

export function bindSharedFolderSearchForm({
  form, input, clearButton, loadMoreButton, controller, statusFn = status,
}) {
  if (!form || !input || !clearButton || !controller) return false;

  const updateActions = () => {
    clearButton.disabled = !controller.active();
    if (loadMoreButton) {
      loadMoreButton.disabled = !controller.hasMore();
      loadMoreButton.hidden = !controller.hasMore();
    }
  };
  form.addEventListener('submit', event => {
    event.preventDefault();
    void controller.search(input.value).then(updateActions);
  });
  loadMoreButton?.addEventListener('click', () => {
    void controller.loadMore().then(updateActions);
  });
  clearButton.addEventListener('click', () => {
    void controller.clear().then(restored => {
      if (restored) input.value = '';
      updateActions();
    });
  });
  input.addEventListener('input', () => {
    if (!input.value.trim() && controller.active()) {
      statusFn('Clear search to return to the current folder.');
    }
  });
  updateActions();
  return true;
}

function configureSharedFolderSearchForm({ controller, statusFn = status }) {
  if (typeof document === 'undefined') return;
  bindSharedFolderSearchForm({
    form: document.getElementById('shared-folder-search-form'),
    input: document.getElementById('shared-folder-search-query'),
    clearButton: document.getElementById('shared-folder-search-clear'),
    loadMoreButton: document.getElementById('shared-folder-search-more'),
    controller,
    statusFn,
  });
}

export async function initializeSharedFolderPage({
  pageRoot = root,
  getAuthTokenFn = getAuthToken,
  authHeadersFn = authHeaders,
  fetchJsonFn = fetchJson,
  accountHasReadFn = accountHasSharedFolderRead,
  requestedPath = () => new URLSearchParams(window.location.search).get('path') || '',
  renderBreadcrumbsFn = renderBreadcrumbs,
  renderToolbarFn = renderToolbar,
  renderEntriesFn = renderEntries,
  configureUploadPanelFn = configureUploadPanel,
  statusFn = status,
  handleAccessLossFn = handleSharedFolderAccessLoss,
  redirectToLogin = () => window.location.replace(loginRedirectUrl(currentRedirectTarget())),
  pushPath = path => window.history.pushState({}, '', internalSharedFolderUrl(path)),
  addPopstateListener = listener => globalThis.window?.addEventListener('popstate', listener),
} = {}) {
  if (!pageRoot) return;
  uploadResumeStore = createUploadResumeStore();
  if (!getAuthTokenFn()) {
    redirectToLogin();
    return;
  }
  try {
    const account = await fetchJsonFn(API.accounts.me, {
      headers: authHeadersFn(),
      redirectOnUnauthorized: false,
    });
    if (!accountHasReadFn(account)) {
      pageRoot.classList.remove('d-none');
      statusFn('Your account does not have shared-folder read access.');
      return;
    }
    configureSharedFolderRadioControl();
    const canWrite = accountHasSharedFolderWrite(account);
    if (canWrite) uploadResumeStore = createUploadResumeStore({ accountId: account.id });
    let activePath = '';
    let navigator;
    let searchController;
    const navigatePath = path => {
      searchController?.leave();
      return navigator.open(path);
    };
    const handleFolderError = error => {
      pageRoot.classList.remove('d-none');
      if (isSharedFolderAccessDenied(error)) handleAccessLossFn(error.status);
      else statusFn(error?.message || 'The shared folder is unavailable.');
    };
    navigator = createSharedFolderNavigator({
      load: path => fetchJsonFn(API.sharedFolder.entries(path), {
        headers: authHeadersFn(),
        redirectOnUnauthorized: false,
      }),
      render: async response => {
        if (searchController?.active()) return;
        activePath = response.path;
        pageRoot.classList.remove('d-none');
        renderBreadcrumbsFn(response.path, navigatePath);
        renderToolbarFn(response.path, canWrite);
        renderEntriesFn(response, canWrite, navigatePath);
        statusFn(`${response.entries.length} item${response.entries.length === 1 ? '' : 's'}`);
      },
      pushPath,
      onError: handleFolderError,
    });
    if (!await navigator.restore(requestedPath())) return;
    searchController = createSharedFolderSearchController({
      load: (query, signal, cursor) => fetchJsonFn(API.sharedFolder.search(query, cursor, 25), {
        headers: authHeadersFn(),
        redirectOnUnauthorized: false,
        signal,
      }),
      render: response => {
        pageRoot.classList.remove('d-none');
        renderEntriesFn(response, canWrite, navigatePath, { searchResults: true });
        statusFn(sharedFolderSearchResultDescription(response));
      },
      restore: () => navigator.restore(activePath),
      onError: handleFolderError,
      invalidateNavigation: navigator.invalidate,
    });
    configureSharedFolderSearchForm({ controller: searchController, statusFn });
    await configureUploadPanelFn(account, () => activePath, uploadResumeStore);
    addPopstateListener(() => {
      searchController.leave();
      void navigator.restore(requestedPath());
    });
  } catch (error) {
    pageRoot.classList.remove('d-none');
    if (isSharedFolderAccessDenied(error)) {
      handleAccessLossFn(error.status);
    } else {
      statusFn(error?.message || 'The shared folder is unavailable.');
    }
  }
}

if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
  navigator.serviceWorker.addEventListener('message', event => {
    if (event.data?.type === 'shared-folder-auth-denied') {
      handleSharedFolderAccessLoss(event.data.status);
    }
  });
}

void initializeSharedFolderPage();
