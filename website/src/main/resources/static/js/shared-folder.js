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
  breadcrumbItems,
  internalSharedFolderUrl,
  isSharedFolderAccessDenied,
  renderPreviewText,
} from './lib/shared-folder.js';
import {
  clearSharedFolderStreamingAuth,
  prepareSharedFolderStreamingAuth,
  sharedFolderStreamingDenial,
} from './lib/shared-folder-streaming.js';

const root = typeof document === 'undefined' ? null : document.getElementById('shared-folder-app');
let currentPreviewLostAccess = false;

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

function renderToolbar(path) {
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
}

function renderEntries(response) {
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
    renderToolbarFn(response.path);
    renderEntriesFn(response);
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
