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
