# Frontend Components

Owns reusable browser-side components shared across pages.

## What Lives Here

- `nav.js` renders the shared site navbar as a web component.
- The nav adapts to auth state, loads user/notification state, shows the three
  most recent notifications in the bell dropdown, can request browser
  notifications while the site is open, routes WFL session invite notifications
  back to `/wfl?session=...`, presents the Void feed as the top-level `Feed`
  destination, and exposes the Tools dropdown for What's For Lunch, VIN Decoder,
  and ZIP Coordinates.
- The notification bell and profile menu are mutually exclusive; opening one
  closes the other so the nav never stacks those popups.
- The shared nav uses the Void console treatment: a `Signal Online` status row, Void brand mark, compact signal-style links, and dark dropdown/notification/profile menus.
- Dropdowns use Bootstrap when present and include vanilla fallbacks for pages that do not load Bootstrap JavaScript.
- Profile, user profile, and messages pages rely on defensive wrapping/stacking rules in `main.css` so long usernames, timestamps, buttons, and message text do not overlap.
- `pubsub.js` provides lightweight cross-component events, especially auth login/logout updates.

## Update This Doc

Update this README when shared component behavior, nav destinations, auth-state rendering, dropdown behavior, responsive layout rules, or pubsub events change.
