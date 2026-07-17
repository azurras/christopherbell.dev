# Frontend Library

Owns reusable browser-side modules that are not tied to one page.

## What Lives Here

- `api.js` defines API paths, including grouped admin-facing routes used by the
  Back Office, account username search used by Messages autocomplete, account
  trust routes, hidden-thread routes, and public Tool APIs such as Raising Canes
  Box Index history.
- `util.js` owns auth storage helpers, request headers, JSON response handling,
  formatting, sanitizing, shared mention/URL linking, and small DOM utilities.
- `shared-folder-streaming.js` defines the exact shared-folder API scope, request cloning that
  preserves `Range` while attaching the per-client JWT in the service worker, worker readiness,
  logout/401 token cleanup, and actionable 401/403 streaming states. Worker forwarding uses
  `cache: 'no-store'`; it never places bearer data in a URL. Its root worker script is installed
  through the exact public `GET /shared-folder-auth-sw.js` bootstrap route, not through a public
  shared-folder API prefix.
- `composer.js` initializes reusable post composer behavior.
- `composer-preview.js` turns draft post text into a live, sanitized preview
  using the same mention linking and rich embed rendering as feed cards.
- `feed-context.js` builds the context object used by feed rendering, including
  the current page's hide-thread action.
- `feed-render.js` renders post cards, mention/external links, stored rich link
  previews, allowlisted rich media/cards, grouped direct image embeds, shared
  lifespan countdowns, expiry removal motion, likes, replies, menus, and inline
  reply composers.
- `image-lightbox.js` owns the shared post-image preview dialog and broken-image
  fallback markup used by feed-rendering pages.
- `infinite.js` owns reusable cursor-based infinite scrolling.
- `lazy-media.js` owns deferred iframe markup and viewport-based activation for
  rich media embeds.
- `notifications.js` owns notification display text, notification routing, recent
  dropdown limiting, browser-notification selection helpers, and pure helpers
  for rendering/reading notification category toggles.
- `profile-stats.js` normalizes safe public profile activity counts before page
  modules render them.
- `thread-navigation.js` builds the post-detail Signal Rail model and markup
  from the full thread payload without depending on page DOM state.
- `util.js` contains small formatting and escaping helpers.

## How It Works

- Page modules compose these helpers instead of importing from each other.
- Shared helpers receive callbacks for page-specific actions, which keeps
  dependency direction from page -> library.
- Feed rendering is intentionally centralized so post cards behave the same on
  home, profile, user feed, and thread pages.
- Feed card menus show the hide-thread action only when the page context
  provides the callback and the visitor is signed in.
- Browser notification helpers accept the saved preference response. Missing
  preferences mean all categories are enabled, matching backend defaults.

## Design Notes

- Keep helper modules small and dependency-light. If a module starts needing page
  selectors, move that code back into a page entry module.
- Prefer explicit context objects over hidden imports for behavior that varies by
  page, such as delete permissions or reply handlers.
- Shared modules should be safe to import on any page.
- `getAuthToken` normalizes the stored JWT, rejects expired or malformed tokens,
  and clears unusable values so the app never sends an empty or stale
  `Authorization: Bearer` header.
- `getAuthClaims` exposes decoded JWT claims for non-authoritative UI decisions
  such as whether a page should render signed-in controls. Server endpoints must
  still enforce permissions.
- `authHeaders` accepts optional extra headers and merges them with the
  normalized bearer token so page modules do not hand-build auth headers.
- `fetchJson` clears stale auth state on `401` but only redirects when the caller
  passes `redirectOnUnauthorized: true`. Public tools and nav background fetches
  should not opt into redirects.
- `currentRedirectTarget`, `safeRedirectTarget`, and `loginRedirectUrl` preserve
  the page a visitor was trying to use before authentication. They only allow
  same-origin paths and fall back to `/` from auth pages to avoid redirect loops.
- `linkMentions` and `appendTextWithMentionLinks` escape user-authored text,
  convert valid `@username` mentions into `/u/{username}` profile links, and
  make HTTP/HTTPS URLs clickable external links.
- Composer previews are browser-only renderings of the current draft. They do
  not create stored preview metadata; submit still sends only the trimmed text.
- Shared lifespan timers use server-returned `expiresOn`. Like handlers must
  retarget countdowns from the updated feed item instead of applying browser-side
  extension math.
- Shared feed chips only label posts as `Expires soon` during their final
  12 hours.
- Shared feed rendering detects allowlisted rich URLs in post text and stored
  link previews, deduplicates them, and renders richer UI while keeping the
  original text URL clickable. Supported rich providers are YouTube
  `youtube-nocookie.com` embeds, direct image URLs with an image extension or
  explicit image-format query such as `format=jpg` or `fm=webp`, direct animated
  GIF URLs such as `.gif`, `format=gif`, or `fm=gif`, Spotify embeds, SoundCloud
  widget embeds, and first-party GitHub repository/issue/pull-request cards.
- Direct image and animated GIF rich embeds render as a grouped image grid. GIFs
  keep browser-native animation through `<img>` and receive a visible `GIF`
  badge. Page modules that render feed cards should call
  `initPostImageLightbox()` once so image triggers open the shared dialog and
  image load failures can show the fallback source link.
- Rich iframe embeds render with `data-src` first. Page modules that render feed
  cards should call `initLazyMedia(root)` after cards are appended so iframes
  activate near the viewport, with an immediate fallback when
  `IntersectionObserver` is unavailable.
- Thread navigation helpers accept the already-loaded thread feed items and
  return previous/next links plus a sanitized nested Signal Rail. They also
  expose newest-reply and collapsed-branch helpers so page modules can add
  thread controls without rebuilding thread maps inline.

## Update This Doc

Update this README when shared frontend helper responsibilities, feed rendering
contracts, API helper behavior, or infinite-scroll behavior changes.
