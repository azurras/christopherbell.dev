import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} };
globalThis.document = { cookie: '', getElementById: () => null };
globalThis.window = { location: { origin: 'https://www.christopherbell.dev', search: '' } };

const picksModule = await import('../../main/resources/static/js/whats-for-lunch.js');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

test('picks boundary applies only the latest opposite-thumb response and restores the confirmed state after failure', async () => {
  assert.equal(typeof picksModule.createPicksVoteMutation, 'function');
  const up = { disabled: false };
  const down = { disabled: false };
  const first = deferred();
  const second = deferred();
  const applied = [];
  const errors = [];
  const mutate = picksModule.createPicksVoteMutation({
    buttons: () => [up, down],
    apply: value => applied.push(value),
    showError: error => errors.push(error.message),
  });

  const activeResubmission = mutate(() => first.promise);
  const oppositeVote = mutate(() => second.promise);
  assert.deepEqual(applied, []);
  assert.equal(up.disabled, true);
  assert.equal(down.disabled, true);
  second.resolve({ myVote: 'DOWN' });
  await oppositeVote;
  first.reject(new Error('stale UP failure'));
  await activeResubmission;

  assert.deepEqual(applied, [{ myVote: 'DOWN' }]);
  assert.deepEqual(errors, []);
  assert.equal(up.disabled, false);
  assert.equal(down.disabled, false);
});

test('picks boundary ignores stale success and preserves the confirmed vote after the latest failure', async () => {
  assert.equal(typeof picksModule.createPicksVoteMutation, 'function');
  const up = { disabled: false };
  const down = { disabled: false };
  const first = deferred();
  const second = deferred();
  const latest = deferred();
  const applied = [];
  const errors = [];
  const mutate = picksModule.createPicksVoteMutation({
    buttons: () => [up, down],
    apply: value => applied.push(value),
    showError: error => errors.push(error.message),
  });

  const activeResubmission = mutate(() => first.promise);
  const oppositeVote = mutate(() => second.promise);
  second.resolve({ myVote: 'DOWN' });
  await oppositeVote;
  first.resolve({ myVote: 'UP' });
  await activeResubmission;

  const latestVote = mutate(() => latest.promise);
  assert.deepEqual(applied, [{ myVote: 'DOWN' }]);
  latest.reject(new Error('latest DOWN failure'));
  await latestVote;

  assert.deepEqual(applied, [{ myVote: 'DOWN' }]);
  assert.deepEqual(errors, ['latest DOWN failure']);
  assert.equal(up.disabled, false);
  assert.equal(down.disabled, false);
});
