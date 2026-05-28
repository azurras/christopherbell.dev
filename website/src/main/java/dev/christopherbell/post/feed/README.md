# Post Feed

Owns feed-style post lists.

## What Lives Here

- `PostFeedService` serves global, user, current-user, following, and account-id post lists.
- Feed reads repair expiration metadata and omit expired posts.

## Design Notes

Keep feed pagination and `PostFeedItem` construction here so all feed list endpoints stay consistent.

