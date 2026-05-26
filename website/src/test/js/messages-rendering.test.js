import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.document = {
  addEventListener() {}
};

const { conversationRowMarkup } = await import('../../main/resources/static/js/messages.js');

test('conversation row prioritizes unread state over timestamps', () => {
  const markup = conversationRowMarkup({
    username: 'jessica',
    latestText: 'hello',
    unreadCount: 3,
    lastMessageOn: '2026-05-19T19:20:27Z'
  }, null);

  assert.match(markup, /conversation-row is-unread/);
  assert.match(markup, /conversation-unread/);
  assert.match(markup, />3</);
  assert.doesNotMatch(markup, /2026/);
  assert.doesNotMatch(markup, /conversation-meta/);
});
