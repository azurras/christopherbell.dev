import { API } from './lib/api.js';
import { renderAlert } from './lib/status-message.js';
import { appendTextWithMentionLinks, authHeaders, fetchJson, formatWhen, isLoggedIn, sanitize, loginRedirectUrl } from './lib/util.js';

let ACTIVE_USERNAME = null;
let CONVERSATIONS = [];
let THREAD_STATE = { items: [], nextCursor: null };
let conversationSelectionGeneration = 0;
let suggestionTimer = null;
let suggestionRequest = null;
const MESSAGE_SUGGESTION_LIMIT = 8;
const MESSAGE_SUGGESTION_DEBOUNCE_MS = 200;

export function conversationRowMarkup(conversation, activeUsername) {
  const username = conversation.username || '';
  const active = username === activeUsername;
  const unread = Number(conversation.unreadCount || 0);
  const rowClasses = ['conversation-row'];
  if (active) rowClasses.push('active');
  if (unread > 0) rowClasses.push('is-unread');
  return `
      <button class="${rowClasses.join(' ')}" type="button" data-username="${sanitize(username)}">
        <span class="conversation-avatar">${sanitize((username || '?')[0].toUpperCase())}</span>
        <span class="conversation-main">
          <strong>@${sanitize(username || 'unknown')}</strong>
          <small>${sanitize(conversation.latestText || 'No message text')}</small>
        </span>
        ${unread > 0 ? `<span class="conversation-unread" aria-label="${unread} unread messages">${unread > 9 ? '9+' : unread}</span>` : ''}
      </button>`;
}

export function shouldFetchMessageSuggestions(enteredHandle) {
  return String(enteredHandle || '').trim().length > 0;
}

export function parseConversationPage(payload) {
  const validCursor = payload?.nextCursor === null
      || typeof payload?.nextCursor === 'string';
  if (!payload || !Array.isArray(payload.items) || !validCursor) {
    throw new TypeError('Server returned an invalid conversation page.');
  }
  return { items: [...payload.items], nextCursor: payload.nextCursor };
}

export function mergeOlderConversationPage(currentItems, olderPage) {
  return [...olderPage.items, ...currentItems];
}

export function messageSuggestionListMarkup(suggestions) {
  if (!Array.isArray(suggestions) || suggestions.length === 0) {
    return '<div class="message-suggestion-empty">No matching handles</div>';
  }

  return suggestions.map(suggestion => {
    const username = suggestion?.username || '';
    return `<button class="message-suggestion-option" type="button" role="option" data-username="${sanitize(username)}">
      <span class="message-suggestion-avatar">${sanitize((username || '?')[0].toUpperCase())}</span>
      <span>@${sanitize(username)}</span>
    </button>`;
  }).join('');
}

function alertBox() {
  return document.getElementById('messagesAlert');
}

function showAlert(message) {
  renderAlert(alertBox(), message);
}

function clearAlert() {
  alertBox()?.classList.add('d-none');
}

function suggestionBox() {
  return document.getElementById('recipientSuggestions');
}

function clearRecipientSuggestions() {
  const box = suggestionBox();
  if (!box) return;
  box.innerHTML = '';
  box.classList.add('d-none');
}

function renderRecipientSuggestions(suggestions) {
  const box = suggestionBox();
  if (!box) return;
  box.innerHTML = messageSuggestionListMarkup(suggestions);
  box.classList.remove('d-none');
  box.querySelectorAll('[data-username]').forEach(option => {
    option.addEventListener('click', async () => {
      const username = option.dataset.username || '';
      const input = document.getElementById('recipientHandle');
      if (input) input.value = username;
      clearRecipientSuggestions();
      await openConversation(username);
    });
  });
}

function getInitialTarget() {
  const params = new URLSearchParams(window.location.search);
  return params.get('with') || params.get('to');
}

function updateCounter() {
  const draftText = document.getElementById('messageText')?.value || '';
  const characterCount = document.getElementById('messageCount');
  if (characterCount) characterCount.textContent = `${draftText.length} / 1000`;
}

async function loadRecipientSuggestions(enteredHandle) {
  const prefix = String(enteredHandle || '').trim();
  if (!shouldFetchMessageSuggestions(prefix)) {
    clearRecipientSuggestions();
    return;
  }

  suggestionRequest?.abort();
  suggestionRequest = new AbortController();
  try {
    const suggestions = await fetchJson(API.accounts.search(prefix, MESSAGE_SUGGESTION_LIMIT), {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
      signal: suggestionRequest.signal,
    });
    renderRecipientSuggestions(suggestions || []);
  } catch (suggestionFailure) {
    if (suggestionFailure.name !== 'AbortError') clearRecipientSuggestions();
  }
}

function scheduleRecipientSuggestionLoad(enteredHandle) {
  window.clearTimeout(suggestionTimer);
  suggestionTimer = window.setTimeout(
      () => loadRecipientSuggestions(enteredHandle),
      MESSAGE_SUGGESTION_DEBOUNCE_MS);
}

function renderConversations() {
  const list = document.getElementById('conversationList');
  if (!list) return;
  if (!CONVERSATIONS.length) {
    list.innerHTML = `
      <div class="conversation-empty">
        No signals yet. Start one with a handle.
      </div>`;
    return;
  }
  list.innerHTML = CONVERSATIONS.map(conversation => conversationRowMarkup(conversation, ACTIVE_USERNAME)).join('');
  list.querySelectorAll('.conversation-row').forEach(row => {
    row.addEventListener('click', () => openConversation(row.dataset.username));
  });
}

function renderMessages(messages, scrollToEnd = true) {
  const list = document.getElementById('messageList');
  if (!list) return;
  if (!messages.length) {
    list.innerHTML = `
      <div class="feed-empty-state message-empty-state">
        <h2>No signals yet</h2>
        <p>Send the first private message in this conversation.</p>
      </div>`;
    return;
  }
  list.innerHTML = '';
  for (const message of messages) {
    const row = document.createElement('div');
    row.className = `message-bubble-row ${message.mine ? 'is-mine' : 'is-theirs'}`;
    row.innerHTML = `
      <div class="message-bubble">
        <div class="message-bubble-meta">
          <span>${message.mine ? 'You' : `@${sanitize(message.senderUsername || ACTIVE_USERNAME || 'user')}`}</span>
          <time>${formatWhen(message.createdOn)}</time>
        </div>
        <p></p>
      </div>`;
    appendTextWithMentionLinks(row.querySelector('p'), message.text || '');
    list.appendChild(row);
  }
  if (scrollToEnd) list.scrollTop = list.scrollHeight;
}

function renderConversationActions() {
  const older = document.getElementById('loadOlderMessages');
  const archive = document.getElementById('archiveConversation');
  older?.classList.toggle('d-none', !ACTIVE_USERNAME || !THREAD_STATE.nextCursor);
  archive?.classList.toggle('d-none', !ACTIVE_USERNAME);
}

async function loadConversations(selectionGeneration = null) {
  const conversations = await fetchJson(`${API.messages.conversations}?limit=30`, {
    headers: authHeaders(),
    redirectOnUnauthorized: true,
  });
  if (selectionGeneration !== null
      && selectionGeneration !== conversationSelectionGeneration) return;
  CONVERSATIONS = conversations;
  renderConversations();
}

function isCurrentConversationSelectionFor(username, selectionGeneration) {
  return ACTIVE_USERNAME === username
      && conversationSelectionGeneration === selectionGeneration;
}

async function openConversation(username) {
  if (!username) return;
  clearAlert();
  clearRecipientSuggestions();
  const selectedUsername = username.trim().replace(/^@/, '');
  const selectionGeneration = ++conversationSelectionGeneration;
  ACTIVE_USERNAME = selectedUsername;
  const title = document.getElementById('conversationTitle');
  if (title) title.textContent = `@${selectedUsername}`;
  const profileLink = document.getElementById('conversationProfileLink');
  if (profileLink) {
    profileLink.href = `/u/${encodeURIComponent(selectedUsername)}`;
    profileLink.classList.remove('d-none');
  }
  document.getElementById('messageForm')?.classList.remove('d-none');
  const olderMessagesButton = document.getElementById('loadOlderMessages');
  if (olderMessagesButton) olderMessagesButton.disabled = false;
  renderConversations();
  const page = parseConversationPage(await fetchJson(
      API.messages.conversationPage(selectedUsername, null, 50), {
    headers: authHeaders(),
    redirectOnUnauthorized: true,
  }));
  if (!isCurrentConversationSelectionFor(selectedUsername, selectionGeneration)) return;
  THREAD_STATE = page;
  renderMessages(THREAD_STATE.items);
  renderConversationActions();
  await loadConversations(selectionGeneration);
  if (!isCurrentConversationSelectionFor(selectedUsername, selectionGeneration)) return;
  renderConversations();
  const url = new URL(window.location.href);
  url.searchParams.set('with', selectedUsername);
  window.history.replaceState({}, '', url.toString());
}

async function loadOlderMessages() {
  if (!ACTIVE_USERNAME || !THREAD_STATE.nextCursor) return;
  const selectedUsername = ACTIVE_USERNAME;
  const selectionGeneration = conversationSelectionGeneration;
  const nextCursor = THREAD_STATE.nextCursor;
  const button = document.getElementById('loadOlderMessages');
  const list = document.getElementById('messageList');
  const priorHeight = list?.scrollHeight || 0;
  try {
    if (button) button.disabled = true;
    const olderPage = parseConversationPage(await fetchJson(
        API.messages.conversationPage(selectedUsername, nextCursor, 50), {
          headers: authHeaders(),
          redirectOnUnauthorized: true,
        }));
    if (!isCurrentConversationSelectionFor(selectedUsername, selectionGeneration)) return;
    THREAD_STATE = {
      items: mergeOlderConversationPage(THREAD_STATE.items, olderPage),
      nextCursor: olderPage.nextCursor,
    };
    renderMessages(THREAD_STATE.items, false);
    if (list) list.scrollTop = Math.max(0, list.scrollHeight - priorHeight);
    renderConversationActions();
  } catch (olderMessagesFailure) {
    if (isCurrentConversationSelectionFor(selectedUsername, selectionGeneration)) {
      showAlert(olderMessagesFailure?.message || 'Failed to load older messages.');
    }
  } finally {
    if (isCurrentConversationSelectionFor(selectedUsername, selectionGeneration)
        && button) button.disabled = false;
  }
}

async function archiveActiveConversation() {
  if (!ACTIVE_USERNAME) return;
  const button = document.getElementById('archiveConversation');
  try {
    if (button) button.disabled = true;
    await fetchJson(API.messages.archiveConversation(ACTIVE_USERNAME), {
      method: 'POST',
      headers: authHeaders(),
      redirectOnUnauthorized: true,
      body: '{}',
    });
    ACTIVE_USERNAME = null;
    THREAD_STATE = { items: [], nextCursor: null };
    document.getElementById('messageForm')?.classList.add('d-none');
    document.getElementById('conversationProfileLink')?.classList.add('d-none');
    const title = document.getElementById('conversationTitle');
    if (title) title.textContent = 'Pick a conversation';
    renderMessages([]);
    renderConversationActions();
    await loadConversations();
    const url = new URL(window.location.href);
    url.searchParams.delete('with');
    window.history.replaceState({}, '', url.toString());
  } catch (archiveFailure) {
    showAlert(archiveFailure?.message || 'Failed to archive conversation.');
  } finally {
    if (button) button.disabled = false;
  }
}

async function sendActiveMessage() {
  const textarea = document.getElementById('messageText');
  const button = document.getElementById('sendMessageBtn');
  const text = (textarea?.value || '').trim();
  if (!ACTIVE_USERNAME || !text) return;
  clearAlert();
  try {
    if (button) button.disabled = true;
    await fetchJson(API.messages.base, {
      method: 'POST',
      headers: authHeaders(),
      redirectOnUnauthorized: true,
      body: JSON.stringify({ recipientUsername: ACTIVE_USERNAME, text })
    });
    if (textarea) textarea.value = '';
    updateCounter();
    await openConversation(ACTIVE_USERNAME);
  } catch (sendFailure) {
    showAlert(sendFailure.message);
  } finally {
    if (button) button.disabled = false;
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  if (!isLoggedIn()) {
    window.location.href = loginRedirectUrl();
    return;
  }

  document.getElementById('messageText')?.addEventListener('input', updateCounter);
  document.getElementById('recipientHandle')?.addEventListener('input', (event) => {
    scheduleRecipientSuggestionLoad(event.target.value);
  });
  document.getElementById('messageForm')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    await sendActiveMessage();
  });
  document.getElementById('loadOlderMessages')?.addEventListener('click', loadOlderMessages);
  document.getElementById('archiveConversation')?.addEventListener(
      'click', archiveActiveConversation);
  document.getElementById('newConversationForm')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const username = document.getElementById('recipientHandle')?.value || '';
    await openConversation(username);
  });

  try {
    await loadConversations();
    const target = getInitialTarget();
    if (target) {
      const input = document.getElementById('recipientHandle');
      if (input) input.value = target.replace(/^@/, '');
      await openConversation(target);
    }
  } catch (startupFailure) {
    showAlert(startupFailure.message);
  }
});
