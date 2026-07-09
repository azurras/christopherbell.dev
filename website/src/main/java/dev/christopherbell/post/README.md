# Post

Owns Void posts and feed behavior.

## What Lives Here

- `PostController` owns the HTTP contract and keeps request parsing thin.
- `PostService` is a facade that keeps controller-facing methods stable.
- Post creation and reply persistence live under `creation`.
- Global, following, user, and current-user feeds live under `feed`.
- Single-post and thread retrieval live under `thread`.
- Like toggling and delete authorization live under `interaction`.
- Lifespan calculation, expiration repair, reply synchronization, and cleanup live under `expiration`.
- Per-account hidden thread roots live under `hide`.
- Stored rich metadata for HTTP and HTTPS links mentioned in posts lives under `preview`.
- The `/p/{id}` post detail page, which renders a single-column Spectral Thread
  view for the selected post, its root/parent context, compact reply composer,
  and direct replies.
- Post DTOs and persistence models under `model`.

## How It Works

- `PostService` delegates business rules to subfeature services so creation,
  feeds, threads, interactions, expiration, and link previews can change independently.
- `PostRepository` is the Mongo boundary. Service code asks it for already-sorted
  post sets instead of sorting in memory.
- The post document declares targeted Mongo indexes for global/user feeds,
  account reply counts, thread reads, parent reply counts, and expiration
  cleanup. Production rollout should allow time for these indexes to build on
  existing collections.
- `PostMapper` maps persistence entities to detail DTOs when feed-specific
  fields are not needed.
- Feed endpoints use the feed service for page sizing,
  author username lookup, current-viewer like state, and `PostFeedItem` creation.
  This keeps global, following, user, thread, and single-post views behaviorally
  consistent.
- Signed-in global, following, and public user feed reads omit posts from
  muted/blocked accounts and omit any thread root the viewer has hidden.

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
  post intact; shared browser rendering still makes the raw URL clickable. The
  shared feed renderer upgrades allowlisted providers into richer cards without
  changing stored post text: YouTube uses `youtube-nocookie.com`, direct image
  URLs render as a lazy grouped image grid when the URL has a supported image
  extension or an explicit image-format query such as `format=jpg` or
  `fm=webp`, Spotify and SoundCloud use provider iframe embeds, and GitHub
  repository/issue/pull-request links render as first-party styled anchor cards.
  Feed pages initialize a shared image lightbox so direct image links can be
  previewed in-place and broken external images keep a source link available.
- The Void composer preview is browser-only and reuses the shared mention/link
  and rich media rendering logic. It does not persist preview-only data; post
  creation still stores the submitted text and server-resolved link previews.
- Post detail pages reuse the shared feed renderer so actions, author links,
  link previews, expiration state, and delete permissions stay consistent with
  feed cards. Page-specific JavaScript adds quieter root/parent "context echoes"
  above the selected post, highlights the selected post, renders a nested Signal
  Rail from the existing full-thread payload, adds previous/next thread
  navigation, lets readers jump to the newest reply, and lets readers collapse
  or expand reply branches without changing the API contract.
- Feed-card menus can hide a thread for the current account. The hidden-thread
  package stores the root post id, so hiding a reply removes that whole thread
  from later feed reads.

## Update This Doc

Update this README when feed ordering, expiration rules, reply/thread behavior, mention behavior, post detail rendering, or post API contracts change.
