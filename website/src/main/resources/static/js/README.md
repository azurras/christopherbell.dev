# JavaScript

Owns browser-side behavior for server-rendered pages.

## What Lives Here

- Page entry modules such as `home-feed.js`, `messages.js`, `profile.js`,
  `post.js`, `user-feed.js`, `vin-decoder.js`, and `whats-for-lunch.js`.
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
- Shared modules should not assume a specific page exists. They should accept
  selectors, callbacks, or small context objects from page modules.
- `messages.js` renders the `/messages` Signal Bridge interactions: conversation
  list state, selected private thread, safe message body rendering, character
  counter, send action, unread-first conversation rows, and login redirect.
- The Void feed toolbar keeps the primary feed filter surface to `All` and
  `Following`; profile and thread pages handle personal post and reply views.
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
- `post.js` renders the `/p/{id}` Spectral Thread page. It loads the selected
  post and thread data, fills root/parent context echoes, applies selected-post
  detail styling through the shared feed renderer, wires the compact reply
  composer, and renders direct replies as a timeline.
- `wfl-list.js` renders the WFL secondary pages for favorites and the public top
  10 rated restaurants.
- `back-office.js` gates the Back Office to admins, renders report/user queues,
  and exposes practical admin operations such as Location Census ZIP coordinate
  import, WFL import/dedupe, and vehicle VIN maintenance.

## Design Notes

- Keep page modules thin. If two pages need the same behavior, move the behavior
  into `lib` or `components` instead of copying it.
- Avoid broad global state. Prefer module-local state and explicit callbacks.
- Keep browser alerts out of new shared code when practical; page modules should
  render errors into page-specific alert containers.

## Update This Doc

Update this README when browser module ownership, shared entry behavior, or
frontend directory structure changes.
