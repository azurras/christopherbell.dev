import assert from 'node:assert/strict';
import test from 'node:test';

const { promotedRoleForAction, rolePromotionOptions, sharedFolderPermissionState } =
    await import('../../main/resources/static/js/lib/back-office-users.js');

test('role promotion options only move users to higher-privilege roles', () => {
  assert.deepEqual(rolePromotionOptions({ role: 'USER' }), [
    { value: 'PROMOTE_MOD', label: 'Promote to MOD' },
    { value: 'PROMOTE_ADMIN', label: 'Promote to ADMIN' },
  ]);
  assert.deepEqual(rolePromotionOptions({ role: 'MOD' }), [
    { value: 'PROMOTE_ADMIN', label: 'Promote to ADMIN' },
  ]);
  assert.deepEqual(rolePromotionOptions({ role: 'ADMIN' }), []);
});

test('promotion action parser refuses demotion actions', () => {
  assert.equal(promotedRoleForAction('PROMOTE_MOD'), 'MOD');
  assert.equal(promotedRoleForAction('PROMOTE_ADMIN'), 'ADMIN');
  assert.equal(promotedRoleForAction('PROMOTE_USER'), null);
});

test('shared-folder controls keep write dependent on read', () => {
  const user = { role: 'USER', permissions: ['SHARED_FOLDER_READ', 'SHARED_FOLDER_WRITE'] };

  assert.deepEqual(sharedFolderPermissionState(user, { read: false }), {
    read: false,
    write: false,
    disabled: false,
  });
  assert.deepEqual(sharedFolderPermissionState({ role: 'USER', permissions: [] }, { write: true }), {
    read: true,
    write: true,
    disabled: false,
  });
});

test('shared-folder controls preserve default ADMIN access as checked and disabled', () => {
  assert.deepEqual(sharedFolderPermissionState({ role: 'ADMIN', permissions: [] }), {
    read: true,
    write: true,
    disabled: true,
  });
});
