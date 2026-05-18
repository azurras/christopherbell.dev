# JavaScript

Owns browser-side behavior for server-rendered pages.

## What Lives Here

- Page entry modules such as `home-feed.js`, `messages.js`, `profile.js`,
  `post.js`, `user-feed.js`, `vin-decoder.js`, and `whats-for-lunch.js`.
- `app.js`, which wires shared page chrome and auth-aware behavior.
- `components`, which contains reusable web components and cross-page UI pieces.
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
- API calls go through `lib/api.js` so auth headers, response parsing, and
  endpoint paths stay consistent.
- `whats-for-lunch.js` requests browser geolocation, loads three restaurants
  within fifteen miles from the WFL nearby API, and re-queries after refresh or
  admin delete actions.
- `back-office.js` gates the Back Office to admins, renders report/user queues,
  and exposes practical admin operations such as WFL import/dedupe and vehicle
  VIN maintenance.

## Design Notes

- Keep page modules thin. If two pages need the same behavior, move the behavior
  into `lib` or `components` instead of copying it.
- Avoid broad global state. Prefer module-local state and explicit callbacks.
- Keep browser alerts out of new shared code when practical; page modules should
  render errors into page-specific alert containers.

## Update This Doc

Update this README when browser module ownership, shared entry behavior, or
frontend directory structure changes.
