# JavaScript

Owns browser-side behavior for server-rendered pages.

## What Lives Here

- `survive.js` renders the Java-owned shared survival world and serializes commands; it owns no game rules. See the `survive` Java package README for the API and lifetime contract.
- The viewport vitals card shows an accepted partner's HP and TP from the same game snapshot. Invitations, ended parties and older snapshots without vitals clear the partner row.
- `survive-world-materials.js` paints the adventurer's eyes, brows and lips on the mirrored face chart when staging the shared actor atlas. Keep the paint coordinates paired with `adventurer.json` and `authored-actors.jpg` when changing those assets.

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
  redirect. A selection-generation guard prevents late history responses from
  replacing the conversation that is currently selected. Archive and send
  completions use the same selection guard. Sending clears only the submitted
  draft when its text and input generation are unchanged, preserving newer typing.
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
  Infinite-feed load failures appear in the page alert, clear an empty feed's
  loading skeleton, and remain retryable. The shared infinite-scroll helper
  rejects malformed non-null cursors before changing pagination state.
- `user-feed.js` reports infinite-feed request failures in its accessible alert;
  later scroll or reload attempts can retry the failed page.
- API calls go through `lib/api.js` so auth headers, response parsing, and
  endpoint paths stay consistent.
- `lib/util.js` owns shared `@username` mention and HTTP/HTTPS URL linking;
  page modules should use it before rendering user-authored text so profile and
  external links behave consistently.
- `whats-for-lunch.js` lets visitors choose browser geolocation or a ZIP code,
  keeps cuisine and radius filters hidden behind an obvious toggle by default,
  keeps "Try 3 more" as the primary page action, groups filters, location, and
  Lunch with Friends tools into a secondary tabbed control area, loads three restaurants
  from the WFL nearby API, preserves anonymous picks for at most 30 minutes as
  restaurant IDs plus an optional ZIP (never coordinates or full restaurant
  payloads), saves filters for signed-in users, creates
  shareable voting sessions for logged-in users, polls active sessions for
  restaurant/vote changes, links vote usernames to public profiles, lets session-link visitors join after authentication,
  lets signed-in users set `UP` or `DOWN` restaurant votes with accessible thumb
  controls, lets signed-in users favorite restaurants, links cards to restaurant profile pages, replaces
  the card list with a loading wheel while "Try 3 more" fetches new picks, shows
  explicitly opened archived shared sessions as read-only, discards archived
  sessions restored implicitly from saved browser state, starts fresh picks when
  selection controls are used from an archive, permits only the host to request
  new picks in an active shared session, and only re-queries when the user clicks
  "Try 3 more", applies filters, changes
  ZIP/location, or an admin deletes a restaurant.
- Restaurant profiles render their complete public content and validated
  HTTP(S) website links on the server. `restaurant-profile.js` makes no
  anonymous detail request; for signed-in visitors it progressively adds only
  personal vote and favorite controls, with failures isolated to that panel.
- `zip-coordinates.js` renders the Tools ZIP coordinate lookup page around
  `GET /api/location/zip/{zipCode}`, including ZIP normalization, inline errors,
  result fields, and copyable API/curl output. Clipboard failures are reported
  in the page alert region.
- `vin-decoder.js` validates VIN length and allowed characters in the browser
  before sending a decode request, then renders summary, JSON, and curl output.
  Its JSON/curl copy controls report clipboard failures in the page alert region.
- `canes-box-tracker.js` renders the Tools Raising Canes Box Index page around
  the public history API, including month-over-month and year-over-year percent
  indexes, verified latest average price, data-quality counts, metro sample
  source/Central-time collection date, clickable metro trend selection, a
  copyable official GraphQL API `curl` for the selected tracked store, and
  lightweight inline SVG trend charts for both the overall index and selected
  metros. Copy failures, including unavailable Clipboard API access, appear in
  the page alert region.
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
- `wfl-list.js` renders the WFL secondary pages for favorites and the public Top
  10 Liked list, whose server order is approval percentage, vote count, then
  stable restaurant ID. Signed-in members receive accessible binary vote
  controls on both lists. Each card owns its pending/error state and ignores
  stale responses from earlier clicks; anonymous Top Liked uses only the public
  list response and never fetches per-restaurant details.
- `user-feed.js` renders public profiles and exposes signed-in mute/block
  actions for other users.
- `back-office.js` gates the Back Office to admins, renders report/user queues
  with repeat-report context, supports user status changes/role
  promotion and shared-folder capability controls, and exposes practical admin operations such as Location Census ZIP
  coordinate import, paged/searchable WFL inventory and duplicate previews,
  WFL import, Raising Canes Box Index collection and
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
  and accessible button controls, copies same-origin `/shared?path=` links with actionable
  live-status feedback when the Clipboard API is unavailable or rejects the write, starts native
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

## Website Monitor

`site-monitor.js` owns the private pilot workspace at `/site-monitor`, using
`API.siteMonitor` and shared auth/CSRF fetch helpers. Remote labels and observations
are escaped, mutation controls retain disabled states after failures, and reports
use protected plain-text attachment routes. The public shell explains pilot caps,
bounded coverage, dashboard-only results and deferred billing/email delivery.

### Survive island

The desktop Main Menu switches between the browser-filling game and the site
page view without resetting the session.
The North Gate waystation uses roof courses and a cloth pennant to mark the
staffed building. The keeper wears a mantle and cap distinct from the player.
The North Gate road has grouped verge plants outside the walking line.
Enemy counter animation follows a confirmed hit or spent guard in the combat
snapshot. The player's attack pose stays separate, and reduced motion disables
the counter lunge.
Engaged players use a ready stance and carry their weapon in hand between
confirmed actions. Disengaging restores the idle pose and stowed weapon.

Items lists carried resources and packed weapons, supports sorting, and keeps
the selected item across refreshes. Use Food sends the existing Eat action.
Equipment → Main uses the same confirmed action
path as field commands to equip or remove gear. Polls preserve the weapon
buttons while the pack is unchanged.

Movement responses can include a claimed roaming animal. Party members in range
can Engage it through Commands. Other players can select and Check the claim,
and Hunt considers only free animals. Participants read the shared health from
their game snapshots.
Commands uses the weapon skill name returned by the game snapshot. Impact
opening and closing messages use the existing chat event stream.
The Waystation Keeper uses the same field targeting controls as creatures.
The island exposes Talk within the server-supplied range, and assignment
progress appears in chat.

The Main Menu Party window sends invitation and membership commands through the
same request path as game actions. Its roster and chat notices come from the
requesting survivor's snapshot. Polls preserve the selected invite recipient.

`survive-world-model.js` owns display-only landmark filtering, camera projection and
movement prediction. The sky dome has its own material so its lower hemisphere uses the same horizon color as the upper sky. `survive-world-art.js` generates terrain, layered tree
crowns, underbrush and actor
meshes. `survive-world-materials.js` loads original grass, path, mossy stone,
bark and foliage images from `static/images/survive` into WebGL textures.
`survive-authored-actors.js` samples textured adventurer and wildlife animation
clips and attaches tools to the sampled hand. It bakes smooth adventurer shading
once on load and preserves hard edges between steeply angled faces.
`survive-world-actors.js` supplies
the fallback models. `survive-world-props.js`
builds the shelters, boats and resource-site props used by the same renderer.
The fishing bank groups textured rocks, driftwood and bent reeds around the dock,
leaving the central approach open. These props share the static scenery buffer.
Sand extends below the ocean surface and darkens near the waterline.
`survive-world-renderer.js` owns WebGL buffers, lighting, fog and disposal.
The starting campfire uses one camera-facing quad for soft flames and embers.
Local firelight falls off around the pit, and reduced motion freezes the effect.
`survive-dynamic-mesh.js` reuses typed vertex and GPU buffer capacity across frames.
`survive-world.js` owns scoped keyboard and controller movement, camera input,
sequenced presence pulses, timeout/backoff, player labels and frame/listener cleanup.
Nearby survivors render their confirmed main weapon, engagement and guard stance
from the presence response. Standing combat poses stay visible with reduced motion.
Fishing projects a line and bite cue on the water and offers Reel In during the
server-timed window. The bobber remains visible when reduced motion is enabled.
The connection label uses the island-wide online count, independent of nearby poses.
Recent confirmed strikes animate once per attacker revision. Reduced motion
keeps the standing combat poses and suppresses strike playback.
The local attack animation uses the owner's confirmed cue, so an ally's hit
does not make the local adventurer swing.
`survive.js` still serializes durable survival commands. New presence uses the API
constants in `lib/api.js`; it never computes survival rewards. The SVG scene remains
the fallback for unavailable/lost WebGL. Labels use text nodes, never HTML.

`survive-world-feedback.js` renders the current enemy name, health and marker.
The Chat log is the only surface for command results. Roaming animals interpolate
between server poses and approach the player visually on engagement.
`survive-action-pose.js` maps confirmed commands to timed body and tool poses.
Landed enemy counters add upper-body recoil to the local adventurer without
moving their feet. Blocked counters and reduced motion leave the body steady.
A confirmed victory keeps the defeated target visible briefly at the point of
contact. The target ring clears immediately, and reduced motion skips the fall.
Walking and action poses respect reduced motion.

The world target menu shows Check and the server-allowed combat commands for the
selected animal. The page refreshes at the next reported attack deadline during
engagement and on return to a visible tab. The server owns attacks, TP, and outcomes.

Survive's Main Menu opens with minus on the focused island or with its on-screen
control. Enter opens the contextual Commands window. Arrow keys move between
entries and Escape returns focus to the island. The read-only map shows landmarks
and the survivor's position. The date and time appear inside the viewport.

`survive-log.js` appends the shared world journal oldest first to the in-world
Chat log, using the server's event revision so repeated polls add nothing and
identical outcomes still appear separately. Check and command errors add local
lines. The log holds up to 60 lines and follows new entries only when already at
the bottom. Plus or Expand opens a taller scrollback window.

`survive-controller.js` uses browser device signals for the phone-only gate.
Phones require the game shell to be the fullscreen element, native WebGL, and a
connected standard Gamepad API mapping. The left stick drives movement, the
right stick turns the camera, and the D-pad and face buttons reuse menu controls.
Leaving fullscreen or disconnecting stops phone input and movement pulses.
Desktop and laptop browsers support the same controller mapping alongside
keyboard and mouse in ordinary windows at any viewport width.
