import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

globalThis.document = {
  addEventListener() {}
};

const { conversationRowMarkup, messageSuggestionListMarkup, shouldFetchMessageSuggestions } =
  await import('../../main/resources/static/js/messages.js');

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
