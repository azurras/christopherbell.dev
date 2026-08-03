import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} };
globalThis.document = { cookie: '', getElementById: () => null };
globalThis.window = { location: { origin: 'https://www.christopherbell.dev' } };

class FakeElement {
  constructor(dataset = {}) {
    this.dataset = dataset;
    this.disabled = false;
  }

  closest(selector) {
    return selector === '.wfl-list-vote' ? this : null;
  }
}
globalThis.Element = FakeElement;

const listModule = await import('../../main/resources/static/js/wfl-list.js')
  .catch(cause => ({ loadFailure: cause }));

const INITIAL = Object.freeze({
  id: 'restaurant-123',
  name: 'Taco Place',
  upVotes: 2,
  downVotes: 1,
  voteCount: 3,
  myVote: 'UP',
  myFavorite: true,
});

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, reject, resolve };
}

function createPage(mode, authenticated = true) {
  const calls = [];
  const pending = [];
  const up = new FakeElement({ restaurantId: INITIAL.id, vote: 'UP' });
  const down = new FakeElement({ restaurantId: INITIAL.id, vote: 'DOWN' });
  const card = {
    dataset: { restaurantId: INITIAL.id },
    errors: [],
    insertAdjacentHTML(_position, html) { this.errors.push(html); },
  };
  const mount = {
    dataset: {
      listMode: mode,
      listTitle: mode === 'favorites' ? 'Favorite Restaurants' : 'Top 10 Liked',
    },
    html: '',
    listener: null,
    set innerHTML(value) { this.html = value; },
    get innerHTML() { return this.html; },
    addEventListener(_type, listener) { this.listener = listener; },
    insertAdjacentHTML(_position, html) { this.html += html; },
    querySelectorAll(selector) {
      if (selector === '.wfl-list-vote') return [up, down];
      if (selector === '.wfl-list-card') return [card];
      return [];
    },
  };
  const request = async (url, options = {}) => {
    calls.push({ options, url });
    if (url.endsWith('/freshness')) return null;
    if (url.includes('/favorites') || url.includes('/top-liked')) return [INITIAL];
    if (url.endsWith('/vote')) {
      const next = deferred();
      pending.push(next);
      return next.promise;
    }
    throw new Error(`Unexpected request: ${url}`);
  };

  return {
    calls,
    card,
    down,
    initialize: () => listModule.initializeWflList({
      mount,
      claims: () => authenticated ? { sub: 'browser-session' } : null,
      request,
      headers: (extra = {}) => extra,
      loginUrl: value => `/login?return=${encodeURIComponent(value)}`,
    }),
    mount,
    pending,
    up,
  };
}

function assertPressed(html, vote) {
  assert.match(
    html,
    new RegExp(`data-vote="${vote}"[^>]+aria-pressed="true"`),
  );
}

test('anonymous Top Liked stays public without controls or per-restaurant detail overfetch', async () => {
  assert.ifError(listModule.loadFailure);
  assert.equal(typeof listModule.initializeWflList, 'function');
  const page = createPage('top-liked', false);

  await page.initialize();

  assert.deepEqual(
    page.calls.map(call => call.url),
    [
      '/api/whatsforlunch/restaurant/2026-07-26/freshness',
      '/api/whatsforlunch/restaurant/2026-05-17/top-liked?limit=10',
    ],
  );
  assert.doesNotMatch(page.mount.innerHTML, /data-vote|lunch-vote-control/);
  assert.match(page.mount.innerHTML, /67% liked · 2 up · 1 down/);
  assert.match(page.mount.innerHTML, /Details/);
  assert.equal(page.calls.some(call => call.url.includes('/profile/')), false);
});

test('Favorites and authenticated Top Liked apply only the latest deferred click response', async () => {
  assert.ifError(listModule.loadFailure);
  for (const mode of ['favorites', 'top-liked']) {
    const page = createPage(mode);
    await page.initialize();
    assert.match(page.mount.innerHTML, /role="group"[^>]+aria-label="Vote on Taco Place"/);
    assertPressed(page.mount.innerHTML, 'UP');

    const firstClick = page.mount.listener({ target: page.up });
    const secondClick = page.mount.listener({ target: page.down });

    assert.equal(page.calls.at(-2).options.body,
      '{"restaurantId":"restaurant-123","vote":"UP"}');
    assert.equal(page.calls.at(-1).options.body,
      '{"restaurantId":"restaurant-123","vote":"DOWN"}');
    assertPressed(page.mount.innerHTML, 'UP');
    assert.equal(page.up.disabled, true);
    assert.equal(page.down.disabled, true);

    page.pending[1].resolve({
      ...INITIAL,
      upVotes: 2,
      downVotes: 2,
      voteCount: 4,
      myVote: 'DOWN',
    });
    await secondClick;
    page.pending[0].resolve({
      ...INITIAL,
      upVotes: 3,
      downVotes: 1,
      voteCount: 4,
      myVote: 'UP',
    });
    await firstClick;

    assertPressed(page.mount.innerHTML, 'DOWN');
    assert.match(page.mount.innerHTML, /50% liked · 2 up · 2 down/);
    assert.deepEqual(page.card.errors, []);
    assert.equal(page.up.disabled, false);
    assert.equal(page.down.disabled, false);
  }
});

test('Favorites and authenticated Top Liked restore controls and confirmed state after a local error', async () => {
  assert.ifError(listModule.loadFailure);
  for (const mode of ['favorites', 'top-liked']) {
    const page = createPage(mode);
    await page.initialize();

    const click = page.mount.listener({ target: page.down });
    assertPressed(page.mount.innerHTML, 'UP');
    page.pending[0].reject(new Error('Vote service unavailable'));
    await click;

    assertPressed(page.mount.innerHTML, 'UP');
    assert.match(page.card.errors[0], /Vote service unavailable/);
    assert.equal(page.up.disabled, false);
    assert.equal(page.down.disabled, false);
  }
});
