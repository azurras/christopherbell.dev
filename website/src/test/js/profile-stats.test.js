import assert from 'node:assert/strict';
import test from 'node:test';

import { profileActivityStats } from '../../main/resources/static/js/lib/profile-stats.js';

test('profileActivityStats returns public activity and network counts', () => {
  assert.deepEqual(profileActivityStats({
    postCount: 3,
    replyCount: 5,
    followerCount: 7,
    followingCount: 11
  }), {
    postCount: 3,
    replyCount: 5,
    followerCount: 7,
    followingCount: 11
  });
});

test('profileActivityStats falls back to zero for missing or invalid counts', () => {
  assert.deepEqual(profileActivityStats({
    postCount: null,
    replyCount: -1,
    followerCount: 'not-a-number',
    followingCount: undefined
  }), {
    postCount: 0,
    replyCount: 0,
    followerCount: 0,
    followingCount: 0
  });
});
