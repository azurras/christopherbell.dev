/**
 * Shared notification display and routing helpers.
 *
 * The nav dropdown, notification center, and browser notifications all use
 * these helpers so new notification types route and read the same way.
 */

const DEFAULT_RECENT_LIMIT = 3;
const NOTIFICATION_PREFERENCE_LABELS = [
  ['mentions', 'Mentions'],
  ['likes', 'Likes'],
  ['comments', 'Comments'],
  ['messages', 'Messages'],
  ['wflSessions', 'WFL sessions']
];

function actorLabel(notification) {
  return notification?.actorUsername ? `@${notification.actorUsername}` : 'Someone';
}

/** Human-readable notification title for UI and browser notification surfaces. */
export function notificationTitle(notification) {
  const actor = actorLabel(notification);
  switch (notification?.notificationType) {
    case 'LIKE':
      return `${actor} liked your post`;
    case 'COMMENT':
      return `${actor} commented on your post`;
    case 'MESSAGE':
      return `${actor} sent you a message`;
    case 'WFL_SESSION':
      return `${actor} invited you to lunch`;
    case 'MENTION':
    default:
      return `${actor} mentioned you`;
  }
}

/** Short notification body text pulled from the type-specific payload. */
export function notificationText(notification) {
  if (!notification) return '';
  if (notification.notificationType === 'MESSAGE') {
    return notification.messageText || '';
  }
  if (notification.notificationType === 'WFL_SESSION') {
    return notification.whatsForLunchSessionText || '';
  }
  return notification.postText || '';
}

/** Local URL users should land on when they open a notification. */
export function notificationTargetUrl(notification) {
  if (!notification) return '/notifications';
  if (notification.notificationType === 'MESSAGE' && notification.actorUsername) {
    return `/messages?with=${encodeURIComponent(notification.actorUsername)}`;
  }
  if (notification.notificationType === 'WFL_SESSION' && notification.whatsForLunchSessionId) {
    return `/wfl?session=${encodeURIComponent(notification.whatsForLunchSessionId)}`;
  }
  if (notification.postId) {
    return `/p/${encodeURIComponent(notification.postId)}`;
  }
  return '/notifications';
}

/** First notifications shown in the compact nav panel. */
export function recentNotifications(notifications, limit = DEFAULT_RECENT_LIMIT) {
  return Array.isArray(notifications) ? notifications.slice(0, limit) : [];
}

/** Unread notifications that have not already been shown by the browser API. */
export function browserNotificationsToShow(notifications, seenIds, preferences = null) {
  const seen = seenIds instanceof Set ? seenIds : new Set();
  return (Array.isArray(notifications) ? notifications : [])
      .filter(notification => notification?.id
          && !notification.read
          && !seen.has(notification.id)
          && notificationTypeEnabled(notification.notificationType, preferences));
}

/** Markup for the notification category settings form. */
export function notificationSettingsMarkup(preferences = {}) {
  return NOTIFICATION_PREFERENCE_LABELS.map(([key, label]) => {
    const checked = preferences[key] !== false ? ' checked' : '';
    return `<label class="notification-setting-toggle">
      <span>${label}</span>
      <input type="checkbox" data-notification-setting="${key}"${checked}>
    </label>`;
  }).join('');
}

/** Read notification category settings from a rendered settings root. */
export function notificationPreferencePayload(root) {
  return Object.fromEntries(NOTIFICATION_PREFERENCE_LABELS.map(([key]) => {
    const input = root?.querySelector?.(`[data-notification-setting="${key}"]`);
    return [key, !!input?.checked];
  }));
}

function notificationTypeEnabled(notificationType, preferences) {
  if (!preferences) return true;
  const key = {
    MENTION: 'mentions',
    LIKE: 'likes',
    COMMENT: 'comments',
    MESSAGE: 'messages',
    WFL_SESSION: 'wflSessions'
  }[notificationType];
  return key ? preferences[key] !== false : true;
}
