const ROLE_ORDER = ['USER', 'MOD', 'ADMIN'];

/**
 * Validates the administrative account page returned by the server.
 */
export function parseAdminAccountPage(payload) {
  const integerFields = ['page', 'size', 'totalElements', 'totalPages'];
  const hasValidIntegers = integerFields.every(field =>
    Number.isInteger(payload?.[field]) && payload[field] >= 0);
  const hasValidMetadata = typeof payload?.sort === 'string'
      && ['ASC', 'DESC'].includes(payload?.direction);
  if (!payload || !Array.isArray(payload.items) || !hasValidIntegers || !hasValidMetadata) {
    throw new TypeError('Server returned an invalid account page.');
  }
  return { ...payload, items: [...payload.items] };
}

/**
 * Produces the accessible Back Office previous/next state for an account page.
 */
export function accountPageNavigation({ page, totalPages }) {
  const hasPages = totalPages > 0;
  return {
    previousDisabled: !hasPages || page <= 0,
    nextDisabled: !hasPages || page + 1 >= totalPages,
    label: hasPages ? `Page ${page + 1} of ${totalPages}` : 'Page 0 of 0',
  };
}

/**
 * Returns role promotions that increase privilege without offering demotions.
 */
export function rolePromotionOptions(account) {
  const role = String(account?.role || 'USER').toUpperCase();
  const roleIndex = ROLE_ORDER.indexOf(role);
  if (roleIndex < 0) {
    return [];
  }
  return ROLE_ORDER.slice(roleIndex + 1).map(nextRole => ({
    value: `PROMOTE_${nextRole}`,
    label: `Promote to ${nextRole}`,
  }));
}

/**
 * Converts a promotion action value into the role stored by the account API.
 */
export function promotedRoleForAction(action) {
  const role = String(action || '').replace(/^PROMOTE_/, '');
  return ['MOD', 'ADMIN'].includes(role) ? role : null;
}

/**
 * Resolves the Back Office checkbox state while keeping shared-folder write access dependent on
 * read access. ADMINs always retain the role-provided default and therefore cannot be edited.
 */
export function sharedFolderPermissionState(account, change = {}) {
  const isAdmin = String(account?.role || '').toUpperCase() === 'ADMIN';
  if (isAdmin) {
    return { read: true, write: true, disabled: true };
  }

  const permissions = new Set(account?.permissions || []);
  const currentRead = permissions.has('SHARED_FOLDER_READ');
  const currentWrite = permissions.has('SHARED_FOLDER_WRITE');
  if (change.read === false) {
    return { read: false, write: false, disabled: false };
  }

  const write = change.write ?? currentWrite;
  const read = change.read ?? currentRead;
  return { read: read || write, write, disabled: false };
}
