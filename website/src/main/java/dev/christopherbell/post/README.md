# Post

Owns Void posts and feed behavior.

## What Lives Here

- Post creation, replies, thread retrieval, and feed APIs.
- Global, following, user, and current-user feeds.
- Like toggling and post expiration behavior.
- Mention detection and notification handoff.
- Stored rich metadata for HTTP and HTTPS links mentioned in posts.
- The `/p/{id}` post detail page, which renders a single-column Spectral Thread
  view for the selected post, its root/parent context, compact reply composer,
  and direct replies.
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
  current account. Suspended accounts cannot create new posts, which prevents
  already-issued sessions from posting after suspension.
- Expiration is enabled by default. New root posts start with a 24-hour
  lifespan. Root likes, every reply in the thread, and every active like on a
  reply each add another 24 hours from the root post creation timestamp.
  Removing a like removes that like's extension. Missing or stale expiration
  timestamps are repaired on read and during cleanup so older data remains
  usable.
- Replies are stored as regular posts with `parentId`, `rootId`, and `level`.
  The root id lets thread reads avoid recursive traversal.
- Replies inherit the thread root expiration so every nested reply lives exactly
  as long as the parent thread. Reads repair older reply documents that still
  carry their own shorter expiration. Any reply creation or like change that
  extends or shortens the root lifespan synchronizes that expiration across the
  full reply tree. Reply deletion still removes that reply subtree so descendants
  do not survive a missing parent.
- Post creation extracts each distinct HTTP or HTTPS URL from text and stores
  fetched link preview metadata on the post. A preview fetch failure leaves the
  post intact; shared browser rendering still makes the raw URL clickable.
- Post detail pages reuse the shared feed renderer so actions, author links,
  link previews, expiration state, and delete permissions stay consistent with
  feed cards. Page-specific JavaScript adds quieter root/parent "context echoes"
  above the selected post and highlights the selected post without changing the
  API contract.

## Update This Doc

Update this README when feed ordering, expiration rules, reply/thread behavior, mention behavior, post detail rendering, or post API contracts change.
