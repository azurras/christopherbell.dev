# Notification Models

Owns notification persistence and response records.

## What Lives Here

- `Notification` is the Mongo-backed notification entity.
- `NotificationDetail` is the API response shape rendered by the nav.
- `NotificationType` constrains supported notification reasons.
- `LIKE` and `COMMENT` route users to the post or reply that caused the notification.
- WFL session fields store the session id and short invite text used by the nav.

## Design Notes

- Notifications store actor and recipient ids so display text can be resolved
  without embedding stale profile data.
- Keep type values explicit. New notification behavior should add a type instead
  of overloading message text.

## Update This Doc

Update this README when notification types, payload fields, or read behavior
changes.
