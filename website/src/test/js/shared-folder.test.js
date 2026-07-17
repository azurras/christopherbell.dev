import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import {
  accountHasSharedFolderRead,
  breadcrumbItems,
  internalSharedFolderUrl,
  isSharedFolderAccessDenied,
  renderPreviewText,
  shouldActivateEntry,
} from '../../main/resources/static/js/lib/shared-folder.js';

test('shared-folder API paths encode each decoded relative path once', () => {
  assert.equal(API.sharedFolder.entries('music/live set'),
    '/api/shared-folder/2026-07-17/entries?path=music%2Flive+set');
  assert.equal(API.sharedFolder.content('music/track.flac'),
    '/api/shared-folder/2026-07-17/content?path=music%2Ftrack.flac');
  assert.equal(API.sharedFolder.preview('notes/<draft>.txt'),
    '/api/shared-folder/2026-07-17/preview?path=notes%2F%3Cdraft%3E.txt');
});

test('effective read access includes admins and stored write capability', () => {
  assert.equal(accountHasSharedFolderRead({ role: 'ADMIN', permissions: [] }), true);
  assert.equal(accountHasSharedFolderRead({ role: 'USER', permissions: ['SHARED_FOLDER_READ'] }), true);
  assert.equal(accountHasSharedFolderRead({ role: 'MOD', permissions: ['SHARED_FOLDER_WRITE'] }), true);
  assert.equal(accountHasSharedFolderRead({ role: 'USER', permissions: [] }), false);
});

test('breadcrumbs and copied links contain only internal decoded paths', () => {
  assert.deepEqual(breadcrumbItems('music/live set'), [
    { label: 'Shared', path: '' },
    { label: 'music', path: 'music' },
    { label: 'live set', path: 'music/live set' },
  ]);
  assert.equal(internalSharedFolderUrl('music/live set'), '/shared?path=music%2Flive+set');
});

test('preview text is rendered as text and keyboard activation is explicit', () => {
  const target = { textContent: '' };
  renderPreviewText(target, '<img src=x onerror=alert(1)>');
  assert.equal(target.textContent, '<img src=x onerror=alert(1)>');
  assert.equal(shouldActivateEntry({ key: 'Enter' }), true);
  assert.equal(shouldActivateEntry({ key: ' ' }), true);
  assert.equal(shouldActivateEntry({ key: 'Tab' }), false);
});

test('permission denial distinguishes 401 and 403 from other failures', () => {
  assert.equal(isSharedFolderAccessDenied({ status: 401 }), true);
  assert.equal(isSharedFolderAccessDenied({ status: 403 }), true);
  assert.equal(isSharedFolderAccessDenied({ status: 500 }), false);
});

test('shared-folder shell owns a responsive single-column mobile layout and download controls', () => {
  const css = fs.readFileSync('website/src/main/resources/static/css/main.css', 'utf8');
  const page = fs.readFileSync('website/src/main/resources/static/js/shared-folder.js', 'utf8');
  assert.match(css, /@media[^{}]*\(max-width:\s*767px\)[\s\S]*\.shared-folder-layout[\s\S]*grid-template-columns:\s*1fr/);
  assert.match(page, /download/);
  assert.match(page, /loginRedirectUrl/);
  assert.match(page, /clipboard\.writeText/);
  assert.match(page, /setAttribute\('sandbox', ''\)/);
});
