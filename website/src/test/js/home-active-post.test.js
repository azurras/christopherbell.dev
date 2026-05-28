import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.document = {
  addEventListener() {}
};

const {
  ACTIVE_POST_REFRESH_MS,
  SIGNAL_RAIL_LIMIT,
  activeScore,
  signalRailMarkup,
  selectMostActivePosts
} = await import('../../main/resources/static/js/home.js');

test('homepage active rail refreshes every few seconds', () => {
  assert.equal(ACTIVE_POST_REFRESH_MS, 5000);
});

test('homepage signal rail shows five posts', () => {
  assert.equal(SIGNAL_RAIL_LIMIT, 5);
});

test('activeScore counts likes and replies as activity', () => {
  assert.equal(activeScore({ likesCount: 4, replyCount: 3 }), 7);
  assert.equal(activeScore({ likesCount: null, replyCount: undefined }), 0);
});

test('selectMostActivePosts returns the five highest activity posts and breaks ties by update time', () => {
  const older = {
    id: 'older',
    likesCount: 2,
    replyCount: 1,
    lastUpdatedOn: '2026-05-25T12:00:00Z',
    createdOn: '2026-05-25T11:00:00Z'
  };
  const quieter = {
    id: 'quiet',
    likesCount: 1,
    replyCount: 0,
    lastUpdatedOn: '2026-05-25T13:00:00Z'
  };
  const newerTie = {
    id: 'newer',
    likesCount: 3,
    replyCount: 0,
    lastUpdatedOn: '2026-05-25T14:00:00Z'
  };
  const posts = [
    older,
    quieter,
    newerTie,
    { id: 'fourth', likesCount: 0, replyCount: 2, lastUpdatedOn: '2026-05-25T13:30:00Z' },
    { id: 'fifth', likesCount: 0, replyCount: 1, lastUpdatedOn: '2026-05-25T13:20:00Z' },
    { id: 'sixth', likesCount: 0, replyCount: 0, lastUpdatedOn: '2026-05-25T15:00:00Z' }
  ];

  assert.deepEqual(selectMostActivePosts(posts).map(post => post.id), [
    'newer',
    'older',
    'fourth',
    'fifth',
    'quiet'
  ]);
});

test('signalRailMarkup renders five linked post snapshots', () => {
  const posts = Array.from({ length: 5 }).map((_, index) => ({
    id: `post-${index + 1}`,
    username: `voiduser${index + 1}`,
    text: `This is live post ${index + 1}.`,
    likesCount: 5 - index,
    replyCount: index
  }));
  const markup = signalRailMarkup(posts);

  assert.match(markup, /Signal Rail/);
  assert.match(markup, /@voiduser1/);
  assert.match(markup, /This is live post 5\./);
  assert.match(markup, /5 likes/);
  assert.match(markup, /4 replies/);
  assert.match(markup, /href="\/p\/post-1"/);
  assert.match(markup, /href="\/p\/post-5"/);
  assert.doesNotMatch(markup, /home-void-signal-rank/);
});
