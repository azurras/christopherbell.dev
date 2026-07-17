import { API } from './lib/api.js';
import {
  authHeaders,
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

const root = document.getElementById('shared-folder-app');

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

async function authenticatedBlob(url) {
  const response = await fetch(url, { headers: authHeaders() });
  if (!response.ok) {
    const error = new Error(`Request failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return response.blob();
}

async function download(entry) {
  status(`Preparing ${entry.name}`);
  const blob = await authenticatedBlob(API.sharedFolder.content(entry.path));
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = entry.name;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
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
    const response = await fetchJson(API.sharedFolder.preview(entry.path), {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
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

  const blob = await authenticatedBlob(API.sharedFolder.preview(entry.path));
  const url = URL.createObjectURL(blob);
  const element = entry.previewKind === 'IMAGE' ? document.createElement('img')
    : entry.previewKind === 'AUDIO' ? document.createElement('audio')
      : entry.previewKind === 'VIDEO' ? document.createElement('video')
        : document.createElement('iframe');
  element.src = url;
  element.title = `${entry.name} preview`;
  if (element instanceof HTMLMediaElement) element.controls = true;
  if (entry.previewKind === 'PDF') element.setAttribute('sandbox', '');
  element.addEventListener('load', () => URL.revokeObjectURL(url), { once: true });
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
    open.addEventListener('click', () => entry.type === 'DIRECTORY' ? navigate(entry.path) : preview(entry));
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
      save.addEventListener('click', () => download(entry));
      row.append(copy, save);
    }
    host.append(row);
  });
}

async function initialize() {
  if (!root) return;
  if (!getAuthToken()) {
    window.location.replace(loginRedirectUrl(currentRedirectTarget()));
    return;
  }
  try {
    const accountResponse = await fetchJson(API.accounts.me, {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    if (!accountHasSharedFolderRead(accountResponse?.payload)) {
      root.classList.remove('d-none');
      status('Your account does not have shared-folder read access.');
      return;
    }
    const path = new URLSearchParams(window.location.search).get('path') || '';
    const response = await fetchJson(API.sharedFolder.entries(path), {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    root.classList.remove('d-none');
    renderBreadcrumbs(response.path);
    renderToolbar(response.path);
    renderEntries(response);
    status(`${response.entries.length} item${response.entries.length === 1 ? '' : 's'}`);
  } catch (error) {
    root.classList.remove('d-none');
    status(isSharedFolderAccessDenied(error)
      ? 'Shared-folder access was denied.'
      : (error?.message || 'The shared folder is unavailable.'));
  }
}

initialize();
