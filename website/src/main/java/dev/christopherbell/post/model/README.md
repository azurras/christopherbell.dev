# Post Models

Owns Void post persistence and API records.

## What Lives Here

- `Post` is the Mongo-backed post and reply entity.
- `PostCreateRequest` carries create-post and create-reply input.
- `PostDetail` is the basic post response shape.
- `PostFeedItem` is the enriched feed/thread response shape.

## Design Notes

- Replies are posts with `parentId`, `rootId`, and `level`; this keeps thread
  storage simple and avoids a separate reply entity.
- `PostFeedItem` includes denormalized read-time fields such as username,
  liked-state, and reply count because feed rendering needs them together.
- Like state stores account ids so toggles are idempotent per user.

## Update This Doc

Update this README when post fields, thread metadata, feed DTO fields, or like
state behavior changes.
