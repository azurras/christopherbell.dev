import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const pageInitializer = [];
const conversationRows = ['alice', 'bob'].map(username => ({
  dataset: { username },
  addEventListener(eventName, callback) {
    if (eventName === 'click') {
      this.click = callback;
    }
  },
}));
const conversationList = {
  innerHTML: '',
  querySelectorAll() {
    return conversationRows;
  },
};
const renderedMessageList = [];
const messageList = {
  scrollHeight: 0,
  scrollTop: 0,
  get children() {
    return renderedMessageList;
  },
  set innerHTML(markup) {
    if (markup === '') {
      renderedMessageList.length = 0;
    }
  },
  appendChild(row) {
    renderedMessageList.push(row);
    this.scrollHeight += 1;
  },
};
const pageNodes = new Map();
function createPageNode() {
  const paragraph = {
    renderedText: '',
    set textContent(text) {
      this.renderedText = text;
    },
    appendChild(textNode) {
      this.renderedText += textNode.textContent;
    },
  };
  const node = {
    disabled: false,
    href: '',
    textContent: '',
    classList: {
      add() {},
      remove() {},
      toggle() {},
    },
    addEventListener(eventName, callback) {
      this.listeners ||= new Map();
      this.listeners.set(eventName, callback);
    },
    click() {
      return this.listeners?.get('click')?.();
    },
    querySelector() {
      return paragraph;
    },
  };
  node.value = '';
  return node;
}

for (const elementId of [
  'conversationTitle', 'conversationProfileLink', 'messageForm', 'messageList',
  'loadOlderMessages', 'archiveConversation', 'conversationList', 'messagesAlert',
  'recipientSuggestions', 'messageText', 'messageCount', 'sendMessageBtn',
  'recipientHandle', 'newConversationForm',
]) {
  pageNodes.set(elementId, createPageNode());
}
pageNodes.set('conversationList', conversationList);
pageNodes.set('messageList', messageList);

let currentUrl = new URL('http://localhost/messages');
globalThis.window = {
  get location() {
    return {
      get href() { return currentUrl.toString(); },
      get pathname() { return currentUrl.pathname; },
      get search() { return currentUrl.search; },
      origin: currentUrl.origin,
    };
  },
  history: {
    replaceState(_state, _title, updatedUrl) {
      currentUrl = new URL(updatedUrl);
    },
  },
  clearTimeout() {},
  setTimeout() { return 1; },
};
globalThis.document = {
  cookie: 'CBELL_AUTH_STATE=present',
  addEventListener(eventName, callback) {
    if (eventName === 'DOMContentLoaded') {
      pageInitializer.push(callback);
    }
  },
  getElementById(elementId) {
    return pageNodes.get(elementId) || null;
  },
  createElement() {
    const element = createPageNode();
    element.innerHTML = '';
    return element;
  },
  createTextNode(textContent) {
    return { textContent };
  },
};

const pendingPageRequests = [];
globalThis.fetch = (requestUrl, options) => {
  if (String(requestUrl).includes('/conversations?limit=30')) {
    return Promise.resolve(responseWithPayload([
      { username: 'alice' },
      { username: 'bob' },
    ]));
  }
  return new Promise((resolve, reject) => pendingPageRequests.push({
    requestUrl: String(requestUrl), options, resolve, reject,
  }));
};

function responseWithPayload(payload) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ success: true, payload }),
  };
}

function conversationPage(messageText, nextCursor = null) {
  return responseWithPayload({
    items: [{
      id: messageText,
      mine: true,
      senderUsername: 'member',
      text: messageText,
      createdOn: '2026-01-01T00:00:00Z',
    }],
    nextCursor,
  });
}

function pendingPageFor(username, cursor = null) {
  return pendingPageRequests.filter(request => request.requestUrl.includes(
      `/conversation/${username}?size=50${cursor ? `&cursor=${cursor}` : ''}`)).at(-1);
}

function renderedTexts() {
  return renderedMessageList.map(row => row.querySelector().renderedText);
}

const {
  conversationRowMarkup,
  mergeOlderConversationPage,
  messageSuggestionListMarkup,
  parseConversationPage,
  shouldFetchMessageSuggestions,
} = await import('../../main/resources/static/js/messages.js');
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

test('conversation selection ignores a late first page from another recipient', async () => {
  await pageInitializer[0]();

  const aliceFirstPage = conversationRows[0].click();
  const bobFirstPage = conversationRows[1].click();
  const bobPageRequest = pendingPageFor('bob');
  bobPageRequest.resolve(conversationPage('message from bob', 'bob-next'));
  await bobFirstPage;
  pendingPageFor('alice').resolve(conversationPage('message from alice', 'alice-next'));
  await aliceFirstPage;

  assert.equal(pageNodes.get('conversationTitle').textContent, '@bob');
  assert.equal(pageNodes.get('conversationProfileLink').href, '/u/bob');
  assert.equal(currentUrl.searchParams.get('with'), 'bob');
  assert.deepEqual(renderedTexts(), ['message from bob']);
});

test('conversation selection ignores a late older page from another recipient', async () => {
  const aliceSelection = conversationRows[0].click();
  pendingPageFor('alice', null).resolve(conversationPage('alice current message', 'alice-older'));
  await aliceSelection;

  const olderAlicePage = pageNodes.get('loadOlderMessages').click();
  const bobSelection = conversationRows[1].click();
  pendingPageFor('bob', null).resolve(conversationPage('bob current message', 'bob-older'));
  await bobSelection;
  pendingPageFor('alice', 'alice-older').resolve(conversationPage('stale alice older message'));
  await olderAlicePage;

  assert.equal(pageNodes.get('conversationTitle').textContent, '@bob');
  assert.equal(pageNodes.get('conversationProfileLink').href, '/u/bob');
  assert.equal(currentUrl.searchParams.get('with'), 'bob');
  assert.deepEqual(renderedTexts(), ['bob current message']);
  assert.equal(pageNodes.get('loadOlderMessages').disabled, false);

  const aliceErrorSelection = conversationRows[0].click();
  pendingPageFor('alice', null).resolve(conversationPage('alice current message', 'alice-error'));
  await aliceErrorSelection;
  const failedOlderAlicePage = pageNodes.get('loadOlderMessages').click();
  const latestBobSelection = conversationRows[1].click();
  pendingPageFor('bob', null).resolve(conversationPage('latest bob message'));
  await latestBobSelection;
  pendingPageFor('alice', 'alice-error').reject(new Error('stale Alice request failed'));
  await failedOlderAlicePage;

  assert.deepEqual(renderedTexts(), ['latest bob message']);
  assert.equal(pageNodes.get('messagesAlert').textContent, '');
});

test('archiving an old selection preserves the newer selected conversation', async () => {
  const aliceSelection = conversationRows[0].click();
  pendingPageFor('alice').resolve(conversationPage('alice before archive'));
  await aliceSelection;
  const archive = pageNodes.get('archiveConversation').click();
  const pendingArchive = pendingPageRequests.at(-1);
  assert.match(pendingArchive.requestUrl, /alice\/archive$/);
  const bobSelection = conversationRows[1].click();
  pendingPageFor('bob').resolve(conversationPage('bob remains visible'));
  await bobSelection;
  pendingArchive.resolve(responseWithPayload({}));
  await archive;
  assert.equal(pageNodes.get('conversationTitle').textContent, '@bob');
  assert.equal(currentUrl.searchParams.get('with'), 'bob');
  assert.deepEqual(renderedTexts(), ['bob remains visible']);
});

test('send completion preserves a newer draft and does not reload another selection', async () => {
  const aliceSelection = conversationRows[0].click();
  pendingPageFor('alice').resolve(conversationPage('alice before send'));
  await aliceSelection;
  const textarea = pageNodes.get('messageText');
  textarea.value = 'submitted to alice';
  const send = pageNodes.get('messageForm').listeners.get('submit')({ preventDefault() {} });
  const pendingSend = pendingPageRequests.at(-1);
  assert.equal(JSON.parse(pendingSend.options.body).recipientUsername, 'alice');
  const bobSelection = conversationRows[1].click();
  pendingPageFor('bob').resolve(conversationPage('bob remains selected'));
  await bobSelection;
  textarea.value = 'new draft for bob';
  const requestCount = pendingPageRequests.length;
  pendingSend.resolve(responseWithPayload({}));
  // Permit assertions even if the old implementation starts an unwanted reload.
  await new Promise(resolve => setImmediate(resolve));
  const extraRequests = pendingPageRequests.slice(requestCount);
  extraRequests.forEach(request => request.resolve(conversationPage('unwanted reload')));
  await send;
  assert.equal(textarea.value, 'new draft for bob');
  assert.equal(extraRequests.length, 0);
  assert.deepEqual(renderedTexts(), ['bob remains selected']);
});

test('send refresh failures remain visible for the current selection', async () => {
  const selection = conversationRows[0].click();
  pendingPageFor('alice').resolve(conversationPage('alice before refresh failure'));
  await selection;
  pageNodes.get('messageText').value = 'submitted';
  const send = pageNodes.get('messageForm').listeners.get('submit')({ preventDefault() {} });
  pendingPageRequests.at(-1).resolve(responseWithPayload({}));
  await new Promise(resolve => setImmediate(resolve));
  pendingPageFor('alice').reject(new Error('current refresh failed'));
  await send;
  assert.equal(pageNodes.get('messagesAlert').textContent, 'current refresh failed');
});

test('send preserves edits to the draft in the same conversation', async () => {
  const selection = conversationRows[0].click();
  pendingPageFor('alice').resolve(conversationPage('alice before another draft'));
  await selection;
  const textarea = pageNodes.get('messageText');
  textarea.value = 'submitted';
  const send = pageNodes.get('messageForm').listeners.get('submit')({ preventDefault() {} });
  const pendingSend = pendingPageRequests.at(-1);
  textarea.value = 'another draft';
  textarea.listeners.get('input')();
  pendingSend.resolve(responseWithPayload({}));
  await new Promise(resolve => setImmediate(resolve));
  pendingPageFor('alice').resolve(conversationPage('sent message'));
  await send;
  assert.equal(textarea.value, 'another draft');
});

test('archive refresh failures remain visible after clearing the selection', async () => {
  const selection = conversationRows[0].click();
  pendingPageFor('alice').resolve(conversationPage('alice before archive refresh failure'));
  await selection;
  const archive = pageNodes.get('archiveConversation').click();
  const pendingArchive = pendingPageRequests.at(-1);
  const originalFetch = globalThis.fetch;
  globalThis.fetch = () => Promise.reject(new Error('current conversation list failed'));
  try {
    pendingArchive.resolve(responseWithPayload({}));
    await archive;
    assert.equal(pageNodes.get('messagesAlert').textContent, 'current conversation list failed');
  } finally {
    globalThis.fetch = originalFetch;
  }
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
