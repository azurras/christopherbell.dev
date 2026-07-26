import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

globalThis.document = {
  addEventListener() {}
};

const {
  conversationRowMarkup,
  mergeOlderConversationPage,
  messageSuggestionListMarkup,
  parseConversationPage,
  shouldFetchMessageSuggestions,
} =
  await import('../../main/resources/static/js/messages.js');
const { API } = await import('../../main/resources/static/js/lib/api.js');

test('conversation page URL encodes user cursor and bounded size', () => {
  assert.equal(
      API.messages.conversationPage('alex name', 'cursor/value', 50),
      '/api/messages/2026-07-26/conversation/alex%20name?size=50&cursor=cursor%2Fvalue');
  assert.equal(
      API.messages.archiveConversation('alex name'),
      '/api/messages/2026-07-26/conversation/alex%20name/archive');
});

test('conversation page boundary validates and prepends older chronological messages', () => {
  const current = [{ id: 'm3' }, { id: 'm4' }];
  const page = parseConversationPage({ items: [{ id: 'm1' }, { id: 'm2' }], nextCursor: 'next' });

  assert.deepEqual(mergeOlderConversationPage(current, page), [
    { id: 'm1' }, { id: 'm2' }, { id: 'm3' }, { id: 'm4' },
  ]);
  assert.throws(() => parseConversationPage({ items: null }), /invalid conversation page/i);
});

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

test('conversation starter avoids browser password-manager username heuristics', () => {
  const template = readFileSync('website/src/main/resources/templates/messages.html', 'utf8');

  assert.match(template, /id="recipientHandle"/);
  assert.match(template, /autocomplete="off"/);
  assert.match(template, /id="recipientSuggestions"/);
  assert.match(template, /role="listbox"/);
  assert.doesNotMatch(template, /id="recipientUsername"/);
  assert.doesNotMatch(template, /for="recipientUsername"/);
  assert.match(template, /id="loadOlderMessages"/);
  assert.match(template, /id="archiveConversation"/);
});

test('messageSuggestionListMarkup renders safe clickable username options', () => {
  const markup = messageSuggestionListMarkup([
    { username: 'alice' },
    { username: '<bad>' }
  ]);

  assert.match(markup, /data-username="alice"/);
  assert.match(markup, />@alice</);
  assert.match(markup, /&lt;bad&gt;/);
  assert.match(markup, /role="option"/);
});

test('messageSuggestionListMarkup renders empty state for no matches', () => {
  assert.match(messageSuggestionListMarkup([]), /No matching handles/);
});

test('shouldFetchMessageSuggestions requires a non-blank handle prefix', () => {
  assert.equal(shouldFetchMessageSuggestions(''), false);
  assert.equal(shouldFetchMessageSuggestions('   '), false);
  assert.equal(shouldFetchMessageSuggestions('a'), true);
});
