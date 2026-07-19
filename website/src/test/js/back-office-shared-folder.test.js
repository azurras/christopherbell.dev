import assert from 'node:assert/strict';
import test from 'node:test';

const {
  sharedAuditMarkup,
  sharedRecycleMarkup,
  sharedAuditFilters,
  purgeConfirmation,
} = await import('../../main/resources/static/js/lib/back-office-shared-folder.js');

test('shared-folder audit markup escapes untrusted values and shows bounded event facts', () => {
  const markup = sharedAuditMarkup([{
    accountId: '<img src=x onerror=alert(1)>',
    action: 'RECYCLE',
    relativePath: 'docs/<script>.txt',
    outcome: 'accepted',
    occurredAt: '2026-07-18T12:00:00Z',
  }]);

  assert.match(markup, /RECYCLE/);
  assert.match(markup, /docs\/&lt;script&gt;\.txt/);
  assert.doesNotMatch(markup, /<script>|<img/);
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
