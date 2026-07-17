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
  internalSharedFolderUrl,
  isSharedFolderAccessDenied,
  moveMutationPayload,
  renderPreviewText,
  uploadProgressPercent,
  uploadIsTerminal,
  uploadResumeMatchesFile,
} from './lib/shared-folder.js';
import {
  clearSharedFolderStreamingAuth,
  prepareSharedFolderStreamingAuth,
  sharedFolderStreamingDenial,
} from './lib/shared-folder-streaming.js';

const root = typeof document === 'undefined' ? null : document.getElementById('shared-folder-app');
let currentPreviewLostAccess = false;
const UPLOAD_RESUME_KEY = 'shared-folder-upload-resume-v1';
const UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024;

function clear(node) {
  while (node?.firstChild) node.removeChild(node.firstChild);
}

function status(message) {
  const target = document.getElementById('shared-folder-status');
  if (target) target.textContent = message;
}

function navigate(path) {
  window.location.href = internalSharedFolderUrl(path);
}

function renderBreadcrumbs(path) {
  const host = document.getElementById('shared-breadcrumbs');
  clear(host);
  breadcrumbItems(path).forEach((item, index) => {
    if (index > 0) host.append(document.createTextNode('/'));
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-sm btn-link';
    button.textContent = item.label;
    button.addEventListener('click', () => navigate(item.path));
    host.append(button);
  });
}

function handleSharedFolderAccessLoss(statusCode) {
  const denial = sharedFolderStreamingDenial(statusCode);
  currentPreviewLostAccess = true;
  status(denial.message);
  if (denial.redirectToLogin && !window.location.pathname.startsWith('/login')) {
    clearSharedFolderStreamingAuth();
    clearAuthState();
    window.location.replace(loginRedirectUrl(currentRedirectTarget()));
  }
}

async function prepareNativeStreaming() {
  try {
    await prepareSharedFolderStreamingAuth(getAuthToken());
    return true;
  } catch (error) {
    status(error?.message || 'Secure shared-folder streaming is unavailable.');
    return false;
  }
}

async function download(entry) {
  status(`Preparing ${entry.name}`);
  if (!await prepareNativeStreaming()) return;
  const link = document.createElement('a');
  link.href = API.sharedFolder.content(entry.path);
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
  heading.textContent = entry.name;
  host.append(heading);

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

  if (!['IMAGE', 'AUDIO', 'VIDEO', 'PDF'].includes(entry.previewKind)) {
    host.append(document.createTextNode('No inline preview is available. Download the file to view it.'));
    return;
  }

  if (!await prepareNativeStreaming()) return;
  currentPreviewLostAccess = false;
  const element = entry.previewKind === 'IMAGE' ? document.createElement('img')
    : entry.previewKind === 'AUDIO' ? document.createElement('audio')
      : entry.previewKind === 'VIDEO' ? document.createElement('video')
        : document.createElement('iframe');
  element.src = API.sharedFolder.preview(entry.path);
  element.title = `${entry.name} preview`;
  if (element instanceof HTMLMediaElement) element.controls = true;
  if (entry.previewKind === 'PDF') element.setAttribute('sandbox', '');
  element.addEventListener('error', () => {
    if (!currentPreviewLostAccess) status('The preview could not be loaded.');
  });
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
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'btn btn-sm btn-outline-light';
  copy.textContent = 'Copy folder link';
  copy.addEventListener('click', async () => {
    await navigator.clipboard.writeText(new URL(internalSharedFolderUrl(path), window.location.origin).href);
    status('Internal link copied');
  });
  host.append(copy);
  if (canWrite) {
    const create = document.createElement('button');
    create.type = 'button';
    create.className = 'btn btn-sm btn-warning';
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
}

function storedUpload() {
  try {
    return JSON.parse(localStorage.getItem(UPLOAD_RESUME_KEY) || 'null');
  } catch (_) {
    localStorage.removeItem(UPLOAD_RESUME_KEY);
    return null;
  }
}

function storeUpload(upload, replace = false) {
  localStorage.setItem(UPLOAD_RESUME_KEY, JSON.stringify({
    id: upload.id,
    parentPath: upload.parentPath,
    name: upload.name,
    expectedBytes: upload.expectedBytes,
    replace,
  }));
}

function clearStoredUpload() {
  localStorage.removeItem(UPLOAD_RESUME_KEY);
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

async function putUploadChunk(upload, offset, chunk) {
  const response = await fetch(API.sharedFolder.uploadChunk(upload.id, offset), {
    method: 'PUT',
    headers: authHeaders({
      'Content-Type': 'application/octet-stream',
      'X-Chunk-SHA-256': await chunkDigest(chunk),
    }),
    body: chunk,
    cache: 'no-store',
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data?.messages?.[0]?.description || `Upload failed: ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data.payload ?? data;
}

async function uploadFile(parentPath, file) {
  let resume = storedUpload();
  let upload;
  let replace = Boolean(resume?.replace);
  if (resume && uploadResumeMatchesFile(resume, file, parentPath)) {
    upload = await fetchJson(API.sharedFolder.uploadStatus(resume.id), {
      headers: authHeaders(),
      redirectOnUnauthorized: false,
      cache: 'no-store',
    });
    if (uploadIsTerminal(upload)) {
      clearStoredUpload();
      throw new Error(`The saved upload is ${String(upload.state).toLowerCase()}; choose the file again.`);
    }
  } else {
    if (resume) throw new Error('Cancel the saved upload or choose the same file before starting another.');
    const listing = await fetchJson(API.sharedFolder.entries(parentPath), {
      headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
    });
    const target = listing.entries?.find(entry =>
      String(entry.name).toLocaleLowerCase() === String(file.name).toLocaleLowerCase());
    if (target) {
      replace = window.confirm(`Replace the existing ${target.name}?`);
      if (!replace) throw new Error('Upload cancelled because the target already exists.');
    }
    upload = await fetchJson(API.sharedFolder.uploads, {
      method: 'POST',
      headers: authHeaders(),
      redirectOnUnauthorized: false,
      cache: 'no-store',
      body: JSON.stringify({
        parentPath,
        name: file.name,
        expectedBytes: file.size,
        targetObservedToken: target?.observedToken || null,
      }),
    });
    storeUpload(upload, replace);
  }
  renderUploadProgress(upload, `Uploading ${file.name}`);
  while (upload.nextOffset < upload.expectedBytes) {
    const end = Math.min(upload.expectedBytes, upload.nextOffset + UPLOAD_CHUNK_BYTES);
    const chunk = file.slice(upload.nextOffset, end);
    upload = await putUploadChunk(upload, upload.nextOffset, chunk);
    renderUploadProgress(upload);
  }
  upload = await fetchJson(API.sharedFolder.uploadComplete(upload.id), {
    method: 'POST',
    headers: authHeaders(),
    redirectOnUnauthorized: false,
    cache: 'no-store',
    body: JSON.stringify({ replace }),
  });
  clearStoredUpload();
  renderUploadProgress(upload, `${file.name} uploaded.`);
  return upload;
}

async function configureUploadPanel(path, account) {
  if (typeof document === 'undefined') return;
  const panel = document.getElementById('shared-upload-panel');
  if (!panel || !accountHasSharedFolderWrite(account)) return;
  panel.hidden = false;
  const form = document.getElementById('shared-upload-form');
  const fileInput = document.getElementById('shared-upload-file');
  const cancel = document.getElementById('shared-upload-cancel');
  const resume = storedUpload();
  if (resume) {
    try {
      const upload = await fetchJson(API.sharedFolder.uploadStatus(resume.id), {
        headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
      });
      if (uploadIsTerminal(upload)) {
        clearStoredUpload();
        renderUploadProgress(upload, `${upload.name} is ${String(upload.state).toLowerCase()}.`);
      } else {
        renderUploadProgress(upload, `Resume ${upload.name} by choosing the same local file.`);
      }
    } catch (error) {
      if (error?.status === 409 || error?.status === 404) clearStoredUpload();
      else throw error;
    }
  }
  form?.addEventListener('submit', event => {
    event.preventDefault();
    const file = fileInput?.files?.[0];
    if (!file) {
      status('Choose a file to upload.');
      return;
    }
    void uploadFile(path, file)
      .then(() => status('Upload complete. Refresh to view the file.'))
      .catch(error => {
        if (isSharedFolderAccessDenied(error)) handleSharedFolderAccessLoss(error.status);
        else status(error?.message || 'Upload failed. You can resume or cancel it.');
      });
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
    void uploadFile(path, file)
      .then(() => status('Upload complete. Refresh to view the file.'))
      .catch(error => {
        if (isSharedFolderAccessDenied(error)) handleSharedFolderAccessLoss(error.status);
        else status(error?.message || 'Upload failed. You can resume or cancel it.');
      });
  });
  cancel?.addEventListener('click', () => {
    const saved = storedUpload();
    if (!saved) return;
    void fetchJson(API.sharedFolder.uploadCancel(saved.id), {
      method: 'DELETE', headers: authHeaders(), redirectOnUnauthorized: false, cache: 'no-store',
    }).then(upload => {
      clearStoredUpload();
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

function renderEntries(response, canWrite = false) {
  const host = document.getElementById('shared-list');
  clear(host);
  if (!response.entries?.length) {
    host.textContent = 'This folder is empty.';
    return;
  }
  response.entries.forEach(entry => {
    const row = document.createElement('div');
    row.className = 'shared-folder-entry';
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'btn btn-link text-start flex-grow-1';
    open.textContent = `${entry.type === 'DIRECTORY' ? 'Folder' : 'File'}: ${entry.name}`;
    open.addEventListener('click', () => {
      if (entry.type === 'DIRECTORY') {
        navigate(entry.path);
      } else {
        void preview(entry).catch(error => status(error?.message || 'The preview could not be loaded.'));
      }
    });
    row.append(open);
    if (canWrite) {
      const rename = document.createElement('button');
      rename.type = 'button';
      rename.className = 'btn btn-sm btn-outline-light';
      rename.textContent = 'Rename';
      rename.addEventListener('click', () => {
        const name = window.prompt('New name', entry.name);
        if (!name?.trim() || name.trim() === entry.name) return;
        void mutationRequest(API.sharedFolder.rename, 'PATCH', {
          path: entry.path, name: name.trim(), observedToken: entry.observedToken,
        }).then(() => window.location.reload()).catch(mutationFailure);
      });
      const move = document.createElement('button');
      move.type = 'button';
      move.className = 'btn btn-sm btn-outline-light';
      move.textContent = 'Move';
      move.addEventListener('click', () => {
        const destinationPath = window.prompt('Destination folder path', response.path || '');
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
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'btn btn-sm btn-outline-danger';
      remove.textContent = 'Delete';
      remove.addEventListener('click', () => {
        if (!window.confirm(`Delete ${entry.name}? This cannot be undone yet.`)) return;
        void mutationRequest(API.sharedFolder.delete, 'DELETE', {
          path: entry.path, observedToken: entry.observedToken,
        }).then(() => window.location.reload()).catch(mutationFailure);
      });
      row.append(rename, move, remove);
    }
    if (entry.type === 'FILE') {
      const copy = document.createElement('button');
      copy.type = 'button';
      copy.className = 'btn btn-sm btn-outline-light';
      copy.textContent = 'Copy link';
      copy.addEventListener('click', () => copyLink(entry));
      const save = document.createElement('button');
      save.type = 'button';
      save.className = 'btn btn-sm btn-warning';
      save.textContent = 'Download';
      save.addEventListener('click', () => {
        void download(entry).catch(error => status(error?.message || 'The download could not be started.'));
      });
      row.append(copy, save);
    }
    host.append(row);
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
} = {}) {
  if (!pageRoot) return;
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
    const path = requestedPath();
    const response = await fetchJsonFn(API.sharedFolder.entries(path), {
      headers: authHeadersFn(),
      redirectOnUnauthorized: false,
    });
    pageRoot.classList.remove('d-none');
    renderBreadcrumbsFn(response.path);
    const canWrite = accountHasSharedFolderWrite(account);
    renderToolbarFn(response.path, canWrite);
    renderEntriesFn(response, canWrite);
    await configureUploadPanelFn(response.path, account);
    statusFn(`${response.entries.length} item${response.entries.length === 1 ? '' : 's'}`);
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
    if (event.data?.type === 'shared-folder-auth-request-token') {
      const controller = navigator.serviceWorker.controller;
      if (event.source !== controller || !event.ports?.[0]) return;
      event.ports[0].postMessage({
        type: 'shared-folder-auth-recovery',
        token: getAuthToken() || null,
      });
      return;
    }
    if (event.data?.type === 'shared-folder-auth-denied') {
      handleSharedFolderAccessLoss(event.data.status);
    }
  });
}

void initializeSharedFolderPage();
