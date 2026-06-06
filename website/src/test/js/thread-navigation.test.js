import assert from 'node:assert/strict';
import test from 'node:test';

import {
  newestReplyInThread,
  renderThreadNavigation,
  replyIdsWithChildren,
  threadNavigationModel,
  visibleThreadAfterCollapsedBranches
} from '../../main/resources/static/js/lib/thread-navigation.js';

const thread = [
  { id: 'root', username: 'rootUser', text: 'Root signal', level: 0 },
  { id: 'reply-1', parentId: 'root', username: 'alice', text: 'First reply', level: 1 },
  { id: 'reply-2', parentId: 'root', username: 'bob', text: 'Second reply', level: 1 },
  { id: 'deep-1', parentId: 'reply-2', username: 'chris', text: 'Nested branch', level: 2 }
];

test('threadNavigationModel returns previous and next posts by thread order', () => {
  const model = threadNavigationModel(thread, 'reply-2');

  assert.equal(model.previous.id, 'reply-1');
  assert.equal(model.next.id, 'deep-1');
  assert.equal(model.nodes.length, 4);
  assert.equal(model.nodes[2].selected, true);
  assert.equal(model.nodes[3].depth, 2);
});

test('threadNavigationModel handles missing, empty, and single-post threads', () => {
  assert.deepEqual(threadNavigationModel([], 'missing'), {
    previous: null,
    next: null,
    nodes: []
  });

  const single = threadNavigationModel([{ id: 'root', text: 'Only post' }], 'root');
  assert.equal(single.previous, null);
  assert.equal(single.next, null);
  assert.equal(single.nodes[0].selected, true);
});

test('renderThreadNavigation highlights the selected post and escapes snippets', () => {
  const markup = renderThreadNavigation([
    { id: 'root', username: '<root>', text: '<Root signal>', level: 0 },
    { id: 'reply', parentId: 'root', username: 'alice', text: 'A reply', level: 1 }
  ], 'root');

  assert.match(markup, /class="void-thread-map-item is-selected"/);
  assert.match(markup, /aria-current="true"/);
  assert.match(markup, /&lt;root&gt;/);
  assert.match(markup, /&lt;Root signal&gt;/);
  assert.match(markup, /href="\/p\/reply"/);
});

test('renderThreadNavigation returns an empty string without navigable thread data', () => {
  assert.equal(renderThreadNavigation([], 'root'), '');
});

test('newestReplyInThread returns the newest non-root reply by creation timestamp', () => {
  const newest = newestReplyInThread([
    { id: 'root', text: 'Root', createdOn: '2026-06-02T10:00:00Z' },
    { id: 'reply-old', parentId: 'root', createdOn: '2026-06-02T10:03:00Z' },
    { id: 'reply-new', parentId: 'root', createdOn: '2026-06-02T10:05:00Z' },
    { id: 'reply-updated', parentId: 'root', createdOn: '2026-06-02T10:01:00Z', lastUpdatedOn: '2026-06-02T10:20:00Z' }
  ]);

  assert.equal(newest.id, 'reply-new');
});

test('newestReplyInThread falls back to lastUpdatedOn and returns null without replies', () => {
  const newest = newestReplyInThread([
    { id: 'root', text: 'Root', createdOn: '2026-06-02T10:00:00Z' },
    { id: 'reply-old', parentId: 'root', lastUpdatedOn: '2026-06-02T10:03:00Z' },
    { id: 'reply-new', parentId: 'root', lastUpdatedOn: '2026-06-02T10:05:00Z' }
  ]);

  assert.equal(newest.id, 'reply-new');
  assert.equal(newestReplyInThread([{ id: 'root', text: 'Root' }]), null);
});

test('visibleThreadAfterCollapsedBranches hides descendants of collapsed posts', () => {
  const visible = visibleThreadAfterCollapsedBranches(thread, new Set(['reply-2']));

  assert.deepEqual(visible.map(post => post.id), ['root', 'reply-1', 'reply-2']);
});

test('visibleThreadAfterCollapsedBranches preserves order and carries hidden descendants forward', () => {
  const visible = visibleThreadAfterCollapsedBranches([
    { id: 'root' },
    { id: 'a', parentId: 'root' },
    { id: 'a-1', parentId: 'a' },
    { id: 'a-1-1', parentId: 'a-1' },
    { id: 'b', parentId: 'root' }
  ], new Set(['a']));

  assert.deepEqual(visible.map(post => post.id), ['root', 'a', 'b']);
});

test('replyIdsWithChildren returns reply ids that have descendants', () => {
  const ids = replyIdsWithChildren(thread);

  assert.deepEqual([...ids].sort(), ['reply-2']);
});
