import assert from 'node:assert/strict';
import test from 'node:test';

import { sortVoidFeedItems } from '../../main/resources/static/js/lib/feed-sort.js';

test('newest ordering ignores keep-alive and reply totals', () => {
  const olderPopular = {
    id: 'older',
    createdOn: '2026-07-28T10:00:00Z',
    likesCount: 500,
    replyCount: 500
  };
  const newerQuiet = {
    id: 'newer',
    createdOn: '2026-07-28T11:00:00Z',
    likesCount: 0,
    replyCount: 0
  };

  assert.deepEqual(
      sortVoidFeedItems([olderPopular, newerQuiet], 'newest').map(post => post.id),
      ['newer', 'older']);
});

test('expiring ordering uses expiration time only', () => {
  const laterPopular = {
    id: 'later',
    expiresOn: '2026-07-29T12:00:00Z',
    likesCount: 500
  };
  const soonerQuiet = {
    id: 'sooner',
    expiresOn: '2026-07-29T11:00:00Z',
    likesCount: 0
  };

  assert.deepEqual(
      sortVoidFeedItems([laterPopular, soonerQuiet], 'expiring').map(post => post.id),
      ['sooner', 'later']);
});
