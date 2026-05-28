# Post Creation

Owns creating root posts and replies.

## What Lives Here

- `PostCreationService` validates post text, resolves the author, builds reply hierarchy fields, stores link previews, saves posts, refreshes root expiration after replies, and sends mention/comment notifications.

## Design Notes

Creation accepts a current-user resolver from the facade so input validation can happen before authentication is resolved. This preserves validation behavior for bad request payloads while authenticated write paths still require a real user id.

