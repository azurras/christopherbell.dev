function permissionsFor(account) {
  return new Set(Array.isArray(account?.permissions) ? account.permissions : []);
}

/** Return the effective Music read capability reported by the current-account API. */
export function accountHasMusicRead(account) {
  if (account?.role === 'ADMIN') return true;
  const permissions = permissionsFor(account);
  return permissions.has('MUSIC_READ') || permissions.has('MUSIC_WRITE');
}

/** Return the effective Shared Folder read capability reported by the current-account API. */
export function accountHasSharedFolderRead(account) {
  if (account?.role === 'ADMIN') return true;
  const permissions = permissionsFor(account);
  return permissions.has('SHARED_FOLDER_READ') || permissions.has('SHARED_FOLDER_WRITE');
}

/** Return the effective Shared Folder write capability reported by the current-account API. */
export function accountHasSharedFolderWrite(account) {
  if (account?.role === 'ADMIN') return true;
  return permissionsFor(account).has('SHARED_FOLDER_WRITE');
}
