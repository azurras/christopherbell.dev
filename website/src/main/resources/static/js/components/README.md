# Frontend Components

Owns reusable browser-side components shared across pages.

## What Lives Here

- `nav.js` renders the shared site navbar as a web component.
- The nav adapts to auth state, loads user/notification state, and exposes the Tools dropdown for What's For Lunch and VIN Decoder.
- Dropdowns use Bootstrap when present and include vanilla fallbacks for pages that do not load Bootstrap JavaScript.
- Profile, user profile, and messages pages rely on defensive wrapping/stacking rules in `main.css` so long usernames, timestamps, buttons, and message text do not overlap.
- `pubsub.js` provides lightweight cross-component events, especially auth login/logout updates.

## Update This Doc

Update this README when shared component behavior, nav destinations, auth-state rendering, dropdown behavior, responsive layout rules, or pubsub events change.
