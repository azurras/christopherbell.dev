import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} };
globalThis.document = { cookie: '', getElementById: () => null };
globalThis.window = { location: { origin: 'https://www.christopherbell.dev', search: '' } };

const picksModule = await import('../../main/resources/static/js/whats-for-lunch.js');

const INITIAL = Object.freeze({
  id: 'restaurant-123',
  name: 'Taco Place',
  upVotes: 2,
  downVotes: 1,
  voteCount: 3,
  myVote: 'UP',
});

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function updated(vote) {
  return {
    ...INITIAL,
    upVotes: vote === 'UP' ? 3 : 2,
    downVotes: vote === 'DOWN' ? 2 : 1,
    voteCount: 4,
    myVote: vote,
  };
}

function picksVotePage() {
  const up = { dataset: { restaurantId: INITIAL.id, vote: 'UP' }, disabled: false };
  const down = { dataset: { restaurantId: INITIAL.id, vote: 'DOWN' }, disabled: false };
  const mount = {
    errors: [],
    querySelectorAll(selector) {
      return selector === '.lunch-vote-button' ? [up, down] : [];
    },
    insertAdjacentHTML(_position, html) {
      this.errors.push(html);
    },
  };
  const state = {
    picks: [INITIAL],
    session: { id: 'session-123', restaurants: [INITIAL] },
  };
  const renders = [];
  const calls = [];
  const pending = [];
  const setRestaurantVote = picksModule.createPicksVoteController({
    mount,
    getCurrentPicks: () => state.picks,
    setCurrentPicks: picks => { state.picks = picks; },
    getActiveSession: () => state.session,
    setActiveSession: session => { state.session = session; },
    renderPicks: picks => renders.push(picks),
    request: (url, options) => {
      const next = deferred();
      calls.push({ url, options });
      pending.push(next);
      return next.promise;
    },
    headers: extra => extra,
  });

  return { calls, down, mount, pending, renders, setRestaurantVote, state, up };
}

test('picks vote controller sends an active UP again and renders only after the server confirms it', async () => {
  assert.equal(typeof picksModule.createPicksVoteController, 'function');
  const page = picksVotePage();

  const request = page.setRestaurantVote(INITIAL.id, 'UP');

  assert.deepEqual(page.calls, [{
    url: '/api/whatsforlunch/restaurant/2026-05-17/vote',
    options: {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: '{"restaurantId":"restaurant-123","vote":"UP"}',
    },
  }]);
  assert.deepEqual(page.renders, []);
  assert.deepEqual(page.state.picks, [INITIAL]);
  assert.deepEqual(page.state.session.restaurants, [INITIAL]);
  assert.equal(page.up.disabled, true);
  assert.equal(page.down.disabled, true);

  page.pending[0].resolve(updated('UP'));
  await request;

  assert.deepEqual(page.state.picks, [updated('UP')]);
  assert.deepEqual(page.state.session.restaurants, [updated('UP')]);
  assert.deepEqual(page.renders, [[updated('UP')]]);
  assert.equal(page.up.disabled, false);
  assert.equal(page.down.disabled, false);
});

test('picks vote controller keeps only the latest opposite-thumb success and suppresses stale failure', async () => {
  const page = picksVotePage();

  const up = page.setRestaurantVote(INITIAL.id, 'UP');
  const down = page.setRestaurantVote(INITIAL.id, 'DOWN');

  assert.equal(page.calls[0].options.body, '{"restaurantId":"restaurant-123","vote":"UP"}');
  assert.equal(page.calls[1].options.body, '{"restaurantId":"restaurant-123","vote":"DOWN"}');
  assert.deepEqual(page.renders, []);
  page.pending[1].resolve(updated('DOWN'));
  await down;
  page.pending[0].reject(new Error('stale UP failure'));
  await up;

  assert.deepEqual(page.state.picks, [updated('DOWN')]);
  assert.deepEqual(page.state.session.restaurants, [updated('DOWN')]);
  assert.deepEqual(page.renders, [[updated('DOWN')]]);
  assert.deepEqual(page.mount.errors, []);
  assert.equal(page.up.disabled, false);
  assert.equal(page.down.disabled, false);
});

test('picks vote controller suppresses stale success and leaves the last confirmed state after latest failure', async () => {
  const page = picksVotePage();

  const up = page.setRestaurantVote(INITIAL.id, 'UP');
  const down = page.setRestaurantVote(INITIAL.id, 'DOWN');
  page.pending[1].resolve(updated('DOWN'));
  await down;
  page.pending[0].resolve(updated('UP'));
  await up;

  const retry = page.setRestaurantVote(INITIAL.id, 'DOWN');
  assert.deepEqual(page.state.picks, [updated('DOWN')]);
  assert.deepEqual(page.renders, [[updated('DOWN')]]);
  page.pending[2].reject(new Error('Vote service unavailable'));
  await retry;

  assert.deepEqual(page.state.picks, [updated('DOWN')]);
  assert.deepEqual(page.state.session.restaurants, [updated('DOWN')]);
  assert.deepEqual(page.renders, [[updated('DOWN')]]);
  assert.equal(page.mount.errors.length, 1);
  assert.match(page.mount.errors[0], /Vote service unavailable/);
  assert.equal(page.up.disabled, false);
  assert.equal(page.down.disabled, false);
});
