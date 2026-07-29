import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const {
  applyDiscoveryPage,
  createDiscoverySectionState,
  loadDiscoverySection,
  topicHref,
} = await import('../../main/resources/static/js/lib/void-discovery.js');
const { API } = await import('../../main/resources/static/js/lib/api.js');

test('discovery API builders encode opaque cursors and canonical topics', () => {
  assert.equal(
    API.posts.discovery.new('cursor + /', 24),
    '/api/posts/2026-07-28/discovery/new?size=24&cursor=cursor+%2B+%2F'
  );
  assert.equal(
    API.posts.discovery.topic('café / music', '', 12),
    '/api/posts/2026-07-28/discovery/topic/caf%C3%A9%20%2F%20music?size=12'
  );
});

test('topic links encode untrusted canonical values as one path segment', () => {
  assert.equal(topicHref('café / music'), '/void/topic/caf%C3%A9%20%2F%20music');
});

test('page state appends items and advances only from the returned cursor', () => {
  const state = createDiscoverySectionState('new');

  applyDiscoveryPage(state, { items: [{ id: 'p1' }], nextCursor: 'cursor-2' }, false);
  applyDiscoveryPage(state, { items: [{ id: 'p2' }], nextCursor: null }, true);

  assert.deepEqual(state.items.map(item => item.id), ['p1', 'p2']);
  assert.equal(state.nextCursor, null);
  assert.equal(state.error, null);
  assert.equal(state.loading, false);
});

test('one failed discovery request does not alter another section', async () => {
  const failed = createDiscoverySectionState('fading');
  const healthy = createDiscoverySectionState('new');

  await Promise.allSettled([
    loadDiscoverySection(failed, async () => { throw new Error('offline'); }),
    loadDiscoverySection(healthy, async () => ({ items: [{ id: 'p1' }], nextCursor: 'next' })),
  ]);

  assert.equal(failed.error, 'This section could not load. Try again.');
  assert.deepEqual(failed.items, []);
  assert.equal(healthy.error, null);
  assert.deepEqual(healthy.items.map(item => item.id), ['p1']);
  assert.equal(healthy.nextCursor, 'next');
});

test('discovery rendering uses DOM text nodes for API-provided topic and person text', () => {
  const source = fs.readFileSync(
    'website/src/main/resources/static/js/void-discovery.js', 'utf8');

  assert.match(source, /link\.textContent = `#\$\{topic\.display \|\| topic\.canonical\}`/);
  assert.match(source, /profile\.textContent = `@\$\{person\.username\}`/);
  assert.doesNotMatch(source, /innerHTML/);
});
