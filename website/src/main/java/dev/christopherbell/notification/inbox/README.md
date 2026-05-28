# Notification Inbox

Owns notification reads and read-state updates.

## What Lives Here

- `NotificationInboxService` lists the current user's notifications, counts unread notifications, and marks notifications read.

## Design Notes

Controller endpoints that serve the notification center should depend on this package. Notification creation belongs in `notification.delivery`.
