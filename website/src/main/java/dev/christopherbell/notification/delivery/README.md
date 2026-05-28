# Notification Delivery

Owns notification creation from feature events.

## What Lives Here

- `NotificationDeliveryService` creates mention, like, comment, direct message, and WFL session notifications.

## Design Notes

Feature services should depend on this package when they need to create notifications. Inbox reads and mark-read behavior belong in `notification.inbox`.

