# CSS

Owns site styling for server-rendered pages and frontend components.

## What Lives Here

- `main.css` is the primary stylesheet for the current site, Void, tools, auth,
  profile, messages, post cards, and responsive layout.
- `blog.css` contains older blog-specific styling.
- `simple.css` contains legacy/simple page styling.
- `thebell.css` styles the archived The Bell pages.

## How It Works

- Current templates should use `main.css` unless they are intentionally part of a
  legacy area.
- Shared components and page modules rely on stable class names in `main.css`.
- The shared nav uses `.void-console-nav` classes for the Signal Online status
  row, Void brand mark, compact nav pills, auth actions, dark dropdowns, and
  notification/profile menu styling. The green `.void-signal-dot` uses a slow
  blink animation to read like an old router status light.
- The home page uses `.home-void-*` classes for the Void-first gateway, primary
  `/void` call to action, and live five-item Signal Rail.
- Messages uses `.void-messages-*` classes in `main.css` for the Signal Bridge
  layout: dark conversation rail, private thread panel, directional message
  bubbles, unread conversation highlighting, and responsive one-column behavior.
- Notifications uses `.notification-center-*` classes for the full Signal Log
  page. The nav bell keeps only the three most recent notifications and uses the
  compact notification dropdown rules near the shared nav styles.
- WFL uses shared lunch classes for picks, restaurant profiles, favorites, and
  top-rated lists, plus `wfl-secondary-nav` for local WFL navigation and
  `lunch-controls` for the filters, location, and Lunch with Friends control tabs.
- ZIP Coordinates uses `.zip-coordinate-*` classes in `main.css` for its
  Void-inspired lookup shell, result grid, and copyable endpoint output.
- Void-related templates opt into `void-shell-page`; `main.css` owns the Lost
  Signal shell, Void-aware nav/footer treatment, shared lifespan countdown
  styling, rich post link preview cards, and expiry motion for those pages.
- The `/p/{id}` post detail page adds the `void-thread-*` class family for the
  Spectral Thread layout: a centered single-column shell, sticky topbar, muted
  root/parent context echoes, selected-post hierarchy, compact reply composer,
  and reply timeline connector. Mobile rules intentionally restore the avatar
  column and connector for this page after the broader feed mobile rules hide
  them elsewhere.
- Responsive rules live near the related component styles when possible. Broad
  page-level breakpoints are kept at the end of `main.css`.

## Design Notes

- Prefer stable grid/flex dimensions over content-driven alignment for repeated
  UI such as home tiles, post cards, message rows, and profile stat cards.
- Keep card radius at or below 8px unless an existing legacy page has its own
  visual language.
- Avoid one-off page overrides when a shared component rule can solve the same
  issue cleanly.

## Update This Doc

Update this README when stylesheet ownership, responsive layout strategy, shared
class contracts, or legacy stylesheet usage changes.
