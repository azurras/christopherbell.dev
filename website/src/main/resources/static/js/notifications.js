/**
 * Notification center page.
 *
 * Renders the full signed-in notification list while the nav keeps only the
 * three most recent items in its dropdown.
 */
import { API } from './lib/api.js';
import { authHeaders, fetchJson, formatWhen, getAuthToken, loginRedirectUrl, sanitize } from './lib/util.js';
import {
  notificationPreferencePayload,
  mergeNotificationPages,
  notificationSettingsMarkup,
  notificationTargetUrl,
  notificationText,
  notificationTitle,
  unreadNotificationCount
} from './lib/notifications.js';

const page = document.getElementById('notificationsPage');
const list = document.getElementById('notificationsList');
const alertBox = document.getElementById('notificationsAlert');
const settingsForm = document.getElementById('notificationSettingsForm');
const settingsStatus = document.getElementById('notificationSettingsStatus');
const loadMoreButton = document.getElementById('loadMoreNotifications');
const markAllReadButton = document.getElementById('markAllNotificationsRead');
const notificationState = { items: [], nextCursor: null, loading: false };

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

function setSettingsStatus(message) {
  if (!settingsStatus) return;
  settingsStatus.textContent = message || '';
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

function renderNotificationSettings(preferences) {
  if (!settingsForm) return;
  settingsForm.innerHTML = notificationSettingsMarkup(preferences);
  settingsForm.querySelectorAll('[data-notification-setting]').forEach(input => {
    input.addEventListener('change', saveNotificationSettings);
  });
}

async function loadNotificationSettings() {
  if (!settingsForm) return;
  setSettingsStatus('Loading settings...');
  try {
    const preferences = await fetchJson(API.notifications.preferences, {
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    renderNotificationSettings(preferences);
    setSettingsStatus('');
  } catch (error) {
    setSettingsStatus(error.message || 'Could not load notification settings.');
  }
}

async function saveNotificationSettings() {
  if (!settingsForm) return;
  setSettingsStatus('Saving settings...');
  try {
    const preferences = await fetchJson(API.notifications.preferences, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(notificationPreferencePayload(settingsForm)),
      redirectOnUnauthorized: true,
    });
    renderNotificationSettings(preferences);
    setSettingsStatus('Settings saved.');
  } catch (error) {
    setSettingsStatus(error.message || 'Could not save notification settings.');
  }
}

function updateNotificationControls() {
  if (loadMoreButton) {
    loadMoreButton.classList.toggle('d-none', !notificationState.nextCursor);
    loadMoreButton.disabled = notificationState.loading;
  }
  if (markAllReadButton) {
    markAllReadButton.disabled = notificationState.loading
      || unreadNotificationCount(notificationState.items) === 0;
  }
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
          notificationState.items = notificationState.items.map(notification =>
            notification.id === notificationId ? { ...notification, read: true } : notification);
          document.dispatchEvent(new CustomEvent('notifications:changed'));
        } catch (_) {
          // Navigation is still useful even if marking read fails.
        }
      }
      window.location.href = targetUrl;
    });
  });
  updateNotificationControls();
}

async function loadNotifications(cursor = null) {
  if (!page) return;
  if (!getAuthToken()) {
    window.location.href = loginRedirectUrl('/notifications');
    return;
  }
  if (notificationState.loading) return;
  clearError();
  notificationState.loading = true;
  updateNotificationControls();
  try {
    const [notificationPage] = await Promise.all([
      fetchJson(API.notifications.page(cursor, 25), {
        headers: authHeaders(),
        redirectOnUnauthorized: true,
      }),
      cursor ? Promise.resolve() : loadNotificationSettings()
    ]);
    notificationState.items = mergeNotificationPages(
      cursor ? notificationState.items : [], notificationPage?.items || []);
    notificationState.nextCursor = notificationPage?.nextCursor || null;
    renderNotifications(notificationState.items);
  } catch (error) {
    showError(error.message || 'Could not load notifications.');
  } finally {
    notificationState.loading = false;
    updateNotificationControls();
  }
}

async function markAllRead() {
  if (notificationState.loading) return;
  notificationState.loading = true;
  updateNotificationControls();
  clearError();
  try {
    await fetchJson(API.notifications.markAllRead, {
      method: 'POST',
      headers: authHeaders(),
      redirectOnUnauthorized: true,
    });
    notificationState.items = notificationState.items.map(notification => ({
      ...notification,
      read: true
    }));
    renderNotifications(notificationState.items);
    document.dispatchEvent(new CustomEvent('notifications:changed'));
  } catch (error) {
    showError(error.message || 'Could not mark notifications read.');
  } finally {
    notificationState.loading = false;
    updateNotificationControls();
  }
}

loadMoreButton?.addEventListener('click', () => loadNotifications(notificationState.nextCursor));
markAllReadButton?.addEventListener('click', markAllRead);

document.addEventListener('DOMContentLoaded', loadNotifications);
