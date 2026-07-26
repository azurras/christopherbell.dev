import assert from 'node:assert/strict';
import test from 'node:test';

import { canEditFor, onEditAction } from '../../main/resources/static/js/lib/feed-context.js';

test('post editing UI uses the same owner and 15-minute boundary as the server', () => {
  const now = Date.parse('2026-07-26T12:15:00Z');
  const owner = canEditFor({ id: 'owner', role: 'USER' }, now);
  const admin = canEditFor({ id: 'admin', role: 'ADMIN' }, now);

  assert.equal(owner({ accountId: 'owner', createdOn: '2026-07-26T12:00:01Z' }), true);
  assert.equal(owner({ accountId: 'owner', createdOn: '2026-07-26T12:00:00Z' }), false);
  assert.equal(owner({ accountId: 'stranger', createdOn: '2026-07-26T12:14:00Z' }), false);
  assert.equal(admin({ accountId: 'owner', createdOn: '2026-07-26T12:14:00Z' }), true);
});

test('post edit action sends a PATCH with only replacement text', async () => {
  const calls = [];
  const edit = onEditAction(async (url, options) => {
    calls.push({ url, options });
    return { id: 'post-1', text: 'after' };
  }, () => ({ 'X-CSRF-TOKEN': 'csrf' }));

  await edit('post-1', 'after');

  assert.equal(calls[0].url, '/api/posts/2026-07-26/post-1');
  assert.equal(calls[0].options.method, 'PATCH');
  assert.deepEqual(JSON.parse(calls[0].options.body), { text: 'after' });
});
