import { API } from './lib/api.js';
import { appendTextWithMentionLinks, authHeaders, fetchJson, formatWhen, isLoggedIn, sanitize, loginRedirectUrl } from './lib/util.js';

let ACTIVE_USERNAME = null;
let CONVERSATIONS = [];
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

export function shouldFetchMessageSuggestions(value) {
  return String(value || '').trim().length > 0;
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
  const alert = alertBox();
  if (!alert) return;
  alert.textContent = message;
  alert.classList.remove('d-none');
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
  const text = document.getElementById('messageText')?.value || '';
  const count = document.getElementById('messageCount');
  if (count) count.textContent = `${text.length} / 1000`;
}

async function loadRecipientSuggestions(value) {
  const prefix = String(value || '').trim();
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
  } catch (err) {
    if (err.name !== 'AbortError') clearRecipientSuggestions();
  }
}

function scheduleRecipientSuggestionLoad(value) {
  window.clearTimeout(suggestionTimer);
  suggestionTimer = window.setTimeout(
      () => loadRecipientSuggestions(value),
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

function renderMessages(messages) {
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
  list.scrollTop = list.scrollHeight;
}

async function loadConversations() {
  CONVERSATIONS = await fetchJson(`${API.messages.conversations}?limit=30`, {
    headers: authHeaders(),
    redirectOnUnauthorized: true,
  });
  renderConversations();
}

async function openConversation(username) {
  if (!username) return;
  clearAlert();
  clearRecipientSuggestions();
  ACTIVE_USERNAME = username.trim().replace(/^@/, '');
  const title = document.getElementById('conversationTitle');
  if (title) title.textContent = `@${ACTIVE_USERNAME}`;
  const profileLink = document.getElementById('conversationProfileLink');
  if (profileLink) {
    profileLink.href = `/u/${encodeURIComponent(ACTIVE_USERNAME)}`;
    profileLink.classList.remove('d-none');
  }
  document.getElementById('messageForm')?.classList.remove('d-none');
  renderConversations();
  const messages = await fetchJson(`${API.messages.conversation(ACTIVE_USERNAME)}?limit=100`, {
    headers: authHeaders(),
    redirectOnUnauthorized: true,
  });
  renderMessages(messages || []);
  await loadConversations();
  renderConversations();
  const url = new URL(window.location.href);
  url.searchParams.set('with', ACTIVE_USERNAME);
  window.history.replaceState({}, '', url.toString());
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
  } catch (err) {
    showAlert(err.message);
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
  } catch (err) {
    showAlert(err.message);
  }
});
