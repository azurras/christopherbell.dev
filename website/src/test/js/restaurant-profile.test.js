import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} };
globalThis.document = { cookie: '', getElementById: () => null };
globalThis.window = {
  location: {
    origin: 'https://www.christopherbell.dev',
    pathname: '/wfl/restaurants/restaurant-123',
  },
};

const profileModule = await import('../../main/resources/static/js/restaurant-profile.js');
const { initializeRestaurantProfile } = profileModule;

function requireInitializer() {
  assert.equal(
    typeof initializeRestaurantProfile,
    'function',
    'restaurant profile must export its progressive-enhancement boundary',
  );
}

function mount(id = 'restaurant-123') {
  return {
    dataset: { restaurantId: id },
    innerHTML: '<p>Sign in to rate or favorite this restaurant.</p>',
    listener: null,
    addEventListener(_type, listener) {
      this.listener = listener;
    },
    insertAdjacentHTML(_position, html) {
      this.innerHTML += html;
    },
  };
}

const DETAIL = Object.freeze({
  id: 'restaurant-123',
  name: 'Taco Place',
  ratingCount: 2,
  ratingSum: 9,
  myRating: 4,
  myFavorite: true,
});

test('anonymous profile keeps server fallback and makes no detail request', async () => {
  requireInitializer();
  const memberMount = mount();
  let requests = 0;

  await initializeRestaurantProfile({
    mount: memberMount,
    claims: () => null,
    request: async () => {
      requests += 1;
      return DETAIL;
    },
  });

  assert.equal(requests, 0);
  assert.match(memberMount.innerHTML, /Sign in to rate or favorite/);
});

test('signed-in profile renders only personal controls from the detail response', async () => {
  requireInitializer();
  const memberMount = mount();
  const urls = [];

  await initializeRestaurantProfile({
    mount: memberMount,
    claims: () => ({ sub: 'browser-session' }),
    request: async (url) => {
      urls.push(url);
      return DETAIL;
    },
    headers: () => ({ 'X-Test': 'yes' }),
  });

  assert.deepEqual(
    urls,
    ['/api/whatsforlunch/restaurant/2026-05-17/profile/restaurant-123'],
  );
  assert.match(memberMount.innerHTML, /Your rating: 4\/5/);
  assert.match(memberMount.innerHTML, /Favorited/);
  assert.doesNotMatch(memberMount.innerHTML, /100 Main|Phone|Website|Source type/);
});

test('member load failure stays local and preserves the anonymous fallback', async () => {
  requireInitializer();
  const memberMount = mount();

  await initializeRestaurantProfile({
    mount: memberMount,
    claims: () => ({ sub: 'browser-session' }),
    request: async () => {
      throw new Error('Service unavailable');
    },
  });

  assert.match(memberMount.innerHTML, /Sign in to rate or favorite/);
  assert.match(memberMount.innerHTML, /Service unavailable/);
});

test('stale browser session restores sign-in fallback without an error banner', async () => {
  requireInitializer();
  const memberMount = mount();
  const unauthorized = Object.assign(new Error('Authentication required.'), { status: 401 });

  await initializeRestaurantProfile({
    mount: memberMount,
    claims: () => ({ sub: 'browser-session' }),
    request: async () => {
      throw unauthorized;
    },
  });

  assert.match(memberMount.innerHTML, /Sign in to rate or favorite/);
  assert.doesNotMatch(memberMount.innerHTML, /alert-danger|Authentication required/);
});

test('rating interaction sends the existing mutation contract', async () => {
  requireInitializer();
  const memberMount = mount();
  const publicRating = { textContent: '' };
  const calls = [];

  await initializeRestaurantProfile({
    mount: memberMount,
    publicRating,
    claims: () => ({ sub: 'browser-session' }),
    request: async (url, options = {}) => {
      calls.push([url, options]);
      return calls.length === 1
        ? DETAIL
        : { ...DETAIL, myRating: 5, ratingCount: 3, ratingSum: 14 };
    },
    headers: (extra = {}) => extra,
  });
  await memberMount.listener({
    target: {
      closest: selector => selector === '.lunch-rating-button'
        ? { dataset: { rating: '5' } }
        : null,
    },
  });

  assert.equal(calls[1][0], '/api/whatsforlunch/restaurant/2026-05-17/rating');
  assert.equal(calls[1][1].method, 'PUT');
  assert.equal(calls[1][1].body, '{"restaurantId":"restaurant-123","rating":5}');
  assert.match(memberMount.innerHTML, /Your rating: 5\/5/);
  assert.equal(publicRating.textContent, '4.7/5 from 3 ratings');
});

test('favorite interaction preserves the existing method and payload contract', async () => {
  requireInitializer();
  const memberMount = mount();
  const calls = [];

  await initializeRestaurantProfile({
    mount: memberMount,
    claims: () => ({ sub: 'browser-session' }),
    request: async (url, options = {}) => {
      calls.push([url, options]);
      return calls.length === 1 ? DETAIL : { ...DETAIL, myFavorite: false };
    },
    headers: (extra = {}) => extra,
  });
  await memberMount.listener({
    target: {
      closest: selector => selector === '.restaurant-favorite-toggle' ? {} : null,
    },
  });

  assert.equal(calls[1][0], '/api/whatsforlunch/restaurant/2026-05-17/favorite');
  assert.equal(calls[1][1].method, 'DELETE');
  assert.equal(calls[1][1].body, '{"restaurantId":"restaurant-123"}');
  assert.match(memberMount.innerHTML, /\bFavorite\b/);
  assert.doesNotMatch(memberMount.innerHTML, /Favorited/);
});
