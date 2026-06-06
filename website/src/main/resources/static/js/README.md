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
  `Following`; profile and thread pages handle personal post and reply views.
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
  the public history API, including the large percent index, verified latest
  average price, data-quality counts, metro sample status/source/quality, and a
  lightweight inline SVG trend chart.
- `post.js` renders the `/p/{id}` Spectral Thread page. It loads the selected
  post and thread data, fills root/parent context echoes, applies selected-post
  detail styling through the shared feed renderer, renders the nested Signal
  Rail and previous/next thread links through `lib/thread-navigation.js`, wires
  newest-reply jumping and collapsible reply branches, wires the compact reply
  composer, and renders replies as a timeline.
- Feed-rendering pages initialize `lib/image-lightbox.js` so direct image and
  animated GIF links open in a shared preview dialog and broken external images
  fall back to a source link instead of leaving empty space.
- Feed-rendering pages initialize `lib/lazy-media.js` after rendering post
  cards so rich iframes defer their `src` until they are near the viewport.
- `wfl-list.js` renders the WFL secondary pages for favorites and the public top
  10 rated restaurants.
- `user-feed.js` renders public profiles and exposes signed-in mute/block
  actions for other users.
- `back-office.js` gates the Back Office to admins, renders report/user queues
  with repeat-report context, supports user approval/status changes/role
  promotion, and exposes practical admin operations such as Location Census ZIP
  coordinate import, WFL import/dedupe, Raising Canes Box Index collection and
  datapoint review, and vehicle VIN maintenance.

## Design Notes

- Keep page modules thin. If two pages need the same behavior, move the behavior
  into `lib` or `components` instead of copying it.
- Avoid broad global state. Prefer module-local state and explicit callbacks.
- Keep browser alerts out of new shared code when practical; page modules should
  render errors into page-specific alert containers.

## Update This Doc

Update this README when browser module ownership, shared entry behavior, or
frontend directory structure changes.
