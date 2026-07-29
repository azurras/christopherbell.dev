import assert from 'node:assert/strict';
import test from 'node:test';

import {
  WFL_ANONYMOUS_SESSION_KEY,
  WFL_ANONYMOUS_SESSION_TTL_MS,
  readAnonymousWflSession,
  writeAnonymousWflSession,
} from '../../main/resources/static/js/lib/wfl-anonymous-session.js';

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value),
  };
}

test('stores only three IDs, version, and a thirty-minute expiry', () => {
  const storage = memoryStorage();
  writeAnonymousWflSession([
    { id: 'one', website: 'https://example.com', address: { latitude: 1, longitude: 2 } },
    { id: 'two' },
    { id: 'three' },
    { id: 'four' },
  ], '78701', storage, 1000);
  const raw = storage.getItem(WFL_ANONYMOUS_SESSION_KEY);
  const stored = JSON.parse(raw);

  assert.deepEqual(stored, {
    version: 3,
    restaurantIds: ['one', 'two', 'three'],
    expiresAt: 1000 + WFL_ANONYMOUS_SESSION_TTL_MS,
  });
  assert.equal(raw.includes('78701'), false);
  assert.equal(raw.includes('latitude'), false);
  assert.equal(raw.includes('website'), false);
});

test('clears expired and corrupt values', () => {
  const expired = memoryStorage({
    [WFL_ANONYMOUS_SESSION_KEY]: JSON.stringify({
      version: 2,
      restaurantIds: ['one'],
      zipCode: '78701',
      expiresAt: 999,
    }),
  });
  const corrupt = memoryStorage({ [WFL_ANONYMOUS_SESSION_KEY]: '{' });

  assert.equal(readAnonymousWflSession(expired, 1000), null);
  assert.equal(expired.getItem(WFL_ANONYMOUS_SESSION_KEY), null);
  assert.equal(readAnonymousWflSession(corrupt, 1000), null);
  assert.equal(corrupt.getItem(WFL_ANONYMOUS_SESSION_KEY), null);
});

test('canonicalizes version two records so unknown payload fields cannot persist', () => {
  const storage = memoryStorage({
    [WFL_ANONYMOUS_SESSION_KEY]: JSON.stringify({
      version: 2,
      restaurantIds: ['one'],
      zipCode: '78701',
      expiresAt: 2000,
      location: { latitude: 1, longitude: 2 },
      restaurants: [{ id: 'one', name: 'Full payload' }],
    }),
  });

  assert.deepEqual(readAnonymousWflSession(storage, 1000), {
    version: 3,
    restaurantIds: ['one'],
    expiresAt: 2000,
  });
  assert.equal(storage.getItem(WFL_ANONYMOUS_SESSION_KEY).includes('78701'), false);
  assert.equal(storage.getItem(WFL_ANONYMOUS_SESSION_KEY).includes('latitude'), false);
  assert.equal(storage.getItem(WFL_ANONYMOUS_SESSION_KEY).includes('Full payload'), false);
});

test('migrates legacy full objects while discarding coordinates and payload fields', () => {
  const storage = memoryStorage({
    [WFL_ANONYMOUS_SESSION_KEY]: JSON.stringify({
      restaurants: [{ id: 'one', name: 'Example' }, { id: 'two' }],
      location: { latitude: 1, longitude: 2 },
      zipCode: '78701-1234',
    }),
  });

  assert.deepEqual(readAnonymousWflSession(storage, 2000), {
    version: 3,
    restaurantIds: ['one', 'two'],
    expiresAt: 2000 + WFL_ANONYMOUS_SESSION_TTL_MS,
  });
  assert.equal(storage.getItem(WFL_ANONYMOUS_SESSION_KEY).includes('78701'), false);
  assert.equal(storage.getItem(WFL_ANONYMOUS_SESSION_KEY).includes('latitude'), false);
  assert.equal(storage.getItem(WFL_ANONYMOUS_SESSION_KEY).includes('name'), false);
});
