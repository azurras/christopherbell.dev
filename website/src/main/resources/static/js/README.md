# JavaScript

Owns browser-side behavior for server-rendered pages.

## What Lives Here

- Page entry modules such as `home-feed.js`, `messages.js`, `profile.js`,
  `post.js`, `user-feed.js`, `vin-decoder.js`, `zip-coordinates.js`, and
  `whats-for-lunch.js`.
- `app.js`, which wires shared page chrome and auth-aware behavior.
- `components`, which contains reusable web components and cross-page UI pieces,
  including the auth-aware nav where Messages stays visible and routes signed-out
  users through login with a return target.
- `lib`, which contains shared API, feed, composer, infinite-scroll, and utility
  helpers.
- `auth`, which contains login, signup, forgot-password, and reset-password page
  scripts.

## How It Works

- Templates load one page entry module after `app.js`.
- Page modules should own DOM selectors for that page and delegate reusable work
  to `lib` or `components`.
- `home.js` renders the `/` homepage Signal Rail. It polls the public Void feed
  every five seconds, picks the five posts with the highest likes plus direct
  replies, and links each snapshot to that post page.
- Shared modules should not assume a specific page exists. They should accept
  selectors, callbacks, or small context objects from page modules.
- `messages.js` renders the `/messages` Signal Bridge interactions: conversation
  list state, selected private thread, safe message body rendering, character
  counter, send action, unread-first conversation rows, a handle-based
  conversation starter that avoids password-manager username heuristics,
  debounced username autocomplete against the account search API, and login
  redirect.
- `notifications.js` renders the `/notifications` Signal Log page for signed-in
  users, showing notification category settings, the full notification list, and
  routing each item through the same mark-read behavior used by the nav dropdown.
- `components/nav.js` loads notification preferences with the compact
  notification list so browser alerts skip categories the user has disabled.
- Public profile pages render API-provided activity stats through
  `lib/profile-stats.js` so counts do not depend on how many feed cards have
  been loaded in the browser.
- The Void feed toolbar keeps the primary feed filter surface to `All` and
  `Following`; its Newest and Expiring Soon sorts use time only, never keep-alive
  or reply totals. Profile and thread pages handle personal post and reply views.
- `void-discovery.js` renders the public `/void/explore` and `/void/topic/{topic}`
  surfaces. New, fading, revived, topic, and people panels load independently so
  one failed request cannot blank the page; topic and account values are inserted
  as text, and every panel owns its retry, empty, and pagination state.
- Shared feed cards present the existing Like mutation as Keep alive, apply
  server-confirmed expiration/count state before showing `+24h`, and share a
  canonical post URL through the native share sheet or clipboard fallback.
- `home-feed.js` wires the signed-in Void composer preview mount. The preview is
  rendered client-side from draft text and does not store preview-only data.
- API calls go through `lib/api.js` so auth headers, response parsing, and
  endpoint paths stay consistent.
- `lib/util.js` owns shared `@username` mention and HTTP/HTTPS URL linking;
  page modules should use it before rendering user-authored text so profile and
  external links behave consistently.
- `whats-for-lunch.js` lets visitors choose browser geolocation or a ZIP code,
  keeps cuisine and radius filters hidden behind an obvious toggle by default,
  keeps "Try 3 more" as the primary page action, groups filters, location, and
  Lunch with Friends tools into a secondary tabbed control area, loads three restaurants
  from the WFL nearby API, preserves the current three picks across page
  refreshes as a solo session, saves filters for signed-in users, creates
  shareable voting sessions for logged-in users, polls active sessions for
  restaurant/vote changes, links vote usernames to public profiles, lets session-link visitors join after authentication,
  lets signed-in users rate restaurants with whole-number buttons, lets signed-in
  users favorite restaurants, links cards to restaurant profile pages, replaces
  the card list with a loading wheel while "Try 3 more" fetches new picks, and
  only re-queries when the user clicks "Try 3 more", applies filters, changes
  ZIP/location, or an admin deletes a restaurant.
- `restaurant-profile.js` renders the public WFL restaurant profile page from
  the restaurant detail API, including aggregate rating, personal rating, and
  favorite state when the visitor is signed in.
- `zip-coordinates.js` renders the Tools ZIP coordinate lookup page around
  `GET /api/location/zip/{zipCode}`, including ZIP normalization, inline errors,
  result fields, and copyable API/curl output.
- `canes-box-tracker.js` renders the Tools Raising Canes Box Index page around
  the public history API, including month-over-month and year-over-year percent
  indexes, verified latest average price, data-quality counts, metro sample
  source/Central-time collection date, clickable metro trend selection, a
  copyable official GraphQL API `curl` for the selected tracked store, and
  lightweight inline SVG trend charts for both the overall index and selected
  metros.
- `post.js` renders the `/p/{id}` Spectral Thread page. It loads the selected
  post and thread data, fills root/parent context echoes, applies selected-post
  detail styling through the shared feed renderer, renders the nested Signal
  Rail and previous/next thread links through `lib/thread-navigation.js`, wires
  newest-reply jumping and collapsible reply branches, wires the compact reply
  composer, and renders replies as a timeline.
- Feed-rendering pages initialize `lib/image-lightbox.js` so direct image and
  animated GIF links open in a shared preview dialog and broken external images
  fall back to a source link instead of leaving empty space. Preview images and
  fallback source links activate only for absolute HTTP(S) values; malformed,
  relative, protocol-relative, and other-scheme values stay non-clickable.
- Feed-rendering pages initialize `lib/lazy-media.js` after rendering post
  cards so rich iframes defer their `src` until they are near the viewport.
- `wfl-list.js` renders the WFL secondary pages for favorites and the public top
  10 rated restaurants.
- `user-feed.js` renders public profiles and exposes signed-in mute/block
  actions for other users.
- `back-office.js` gates the Back Office to admins, renders report/user queues
  with repeat-report context, supports user status changes/role
  promotion and shared-folder capability controls, and exposes practical admin operations such as Location Census ZIP
  coordinate import, WFL import/dedupe, Raising Canes Box Index collection and
  datapoint review, and vehicle VIN maintenance.
- `command-center.js` gates the public data-free `/command-center` shell with a
  fresh account-role check, then renders protected host metrics, explicit
  stale/unavailable states, 15-minute sparklines, delayed logs, and challenged
  fixed recovery actions. It polls at five seconds with bounded backoff, pauses
  while the tab is hidden, serializes overlapping requests, and uses abortable
  generations so stale snapshot or log responses cannot overwrite newer state.
- Command-center log filters invalidate the cursor generation; literal search is
  debounced, pause suppresses log reads, and clear affects only visible rows.
  Logs are appended with DOM `textContent`, copied from visible row text, and
  never rendered as HTML. Server reset responses replace the visible tail.
- The native action dialog obtains a fresh challenge before opening and clears
  the password, confirmation phrase, required phrase, status, and challenge on
  every close or cancel path. Only restart site, restart computer, and shut down
  computer are challenge buttons; cancellation appears only for a cancellable
  pending machine action.
- `lib/command-center.js` owns pure metric formatting, unavailable semantics,
  polling/backoff decisions, cursor-generation decisions, text-only log copying,
  dialog clearing, and countdown helpers. Any 401/403—including one from a stale
  request generation—tears down and hides the console before redirecting.
- `shared-folder.js` owns the Shared Folder shell: it redirects visitors without a browser session marker,
  checks the current account's effective shared read capability, renders relative-path breadcrumbs
  and accessible button controls, copies same-origin `/shared?path=` links, starts native
  attachment downloads and hands audio/video selections to the site-wide player without Blob buffering,
  and inserts text previews only
  with `textContent`. Before assigning a protected native URL it waits for the root-scoped
  shared-folder service worker to control the page; 401 redirects to login and 403/revocation is
  shown inline. The worker handles only the exact versioned shared-folder API prefix and forwards the original
  cookie-authenticated request with its `Range` and credentials mode intact and
  `cache: 'no-store'`; it never receives, stores, or attaches a bearer token.
  Folder navigation replaces only the breadcrumbs, toolbar, and entry list. Its semantic global-search form validates the
  recursive response before rendering, labels each result with its parent path as text, reports server-side result caps,
  cancels stale requests, and restores the active folder when cleared. Browser history restores folders without a page
  reload, and a navigation generation guard prevents slower stale responses from replacing the
  latest folder.
  Text and native-stream 401/403 responses use one actionable
  access-loss handler. Its root bootstrap script is intentionally public for exact anonymous
  `GET /shared-folder-auth-sw.js` so installation can happen before login; all
  shared-folder API requests stay protected. Write-capable users can create folders, rename, move,
  explicitly replace, and delete entries with observed tokens. The same module owns resumable
  8 MiB chunk uploads, SHA-256 chunk digests, progress, cancel, drag/drop, explicit replacement,
  and refresh recovery from non-secret local session metadata; terminal sessions clear that local
  record. The shared nav adds Shared Folder to the alphabetized Tools menu only after the
  current-account API reports effective read access.
- `components/site-media-player.js` owns the single audio/video element in the top document. It
  renders accessible adaptive controls in a fixed bottom bar, shows video pixels there, and reuses
  the existing authenticated direct-stream and progressive transcoding paths. It stores only the
  current file descriptor, timestamp, play state, speed, mute state, and volume in same-tab
  `sessionStorage`; it never stores a token or prepared stream URL. Refresh restores and seeks the
  secure source before attempting to continue playback. If autoplay policy blocks that attempt,
  the next page gesture resumes it without losing the saved playing intent. Audio tags provide an
  optional title, artist, album, embedded cover, and Media Session presentation; missing or invalid
  tags leave the filename and music-note fallback intact. Radio completion immediately syncs and
  starts the server-selected next track; close, item completion, logout, malformed state, and
  access loss clear the record. While media is active, ordinary
  same-origin link clicks load a full-page content frame above the original document, so existing
  server-rendered pages and their scripts keep working without replacing the player. The top URL,
  title, Back/Forward history, logout, and access-revocation behavior remain synchronized. External,
  download, new-tab, modifier, hash-only, and API links retain browser-owned behavior.
- `music.js` and `lib/music.js` own the access-aware `/music` hub. They validate catalog, queue,
  and playlist responses before rendering; expose catalog playback, the global station and queue,
  favorites, radio exclusions, shared playlists/history, and writer-only metadata editing with
  undo; fetch All Music, Favorites, and playlists as stable 50-track server pages with full-result
  totals and facets; and delegate every track to the existing top-document media owner. Catalog
  page changes replace only result DOM and never constrain the server-owned radio pool or replace
  the active media node. The player expands on
  `/music` and compacts on other routes by changing presentation only, so the active media node is
  never replaced during navigation.

## Design Notes

- Keep page modules thin. If two pages need the same behavior, move the behavior
  into `lib` or `components` instead of copying it.
- Avoid broad global state. Prefer module-local state and explicit callbacks.
- Keep browser alerts out of new shared code when practical; page modules should
  render errors into page-specific alert containers.
- Browser-side regression tests live under `website/src/test/js` and run through
  `./gradlew :website:jsTest` without adding an npm workflow.

## Update This Doc

Update this README when browser module ownership, shared entry behavior, or
frontend directory structure changes.
