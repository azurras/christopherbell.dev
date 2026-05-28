# Post Interaction

Owns user actions on existing posts.

## What Lives Here

- `PostInteractionService` toggles likes, sends like notifications, updates expiration, synchronizes reply expiration, and deletes authorized post subtrees.

## Design Notes

Authorization inputs come from the facade so this service stays focused on post state changes.

