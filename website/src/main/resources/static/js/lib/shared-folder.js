/** Pure shared-folder UI helpers. */

/** Return the effective read capability reported by the current-account API. */
export function accountHasSharedFolderRead(account) {
  if (account?.role === 'ADMIN') return true;
  const permissions = new Set(Array.isArray(account?.permissions) ? account.permissions : []);
  return permissions.has('SHARED_FOLDER_READ') || permissions.has('SHARED_FOLDER_WRITE');
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
