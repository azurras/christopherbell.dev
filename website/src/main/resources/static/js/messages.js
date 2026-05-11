import { API } from './lib/api.js';
import { authHeaders, fetchJson, formatWhen, isLoggedIn, sanitize } from './lib/util.js';

let ACTIVE_USERNAME = null;
let CONVERSATIONS = [];

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

function getInitialTarget() {
  const params = new URLSearchParams(window.location.search);
  return params.get('with') || params.get('to');
}

function updateCounter() {
  const text = document.getElementById('messageText')?.value || '';
  const count = document.getElementById('messageCount');
  if (count) count.textContent = `${text.length} / 1000`;
}

function renderConversations() {
  const list = document.getElementById('conversationList');
  if (!list) return;
  if (!CONVERSATIONS.length) {
    list.innerHTML = `
      <div class="conversation-empty">
        No messages yet.
      </div>`;
    return;
  }
  list.innerHTML = CONVERSATIONS.map(conversation => {
    const username = conversation.username || '';
    const active = username === ACTIVE_USERNAME;
    const unread = Number(conversation.unreadCount || 0);
    return `
      <button class="conversation-row ${active ? 'active' : ''}" type="button" data-username="${sanitize(username)}">
        <span class="conversation-avatar">${sanitize((username || '?')[0].toUpperCase())}</span>
        <span class="conversation-main">
          <strong>@${sanitize(username || 'unknown')}</strong>
          <small>${sanitize(conversation.latestText || 'No message text')}</small>
        </span>
        <span class="conversation-meta">
          ${unread > 0 ? `<span class="conversation-unread">${unread > 9 ? '9+' : unread}</span>` : ''}
          <small>${conversation.lastMessageOn ? formatWhen(conversation.lastMessageOn) : ''}</small>
        </span>
      </button>`;
  }).join('');
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
        <h2>No messages yet</h2>
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
    row.querySelector('p').textContent = message.text || '';
    list.appendChild(row);
  }
  list.scrollTop = list.scrollHeight;
}

async function loadConversations() {
  CONVERSATIONS = await fetchJson(`${API.messages.conversations}?limit=30`, { headers: authHeaders() });
  renderConversations();
}

async function openConversation(username) {
  if (!username) return;
  clearAlert();
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
    headers: authHeaders()
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
    window.location.href = '/login';
    return;
  }

  document.getElementById('messageText')?.addEventListener('input', updateCounter);
  document.getElementById('messageForm')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    await sendActiveMessage();
  });
  document.getElementById('newConversationForm')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const username = document.getElementById('recipientUsername')?.value || '';
    await openConversation(username);
  });

  try {
    await loadConversations();
    const target = getInitialTarget();
    if (target) {
      const input = document.getElementById('recipientUsername');
      if (input) input.value = target.replace(/^@/, '');
      await openConversation(target);
    }
  } catch (err) {
    showAlert(err.message);
  }
});
