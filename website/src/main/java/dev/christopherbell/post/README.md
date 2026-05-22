# Post

Owns Void posts and feed behavior.

## What Lives Here

- Post creation, replies, thread retrieval, and feed APIs.
- Global, following, user, and current-user feeds.
- Like toggling and post expiration behavior.
- Mention detection and notification handoff.
- Stored rich metadata for HTTP and HTTPS links mentioned in posts.
- Post DTOs and persistence models under `model`.

## How It Works

- `PostController` owns the HTTP contract and keeps request parsing thin.
- `PostService` owns business rules: text validation, reply hierarchy, expiration,
  author lookup, like state, and delete authorization.
- `PostRepository` is the Mongo boundary. Service code asks it for already-sorted
  post sets instead of sorting in memory.
- `PostMapper` maps persistence entities to detail DTOs when feed-specific
  fields are not needed.
- Feed endpoints all use the same private service helpers for page sizing,
  author username lookup, current-viewer like state, and `PostFeedItem` creation.
  This keeps global, following, user, thread, and single-post views behaviorally
  consistent.

## Design Notes

- Posts store `accountId`, not username, because usernames can change. Feed
  responses resolve usernames at read time.
- Public reads tolerate anonymous callers. Missing auth only disables
  viewer-specific fields like `liked`; write operations still require a resolved
  current account.
- Expiration is enabled by default. New posts start with a 24-hour lifespan,
  each active like adds 24 hours from the post creation timestamp, and removing
  a like removes that extension. Missing expiration timestamps are repaired on
  read and during cleanup so older data remains usable.
- Replies are stored as regular posts with `parentId`, `rootId`, and `level`.
  The root id lets thread reads avoid recursive traversal.
- Reply expiration and deletion remove that reply subtree so descendants do not
  survive a missing parent. Likes on replies also extend the thread root
  lifespan by 24 hours per active reply like.
- Post creation extracts each distinct HTTP or HTTPS URL from text and stores
  fetched link preview metadata on the post. A preview fetch failure leaves the
  post intact; shared browser rendering still makes the raw URL clickable.

## Update This Doc

Update this README when feed ordering, expiration rules, reply/thread behavior, mention behavior, or post API contracts change.
