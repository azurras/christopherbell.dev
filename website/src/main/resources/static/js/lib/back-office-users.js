const ROLE_ORDER = ['USER', 'MOD', 'ADMIN'];

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
