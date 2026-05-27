/**
 * Notification center page.
 *
 * Renders the full signed-in notification list while the nav keeps only the
 * three most recent items in its dropdown.
 */
import { API } from './lib/api.js';
import { authHeaders, fetchJson, formatWhen, getAuthToken, loginRedirectUrl, sanitize } from './lib/util.js';
import { notificationTargetUrl, notificationText, notificationTitle } from './lib/notifications.js';

const page = document.getElementById('notificationsPage');
const list = document.getElementById('notificationsList');
const alertBox = document.getElementById('notificationsAlert');

function showError(message) {
  if (!alertBox) return;
  alertBox.textContent = message;
  alertBox.classList.remove('d-none');
}

function clearError() {
  if (!alertBox) return;
  alertBox.textContent = '';
  alertBox.classList.add('d-none');
}

function notificationItemHtml(notification) {
  const unread = !notification.read;
  return `
    <button type="button" class="notification-center-item ${unread ? 'unread' : ''}" data-notification-id="${sanitize(notification.id || '')}" data-target-url="${sanitize(notificationTargetUrl(notification))}">
      <span class="notification-center-state">${unread ? 'Unread' : 'Read'}</span>
      <span class="notification-center-copy">
        <strong>${sanitize(notificationTitle(notification))}</strong>
        <span>${sanitize(notificationText(notification))}</span>
      </span>
      <time>${formatWhen(notification.createdOn)}</time>
    </button>`;
}

function renderNotifications(notifications) {
  if (!list) return;
  if (!Array.isArray(notifications) || notifications.length === 0) {
    list.innerHTML = `
      <div class="feed-empty-state notifications-empty-state">
        <h2>No signals yet</h2>
        <p>Likes, comments, mentions, messages, and lunch invites will appear here.</p>
      </div>`;
    return;
  }

  list.innerHTML = notifications.map(notificationItemHtml).join('');
  list.querySelectorAll('.notification-center-item').forEach(item => {
    item.addEventListener('click', async () => {
      const notificationId = item.getAttribute('data-notification-id');
      const targetUrl = item.getAttribute('data-target-url') || '/notifications';
      if (notificationId) {
        try {
          await fetchJson(API.notifications.markRead(notificationId), {
            method: 'POST',
            headers: authHeaders(),
            redirectOnUnauthorized: true,
          });
        } catch (_) {
          // Navigation is still useful even if marking read fails.
        }
      }
      window.location.href = targetUrl;
    });
  });
}

async function loadNotifications() {
  if (!page) return;
  if (!getAuthToken()) {
    window.location.href = loginRedirectUrl('/notifications');
    return;
  }
  clearError();
  try {
    const notifications = await fetchJson(`${API.notifications.base}?limit=50`, {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    renderNotifications(notifications);
  } catch (error) {
    showError(error.message || 'Could not load notifications.');
  }
}

document.addEventListener('DOMContentLoaded', loadNotifications);
