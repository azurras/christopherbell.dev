# Post Models

Owns Void post persistence and API records.

## What Lives Here

- `Post` is the Mongo-backed post and reply entity.
- `PostCreateRequest` carries create-post and create-reply input.
- `PostDetail` is the basic post response shape.
- `PostFeedItem` is the enriched feed/thread response shape.
- `PostLinkPreview` stores fetched metadata for a URL mentioned in a post.

## Design Notes

- Replies are posts with `parentId`, `rootId`, and `level`; this keeps thread
  storage simple and avoids a separate reply entity.
- `PostFeedItem` includes denormalized read-time fields such as username,
  liked-state, reply count, and stored link previews because feed rendering
  needs them together.
- Like state stores account ids so toggles are idempotent per user.
- Thread roots store reply-like extension counts separately from their own
  visible like counts so reply engagement can extend parent lifespan without
  falsifying the root post's likes.
- Reply count is computed from `rootId` and `parentId` when expiration is
  refreshed. It is not stored on `Post`, which keeps reply creation and subtree
  deletion from drifting a denormalized counter.

## Update This Doc

Update this README when post fields, thread metadata, feed DTO fields, or like
state behavior changes.
