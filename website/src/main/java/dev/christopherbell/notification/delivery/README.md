# Notification Delivery

Owns notification creation from feature events.

## What Lives Here

- `NotificationDeliveryService` creates mention, like, comment, direct message,
  and WFL session notifications.
- Delivery checks `notification.preference.NotificationPreferenceService` before
  saving so disabled categories do not create in-app notifications.
- Stable event identities are claimed atomically for a bounded dedupe window.
  Expired claims can be replaced before Mongo TTL cleanup, and a failed
  notification save releases its claim so the event can be retried.

## Design Notes

Feature services should depend on this package when they need to create
notifications. Inbox reads and mark-read behavior belong in
`notification.inbox`. Category opt-in state belongs in `notification.preference`;
delivery only asks whether a notification should be stored.

