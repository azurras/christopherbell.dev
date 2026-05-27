# Notification

Owns in-app notifications.

## What Lives Here

- Mention notifications created from posts and replies using case-insensitive username lookup.
- Like notifications created when another member likes a user's post.
- Comment notifications created when another member replies to a user's post.
- WFL session invite notifications that route invited members back to the shared session.
- Message notifications created from direct messages.
- Unread counts, notification listing, and mark-read behavior.
- The notification center page and nav dropdown consume the same notification API.
- Notification models under `model`.

## Delivery Triggers

- Mentions scan post text for valid `@username` tokens and skip duplicate or self mentions.
- Likes notify the post owner only when the action is a new like. Removing a like does not create a notification.
- Comments notify the direct parent post owner when the reply author is someone else.
- Messages notify the recipient when a direct message is created.
- WFL session invites notify each invited member and link them back to the session.

## Update This Doc

Update this README when notification types, unread-count logic, delivery triggers, or notification API responses change.
