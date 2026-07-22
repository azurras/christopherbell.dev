import assert from 'node:assert/strict';
import test from 'node:test';

const {
  sharedAuditMarkup,
  sharedRecycleMarkup,
  sharedAuditFilters,
  purgeConfirmation,
  runSharedRecycleAction,
  createSharedRecycleActionHandler,
  sharedRecycleButton,
  sharedRecyclePagination,
} = await import('../../main/resources/static/js/lib/back-office-shared-folder.js');

test('recycle pagination exposes later bounded pages and prevents empty navigation', () => {
  assert.deepEqual(sharedRecyclePagination(0, 200), {
    label: 'Page 1', previousDisabled: true, nextDisabled: false,
  });
  assert.deepEqual(sharedRecyclePagination(2, 42), {
    label: 'Page 3', previousDisabled: false, nextDisabled: true,
  });
});

test('shared-folder audit markup escapes untrusted values and shows bounded event facts', () => {
  const markup = sharedAuditMarkup([{
    accountId: '<img src=x onerror=alert(1)>',
    action: 'RECYCLE',
    relativePath: 'docs/<script>.txt',
    outcome: 'accepted',
    failureCategory: '<b>access_denied</b>',
    clientIp: '<img src=x onerror=alert(2)>',
    occurredAt: '2026-07-18T12:00:00Z',
  }]);

  assert.match(markup, /RECYCLE/);
  assert.match(markup, /docs\/&lt;script&gt;\.txt/);
  assert.match(markup, /&lt;b&gt;access_denied&lt;\/b&gt;/);
  assert.match(markup, /&lt;img src=x onerror=alert\(2\)&gt;/);
  assert.doesNotMatch(markup, /<script>|<img|<b>/);
});

test('recycle markup exposes restore, explicit replace, and purge controls without HTML injection', () => {
  const markup = sharedRecycleMarkup([{
    id: 'item-1',
    originalPath: 'docs/"report".pdf',
    deletedByAccountId: 'account-1',
    deletedAt: '2026-07-18T12:00:00Z',
    expiresAt: '2026-08-17T12:00:00Z',
    size: 42,
  }]);

  assert.match(markup, /data-shared-recycle-action="restore"/);
  assert.match(markup, /data-shared-recycle-action="replace"/);
  assert.match(markup, /data-shared-recycle-action="purge"/);
  assert.doesNotMatch(markup, /docs\/"report"/);
});

test('audit filters omit blanks and typed purge confirmation is exact', () => {
  const form = { elements: {
    accountId: { value: ' account-1 ' },
    action: { value: 'RECYCLE' },
    outcome: { value: '' },
    path: { value: 'docs/report.pdf' },
    from: { value: '2026-07-01T00:00' },
    to: { value: '' },
  } };

  assert.deepEqual(sharedAuditFilters(form), {
    accountId: 'account-1',
    action: 'RECYCLE',
    path: 'docs/report.pdf',
    from: new Date('2026-07-01T00:00').toISOString(),
  });
  assert.equal(purgeConfirmation('item-1', 'PURGE item-1'), true);
  assert.equal(purgeConfirmation('item-1', 'purge item-1'), false);
});

test('invalid audit dates are omitted instead of breaking the admin filter', () => {
  const form = { elements: {
    accountId: { value: '' }, action: { value: '' }, outcome: { value: '' }, path: { value: '' },
    from: { value: 'not-a-date' }, to: { value: '' },
  } };

  assert.deepEqual(sharedAuditFilters(form), {});
});

test('restore replace and purge actions call only the explicitly confirmed API operation', async () => {
  const calls = [];
  const restore = async (id, replace) => calls.push(['restore', id, replace]);
  const purge = async (id, confirmation) => calls.push(['purge', id, confirmation]);

  assert.equal(await runSharedRecycleAction({
    id: 'item-1', action: 'restore', confirmReplace: () => false,
    promptPurge: () => '', restore, purge,
  }), true);
  assert.equal(await runSharedRecycleAction({
    id: 'item-1', action: 'replace', confirmReplace: () => false,
    promptPurge: () => '', restore, purge,
  }), false);
  assert.equal(await runSharedRecycleAction({
    id: 'item-1', action: 'replace', confirmReplace: () => true,
    promptPurge: () => '', restore, purge,
  }), true);
  assert.equal(await runSharedRecycleAction({
    id: 'item-1', action: 'purge', confirmReplace: () => false,
    promptPurge: () => 'PURGE item-1', restore, purge,
  }), true);
  assert.deepEqual(calls, [
    ['restore', 'item-1', false],
    ['restore', 'item-1', true],
    ['purge', 'item-1', 'PURGE item-1'],
  ]);
});

test('purge action rejects a mismatched phrase before calling the API', async () => {
  let called = false;
  await assert.rejects(() => runSharedRecycleAction({
    id: 'item-1', action: 'purge', confirmReplace: () => false,
    promptPurge: () => 'purge item-1', restore: async () => {},
    purge: async () => { called = true; },
  }), /did not match/);
  assert.equal(called, false);
});

test('actual Back Office handler wires delegation URLs methods bodies and refresh', async () => {
  class FakeButton {
    constructor(id, action) { this.id = id; this.action = action; this.disabled = false; }
    getAttribute(name) {
      return name === 'data-id' ? this.id
        : name === 'data-shared-recycle-action' ? this.action : null;
    }
  }
  const button = new FakeButton('item-7', 'replace');
  const target = { closest: selector =>
    selector === '[data-shared-recycle-action]' ? button : null };
  assert.equal(sharedRecycleButton(target, FakeButton), button);

  const calls = [];
  let refreshes = 0;
  const handle = createSharedRecycleActionHandler({
    api: {
      restore: id => `/admin/recycle/${id}/restore`,
      purge: id => `/admin/recycle/${id}`,
    },
    fetchJson: async (url, options) => calls.push([url, options]),
    authHeaders: () => ({ Authorization: 'Bearer test' }),
    refresh: async () => { refreshes += 1; },
    clearAlert: () => {},
    showAlert: message => { throw new Error(message); },
    confirmReplace: () => true,
    promptPurge: () => 'PURGE item-7',
  });

  await handle(button);
  assert.deepEqual(calls, [[
    '/admin/recycle/item-7/restore',
    {
      method: 'POST',
      headers: { Authorization: 'Bearer test' },
      body: JSON.stringify({ replace: true }),
    },
  ]]);
  assert.equal(refreshes, 1);
  assert.equal(button.disabled, false);

  button.action = 'purge';
  await handle(button);
  assert.deepEqual(calls[1], [
    '/admin/recycle/item-7',
    {
      method: 'DELETE',
      headers: { Authorization: 'Bearer test' },
      body: JSON.stringify({ confirmation: 'PURGE item-7' }),
    },
  ]);
  assert.equal(refreshes, 2);
});
