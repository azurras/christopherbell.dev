import assert from 'node:assert/strict';
import test from 'node:test';

const voteMutation = await import('../../main/resources/static/js/lib/restaurant-vote-mutation.js')
  .catch(cause => ({ loadFailure: cause }));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function button(vote) {
  return { dataset: { vote }, disabled: false };
}

test('picks vote mutation applies only the latest opposite-thumb response and restores both controls', async () => {
  assert.ifError(voteMutation.loadFailure);
  const up = button('UP');
  const down = button('DOWN');
  const first = deferred();
  const second = deferred();
  const applied = [];
  const errors = [];
  const mutate = voteMutation.createRestaurantVoteMutation({
    buttons: () => [up, down],
    apply: value => applied.push(value),
    showError: error => errors.push(error.message),
  });

  const firstRequest = mutate(() => first.promise);
  const secondRequest = mutate(() => second.promise);
  assert.deepEqual(applied, []);
  assert.equal(up.disabled, true);
  assert.equal(down.disabled, true);

  second.resolve({ myVote: 'DOWN' });
  await secondRequest;
  first.resolve({ myVote: 'UP' });
  await firstRequest;

  assert.deepEqual(applied, [{ myVote: 'DOWN' }]);
  assert.deepEqual(errors, []);
  assert.equal(up.disabled, false);
  assert.equal(down.disabled, false);
});

test('profile vote mutation keeps confirmed state immutable before response and restores controls after latest failure', async () => {
  assert.ifError(voteMutation.loadFailure);
  const up = button('UP');
  const down = button('DOWN');
  const pending = deferred();
  const applied = [];
  const errors = [];
  const mutate = voteMutation.createRestaurantVoteMutation({
    buttons: () => [up, down],
    apply: value => applied.push(value),
    showError: error => errors.push(error.message),
  });

  const request = mutate(() => pending.promise);
  assert.deepEqual(applied, []);
  pending.reject(new Error('Vote service unavailable'));
  await request;

  assert.deepEqual(applied, []);
  assert.deepEqual(errors, ['Vote service unavailable']);
  assert.equal(up.disabled, false);
  assert.equal(down.disabled, false);
});
