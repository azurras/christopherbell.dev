import assert from 'node:assert/strict';
import test from 'node:test';

const {
  accountPageNavigation,
  musicPermissionState,
  parseAdminAccountPage,
  promotedRoleForAction,
  rolePromotionOptions,
  sharedFolderPermissionState,
} =
    await import('../../main/resources/static/js/lib/back-office-users.js');
const { API } = await import('../../main/resources/static/js/lib/api.js');

test('admin account URL sends bounded paging and filter controls', () => {
  assert.equal(
      API.accounts.adminPage({
        page: 2,
        size: 25,
        sort: 'username',
        direction: 'asc',
        status: 'ACTIVE',
        role: 'USER',
        text: 'a.b',
      }),
      '/api/accounts/2026-07-26/admin?page=2&size=25&sort=username&direction=asc&status=ACTIVE&role=USER&text=a.b');
});

test('admin account page parser validates the network boundary and copies items', () => {
  const payload = {
    items: [{ id: 'account-1' }],
    page: 1,
    size: 25,
    totalElements: 26,
    totalPages: 2,
    sort: 'createdOn',
    direction: 'DESC',
  };

  assert.deepEqual(parseAdminAccountPage(payload), payload);
  assert.throws(
      () => parseAdminAccountPage({ ...payload, items: null }),
      /invalid account page/i);
});

test('admin account navigation reflects exact page bounds', () => {
  assert.deepEqual(accountPageNavigation({ page: 0, totalPages: 2 }), {
    previousDisabled: true,
    nextDisabled: false,
    label: 'Page 1 of 2',
  });
  assert.deepEqual(accountPageNavigation({ page: 1, totalPages: 2 }), {
    previousDisabled: false,
    nextDisabled: true,
    label: 'Page 2 of 2',
  });
  assert.deepEqual(accountPageNavigation({ page: 0, totalPages: 0 }), {
    previousDisabled: true,
    nextDisabled: true,
    label: 'Page 0 of 0',
  });
});

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

test('music controls preserve unrelated grants and keep write dependent on read', () => {
  const user = {
    role: 'USER',
    permissions: ['SHARED_FOLDER_READ', 'MUSIC_READ', 'MUSIC_WRITE'],
  };

  assert.deepEqual(musicPermissionState(user, { read: false }), {
    read: false,
    write: false,
    disabled: false,
  });
  assert.deepEqual(musicPermissionState({ role: 'USER', permissions: [] }, { write: true }), {
    read: true,
    write: true,
    disabled: false,
  });
  assert.equal(
      API.accounts.updateMusicPermissions('account / 1'),
      '/api/accounts/2026-07-28/account%20%2F%201/music-permissions');
});

test('music controls preserve default ADMIN access as checked and disabled', () => {
  assert.deepEqual(musicPermissionState({ role: 'ADMIN', permissions: [] }), {
    read: true,
    write: true,
    disabled: true,
  });
});
