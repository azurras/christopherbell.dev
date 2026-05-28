import assert from 'node:assert/strict';
import test from 'node:test';

const {
  browserNotificationsToShow,
  notificationTargetUrl,
  notificationText,
  notificationTitle,
  recentNotifications
} = await import('../../main/resources/static/js/lib/notifications.js');

test('notification titles describe like and comment activity', () => {
  assert.equal(
    notificationTitle({ notificationType: 'LIKE', actorUsername: 'reader' }),
    '@reader liked your post'
  );
  assert.equal(
    notificationTitle({ notificationType: 'COMMENT', actorUsername: 'reader' }),
    '@reader commented on your post'
  );
});

test('notification text uses the post, message, or WFL payload', () => {
  assert.equal(notificationText({ notificationType: 'LIKE', postText: 'post text' }), 'post text');
  assert.equal(notificationText({ notificationType: 'MESSAGE', messageText: 'hello' }), 'hello');
  assert.equal(
    notificationText({ notificationType: 'WFL_SESSION', whatsForLunchSessionText: 'vote now' }),
    'vote now'
  );
});

test('notification target routes users to the right detail page', () => {
  assert.equal(notificationTargetUrl({ notificationType: 'MESSAGE', actorUsername: 'friend' }), '/messages?with=friend');
  assert.equal(notificationTargetUrl({ notificationType: 'WFL_SESSION', whatsForLunchSessionId: 'session-1' }), '/wfl?session=session-1');
  assert.equal(notificationTargetUrl({ notificationType: 'LIKE', postId: 'post-1' }), '/p/post-1');
  assert.equal(notificationTargetUrl({ notificationType: 'COMMENT', postId: 'reply-1' }), '/p/reply-1');
});

test('recent notifications caps the nav dropdown at three items', () => {
  const notifications = [
    { id: '1' },
    { id: '2' },
    { id: '3' },
    { id: '4' }
  ];

  assert.deepEqual(recentNotifications(notifications).map(notification => notification.id), ['1', '2', '3']);
});

test('browser notification selection skips read, seen, and missing-id notifications', () => {
  const notifications = [
    { id: 'new-unread', read: false },
    { id: 'seen-unread', read: false },
    { id: 'read', read: true },
    { read: false }
  ];

  assert.deepEqual(
    browserNotificationsToShow(notifications, new Set(['seen-unread'])).map(notification => notification.id),
    ['new-unread']
  );
});
