/** Pure shared-folder UI helpers. */

/** Return the effective read capability reported by the current-account API. */
export function accountHasSharedFolderRead(account) {
  if (account?.role === 'ADMIN') return true;
  const permissions = new Set(Array.isArray(account?.permissions) ? account.permissions : []);
  return permissions.has('SHARED_FOLDER_READ') || permissions.has('SHARED_FOLDER_WRITE');
}

/** Return the effective write capability reported by the current-account API. */
export function accountHasSharedFolderWrite(account) {
  if (account?.role === 'ADMIN') return true;
  const permissions = new Set(Array.isArray(account?.permissions) ? account.permissions : []);
  return permissions.has('SHARED_FOLDER_WRITE');
}

/** Return a bounded whole-number percentage for an upload status response. */
export function uploadProgressPercent(status) {
  const expected = Number(status?.expectedBytes);
  const offset = Number(status?.nextOffset);
  if (!Number.isFinite(expected) || expected <= 0 || !Number.isFinite(offset)) return 0;
  return Math.max(0, Math.min(100, Math.round((offset / expected) * 100)));
}

/** Return whether a persisted resume record must be cleared instead of resumed. */
export function uploadIsTerminal(status) {
  return ['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(status?.state);
}

/** Require the same decoded destination, name, and byte length before resuming local bytes. */
export function uploadResumeMatchesFile(session, file, parentPath) {
  return String(session?.parentPath ?? '') === String(parentPath ?? '')
    && String(session?.name ?? '') === String(file?.name ?? '')
    && Number(session?.expectedBytes) === Number(file?.size);
}

/** Return the positive server-advertised upload chunk size. */
export function uploadChunkSize(status) {
  const size = Number(status?.chunkSizeBytes);
  if (!Number.isSafeInteger(size) || size < 1) {
    throw new Error('The server did not provide a valid upload chunk size.');
  }
  return size;
}

/** Rehash only committed server-proven chunks before trusting a local resume file. */
export async function verifyCommittedUploadPrefix(status, file, digest) {
  const nextOffset = Number(status?.nextOffset);
  const proofs = Array.isArray(status?.committedChunks) ? status.committedChunks : [];
  if (!Number.isSafeInteger(nextOffset) || nextOffset < 0 || nextOffset > Number(file?.size)) {
    throw new Error('The saved upload has invalid committed progress.');
  }
  let covered = 0;
  for (const proof of proofs) {
    const offset = Number(proof?.offset);
    const length = Number(proof?.length);
    if (!Number.isSafeInteger(offset) || offset !== covered
        || !Number.isSafeInteger(length) || length < 1 || offset + length > nextOffset
        || typeof proof?.sha256 !== 'string' || !proof.sha256) {
      throw new Error('The saved upload has invalid committed chunk proofs.');
    }
    const actual = await digest(file.slice(offset, offset + length));
    if (actual !== proof.sha256) {
      throw new Error('The selected file does not match the committed upload prefix.');
    }
    covered += length;
  }
  if (covered !== nextOffset) {
    throw new Error('The saved upload is missing committed chunk proofs.');
  }
}

/** Retry only network failures and transient server responses, with a bounded delay list. */
export async function retryUploadOperation(operation, options = {}) {
  const delays = Array.isArray(options.delays) ? options.delays : [100, 250];
  const signal = options.signal;
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      const status = Number(error?.status);
      const transient = !Number.isFinite(status) || (status >= 500 && status <= 599);
      if (signal?.aborted || error?.name === 'AbortError' || !transient || attempt >= delays.length) {
        throw error;
      }
      await new Promise((resolve, reject) => {
        const finish = () => {
          signal?.removeEventListener('abort', abort);
          resolve();
        };
        const timer = setTimeout(finish, Math.max(0, Number(delays[attempt]) || 0));
        const abort = () => {
          clearTimeout(timer);
          reject(new DOMException('Upload paused.', 'AbortError'));
        };
        signal?.addEventListener('abort', abort, { once: true });
      });
    }
  }
}

/** Run the browser upload protocol through injected network and UI decisions. */
export async function runUploadWorkflow(options) {
  const {
    parentPath, file, resume, signal, digest,
    loadStatus, listEntries, confirmReplace, createUpload, putChunk, completeUpload,
    onCreated = () => {}, onProgress = () => {},
  } = options;
  let upload;
  let replace = Boolean(resume?.replace);
  if (resume && uploadResumeMatchesFile(resume, file, parentPath)) {
    upload = await retryUploadOperation(() => loadStatus(resume.id, signal), { signal });
    if (uploadIsTerminal(upload)) {
      throw new Error(`The saved upload is ${String(upload.state).toLowerCase()}; choose the file again.`);
    }
    await verifyCommittedUploadPrefix(upload, file, digest);
  } else {
    if (resume) throw new Error('Cancel the saved upload or choose the same file before starting another.');
    const listing = await retryUploadOperation(() => listEntries(parentPath, signal), { signal });
    const target = listing.entries?.find(entry =>
      String(entry.name).toLocaleLowerCase() === String(file.name).toLocaleLowerCase());
    if (target) {
      replace = Boolean(confirmReplace(target));
      if (!replace) throw new Error('Upload cancelled because the target already exists.');
    }
    upload = await retryUploadOperation(() => createUpload({
      parentPath,
      name: file.name,
      expectedBytes: file.size,
      targetObservedToken: target?.observedToken || null,
    }, signal), { signal });
    onCreated(upload, replace);
  }
  onProgress(upload);
  signal?.throwIfAborted();
  const chunkSize = uploadChunkSize(upload);
  while (upload.nextOffset < upload.expectedBytes) {
    signal?.throwIfAborted();
    const offset = upload.nextOffset;
    const end = Math.min(upload.expectedBytes, offset + chunkSize);
    const chunk = file.slice(offset, end);
    upload = await retryUploadOperation(
      () => putChunk(upload, offset, chunk, signal), { signal });
    onProgress(upload);
  }
  return retryUploadOperation(() => completeUpload(upload, replace, signal), { signal });
}

/** Enforce one active upload workflow while allowing a client-only pause. */
export function createUploadOperationGate() {
  let current = null;
  return {
    start(operation) {
      if (current) return null;
      const controller = new AbortController();
      let promise;
      try {
        promise = Promise.resolve(operation(controller.signal));
      } catch (error) {
        promise = Promise.reject(error);
      }
      current = { controller, promise };
      promise.finally(() => {
        if (current?.promise === promise) current = null;
      }).catch(() => {});
      return promise;
    },
    pause() {
      if (!current) return false;
      current.controller.abort();
      return true;
    },
    async pauseAndSettle() {
      if (!current) return false;
      const active = current;
      active.controller.abort();
      try {
        await active.promise;
      } catch (_) {
        // An aborted request is expected; settling prevents DELETE racing the append rollback.
      }
      return true;
    },
    active() {
      return Boolean(current);
    },
  };
}

/** Abort and settle the active workflow, then retry cancellation while append rollback completes. */
export async function cancelUploadWorkflow(gate, cancel, options = {}) {
  await gate.pauseAndSettle();
  const delays = Array.isArray(options.delays) ? options.delays : [100, 250, 500];
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await cancel();
    } catch (error) {
      const status = Number(error?.status);
      const retryable = !Number.isFinite(status) || status === 409 || status >= 500 && status <= 599;
      if (!retryable || attempt >= delays.length) throw error;
      await new Promise(resolve => setTimeout(resolve, Math.max(0, Number(delays[attempt]) || 0)));
    }
  }
}

/** Build a move request that cannot replace unless the caller supplies the observed target. */
export function moveMutationPayload(source, destinationPath, name, replacement = null) {
  return {
    path: source.path,
    destinationPath: String(destinationPath ?? ''),
    name: String(name ?? ''),
    observedToken: source.observedToken,
    replace: Boolean(replacement?.observedToken),
    replacedObservedToken: replacement?.observedToken || null,
  };
}

/** Build root-first breadcrumb models from one decoded relative path. */
export function breadcrumbItems(path = '') {
  const parts = String(path || '').split('/').filter(Boolean);
  const items = [{ label: 'Shared', path: '' }];
  parts.forEach((label, index) => {
    items.push({ label, path: parts.slice(0, index + 1).join('/') });
  });
  return items;
}

/** Build a same-origin link that can be copied without exposing a filesystem path. */
export function internalSharedFolderUrl(path = '') {
  const params = new URLSearchParams({ path: String(path || '') });
  return `/shared?${params}`;
}

/** Render untrusted preview text without interpreting markup. */
export function renderPreviewText(target, text) {
  target.textContent = String(text ?? '');
}

/** Return whether a keyboard event should activate a focused entry. */
export function shouldActivateEntry(event) {
  return event?.key === 'Enter' || event?.key === ' ';
}

/** Return whether an API failure means the shared-folder session lost access. */
export function isSharedFolderAccessDenied(error) {
  return error?.status === 401 || error?.status === 403;
}
