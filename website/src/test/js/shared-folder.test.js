import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import {
  accountHasSharedFolderRead,
  accountHasSharedFolderWrite,
  breadcrumbItems,
  internalSharedFolderUrl,
  isSharedFolderAccessDenied,
  renderPreviewText,
  shouldActivateEntry,
  uploadProgressPercent,
  uploadIsTerminal,
  uploadResumeMatchesFile,
  uploadChunkSize,
  verifyCommittedUploadPrefix,
  retryUploadOperation,
  createUploadOperationGate,
  runUploadWorkflow,
  cancelUploadWorkflow,
  moveMutationPayload,
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

test('effective write access and resumable upload progress are explicit', () => {
  assert.equal(accountHasSharedFolderWrite({ role: 'ADMIN', permissions: [] }), true);
  assert.equal(accountHasSharedFolderWrite({ role: 'USER', permissions: ['SHARED_FOLDER_WRITE'] }), true);
  assert.equal(accountHasSharedFolderWrite({ role: 'USER', permissions: ['SHARED_FOLDER_READ'] }), false);
  assert.equal(uploadProgressPercent({ expectedBytes: 20, nextOffset: 5 }), 25);
  assert.equal(uploadProgressPercent({ expectedBytes: 0, nextOffset: 0 }), 0);
  assert.equal(uploadProgressPercent({ expectedBytes: 20, nextOffset: 99 }), 100);
  assert.equal(uploadIsTerminal({ state: 'COMPLETED' }), true);
  assert.equal(uploadIsTerminal({ state: 'CANCELLED' }), true);
  assert.equal(uploadIsTerminal({ state: 'EXPIRED' }), true);
  assert.equal(uploadIsTerminal({ state: 'ACTIVE' }), false);
  assert.equal(uploadResumeMatchesFile(
    { parentPath: 'docs', name: 'video.mkv', expectedBytes: 10 },
    { name: 'video.mkv', size: 10 }, 'docs'), true);
  assert.equal(uploadResumeMatchesFile(
    { parentPath: 'docs', name: 'Video.MKV', expectedBytes: 10 },
    { name: 'video.mkv', size: 10 }, 'docs'), true);
  assert.equal(uploadResumeMatchesFile(
    { parentPath: 'docs', name: 'video.mkv', expectedBytes: 10 },
    { name: 'other.mkv', size: 10 }, 'docs'), false);
});

test('resume authenticates every committed server chunk and uses the configured chunk size', async () => {
  const bytes = new TextEncoder().encode('abcdefgh');
  const file = new Blob([bytes]);
  const digests = new Map([
    ['abcd', 'proof-1'],
    ['ef', 'proof-2'],
  ]);
  const status = {
    nextOffset: 6,
    chunkSizeBytes: 3,
    committedChunks: [
      { offset: 0, length: 4, sha256: 'proof-1' },
      { offset: 4, length: 2, sha256: 'proof-2' },
    ],
  };
  const digest = async chunk => digests.get(new TextDecoder().decode(await chunk.arrayBuffer()));

  assert.equal(uploadChunkSize(status), 3);
  await verifyCommittedUploadPrefix(status, file, digest);
  await assert.rejects(
    verifyCommittedUploadPrefix(status, new Blob(['abcdZZgh']), digest),
    /does not match the committed upload prefix/);
  assert.throws(() => uploadChunkSize({ chunkSizeBytes: 0 }), /chunk size/);
});

test('bounded retry handles only network and 5xx failures', async () => {
  let attempts = 0;
  const result = await retryUploadOperation(async () => {
    attempts += 1;
    if (attempts < 3) throw Object.assign(new Error('temporary'), { status: 503 });
    return 'ok';
  }, { delays: [0, 0] });
  assert.equal(result, 'ok');
  assert.equal(attempts, 3);

  attempts = 0;
  await assert.rejects(retryUploadOperation(async () => {
    attempts += 1;
    throw Object.assign(new Error('conflict'), { status: 409 });
  }, { delays: [0, 0] }), /conflict/);
  assert.equal(attempts, 1);
});

test('operation gate rejects double submit and pause aborts without cancelling server state', async () => {
  const gate = createUploadOperationGate();
  let release;
  const first = gate.start(signal => new Promise(resolve => {
    release = () => resolve(signal.aborted ? 'paused' : 'done');
  }));
  assert.equal(gate.start(async () => 'duplicate'), null);
  assert.equal(gate.pause(), true);
  release();
  assert.equal(await first, 'paused');
  assert.equal(gate.active(), false);
  assert.equal(await gate.start(async () => 'resumed'), 'resumed');
});

test('mocked browser workflow retries, chunks from server config, gates duplicates, and replaces explicitly', async () => {
  const file = Object.assign(new Blob(['abcdefgh']), { name: 'target.bin' });
  const gate = createUploadOperationGate();
  const chunks = [];
  let firstChunkAttempts = 0;
  let createRequest;
  let completedReplace;
  const operation = gate.start(signal => runUploadWorkflow({
    parentPath: 'docs', file, signal, resume: null,
    digest: async () => 'digest',
    loadStatus: async () => { throw new Error('unexpected resume'); },
    listEntries: async () => ({ entries: [{ name: 'target.bin', observedToken: 'target-proof' }] }),
    confirmReplace: () => true,
    createUpload: async request => {
      createRequest = request;
      return { id: 'u1', name: file.name, expectedBytes: file.size, nextOffset: 0,
        state: 'ACTIVE', chunkSizeBytes: 3, committedChunks: [] };
    },
    putChunk: async (upload, offset, chunk) => {
      if (offset === 0 && firstChunkAttempts++ === 0) {
        throw Object.assign(new Error('temporary'), { status: 503 });
      }
      chunks.push([offset, chunk.size]);
      return { ...upload, nextOffset: offset + chunk.size };
    },
    completeUpload: async (upload, replace) => {
      completedReplace = replace;
      return { ...upload, state: 'COMPLETED' };
    },
  }));
  assert.equal(gate.start(async () => 'duplicate-drop-or-submit'), null);
  assert.equal((await operation).state, 'COMPLETED');
  assert.deepEqual(chunks, [[0, 3], [3, 3], [6, 2]]);
  assert.equal(firstChunkAttempts, 2);
  assert.equal(createRequest.targetObservedToken, 'target-proof');
  assert.equal(completedReplace, true);
});

test('replacement uses canonical server spelling and upload-session create is never retried', async () => {
  const file = Object.assign(new Blob(['x']), { name: 'foo.mkv' });
  let createRequest;
  const completed = await runUploadWorkflow({
    parentPath: 'media', file, resume: null, digest: async () => 'digest',
    loadStatus: async () => { throw new Error('unexpected resume'); },
    listEntries: async () => ({ entries: [{ name: 'Foo.mkv', observedToken: 'target-proof' }] }),
    confirmReplace: () => true,
    createUpload: async request => {
      createRequest = request;
      return { id: 'u-canonical', parentPath: 'media', name: request.name,
        expectedBytes: 1, nextOffset: 0, state: 'ACTIVE', chunkSizeBytes: 1,
        committedChunks: [] };
    },
    putChunk: async upload => ({ ...upload, nextOffset: 1 }),
    completeUpload: async upload => ({ ...upload, state: 'COMPLETED' }),
  });
  assert.equal(completed.state, 'COMPLETED');
  assert.equal(createRequest.name, 'Foo.mkv');
  assert.equal(createRequest.targetObservedToken, 'target-proof');

  let attempts = 0;
  await assert.rejects(runUploadWorkflow({
    parentPath: 'media', file, resume: null, digest: async () => 'digest',
    loadStatus: async () => { throw new Error('unexpected resume'); },
    listEntries: async () => ({ entries: [] }), confirmReplace: () => false,
    createUpload: async () => {
      attempts += 1;
      throw Object.assign(new Error('ambiguous create'), { status: 503 });
    },
    putChunk: async () => { throw new Error('must not upload'); },
    completeUpload: async upload => upload,
  }), /ambiguous create/);
  assert.equal(attempts, 1);
});

test('case-insensitive resume still proves the committed local prefix', async () => {
  const file = Object.assign(new Blob(['abc']), { name: 'resume.bin' });
  const resume = { id: 'u-case', parentPath: 'docs', name: 'Resume.BIN', expectedBytes: 3 };
  const active = { ...resume, nextOffset: 3, state: 'ACTIVE', chunkSizeBytes: 3,
    committedChunks: [{ offset: 0, length: 3, sha256: 'proof' }] };
  let createCalls = 0;

  const completed = await runUploadWorkflow({
    parentPath: 'docs', file, resume, digest: async () => 'proof',
    loadStatus: async () => active, listEntries: async () => ({ entries: [] }),
    confirmReplace: () => false,
    createUpload: async () => { createCalls += 1; return active; },
    putChunk: async () => { throw new Error('prefix is already committed'); },
    completeUpload: async upload => ({ ...upload, state: 'COMPLETED' }),
  });

  assert.equal(completed.state, 'COMPLETED');
  assert.equal(createCalls, 0);
});

test('mocked browser workflow pauses without cancel, resumes, and rejects changed local bytes', async () => {
  const file = Object.assign(new Blob(['abc']), { name: 'resume.bin' });
  const resume = { id: 'u2', parentPath: 'docs', name: file.name, expectedBytes: file.size };
  const active = { id: 'u2', parentPath: 'docs', name: file.name, expectedBytes: file.size,
    nextOffset: 0, state: 'ACTIVE', chunkSizeBytes: 3, committedChunks: [] };
  const gate = createUploadOperationGate();
  let cancelCalls = 0;
  const paused = gate.start(signal => runUploadWorkflow({
    parentPath: 'docs', file, resume, signal, digest: async () => 'unused',
    loadStatus: async () => active,
    listEntries: async () => ({ entries: [] }), confirmReplace: () => false,
    createUpload: async () => active,
    putChunk: async (_upload, _offset, _chunk, requestSignal) => new Promise((resolve, reject) =>
      requestSignal.addEventListener('abort', () => reject(new DOMException('paused', 'AbortError')),
        { once: true })),
    completeUpload: async upload => upload,
    cancelUpload: async () => { cancelCalls += 1; },
  }));
  assert.equal(gate.pause(), true);
  await assert.rejects(paused, error => error.name === 'AbortError');
  assert.equal(cancelCalls, 0);

  const resumed = await runUploadWorkflow({
    parentPath: 'docs', file, resume, digest: async () => 'unused',
    loadStatus: async () => active,
    listEntries: async () => ({ entries: [] }), confirmReplace: () => false,
    createUpload: async () => active,
    putChunk: async (upload, offset, chunk) => ({ ...upload, nextOffset: offset + chunk.size }),
    completeUpload: async upload => ({ ...upload, state: 'COMPLETED' }),
  });
  assert.equal(resumed.state, 'COMPLETED');

  const changedStatus = { ...active, nextOffset: 3,
    committedChunks: [{ offset: 0, length: 3, sha256: 'original-proof' }] };
  await assert.rejects(runUploadWorkflow({
    parentPath: 'docs', file: Object.assign(new Blob(['xyz']), { name: 'resume.bin' }), resume,
    digest: async () => 'changed-proof', loadStatus: async () => changedStatus,
    listEntries: async () => ({ entries: [] }), confirmReplace: () => false,
    createUpload: async () => active,
    putChunk: async () => { throw new Error('must not upload changed bytes'); },
    completeUpload: async upload => upload,
  }), /does not match the committed upload prefix/);
});

test('cancel waits for aborted append settlement and retries the normal APPENDING conflict', async () => {
  const gate = createUploadOperationGate();
  let appendSettled = false;
  const upload = gate.start(signal => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => {
      appendSettled = true;
      reject(new DOMException('paused', 'AbortError'));
    }, { once: true });
  }));
  upload.catch(() => {});
  let cancels = 0;
  const cancelled = await cancelUploadWorkflow(gate, async () => {
    assert.equal(appendSettled, true);
    cancels += 1;
    if (cancels === 1) throw Object.assign(new Error('append rollback pending'), { status: 409 });
    return { state: 'CANCELLED' };
  }, { delays: [0] });
  assert.equal(cancelled.state, 'CANCELLED');
  assert.equal(cancels, 2);
});

test('shared-folder write API paths encode identifiers and expose resumable upload actions', () => {
  assert.equal(API.sharedFolder.folders, '/api/shared-folder/2026-07-17/folders');
  assert.equal(API.sharedFolder.uploads, '/api/shared-folder/2026-07-17/uploads');
  assert.equal(API.sharedFolder.uploadStatus('session/id'),
    '/api/shared-folder/2026-07-17/uploads/session%2Fid');
  assert.equal(API.sharedFolder.uploadChunk('session/id', 8192),
    '/api/shared-folder/2026-07-17/uploads/session%2Fid/chunks/8192');
  assert.equal(API.sharedFolder.uploadComplete('session/id'),
    '/api/shared-folder/2026-07-17/uploads/session%2Fid/complete');
});

test('move payload requires an explicit observed replacement token', () => {
  const source = { path: 'docs/a.txt', observedToken: 'source-token' };
  assert.deepEqual(moveMutationPayload(source, 'archive', 'a.txt'), {
    path: 'docs/a.txt', destinationPath: 'archive', name: 'a.txt',
    observedToken: 'source-token', replace: false, replacedObservedToken: null,
  });
  assert.deepEqual(moveMutationPayload(
    source, 'archive', 'a.txt', { name: 'A.txt', observedToken: 'target-token' }), {
    path: 'docs/a.txt', destinationPath: 'archive', name: 'A.txt',
    observedToken: 'source-token', replace: true, replacedObservedToken: 'target-token',
  });
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
  assert.match(page, /shared-upload-progress/);
  assert.match(page, /localStorage/);
  assert.match(page, /crypto\.subtle\.digest/);
  assert.match(page, /uploadCancel/);
  assert.match(page, /runUploadWorkflow/);
  assert.match(page, /uploadOperationGate/);
  assert.doesNotMatch(page, /UPLOAD_CHUNK_BYTES/);
  assert.match(page, /dragover/);
  assert.match(page, /API\.sharedFolder\.folders/);
  assert.match(page, /API\.sharedFolder\.rename/);
  assert.match(page, /API\.sharedFolder\.move/);
  assert.match(page, /API\.sharedFolder\.delete/);
  assert.match(page, /window\.confirm/);
});
