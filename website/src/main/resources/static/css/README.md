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
- The Void feed composer uses `.composer-preview-*` classes for the live draft
  preview, including mention links, clickable URLs, and shared rich media embeds.
- Messages uses `.void-messages-*` classes in `main.css` for the Signal Bridge
  layout: dark conversation rail, private thread panel, directional message
  bubbles, unread conversation highlighting, recipient suggestion listbox, and
  responsive one-column behavior.
- Notifications uses `.notification-center-*` and `.notification-settings*`
  classes for the full Signal Log page and category preference panel. The nav
  bell keeps only the three most recent notifications and uses the compact
  notification dropdown rules near the shared nav styles.
- WFL uses shared lunch classes for picks, restaurant profiles, favorites, and
  top-rated lists, plus `wfl-secondary-nav` for local WFL navigation and
  `lunch-controls` for the filters, location, and Lunch with Friends control tabs.
- ZIP Coordinates uses `.zip-coordinate-*` classes in `main.css` for its
  Void-inspired lookup shell, result grid, and copyable endpoint output.
- Raising Canes Box Index uses `.canes-box-*` classes in `main.css` for its
  public Tool shell, red/green month-over-month and year-over-year index cards,
  latest-price summary, responsive data-quality summary grids, chart panels,
  selected-metro trend panel, official API curl verification block, and
  clickable metro sample table.
- Mission Control uses `.command-*` classes for its hidden admin-gated shell,
  phone-first telemetry grid, 15-minute sparklines, delayed log console,
  accessible challenge dialog, and visually separate danger zone.
- Void-related templates opt into `void-shell-page`; `main.css` owns the Lost
  Signal shell, Void-aware nav/footer treatment, shared lifespan countdown
  styling, rich post link preview cards, allowlisted media embeds, grouped post
  image/GIF grids, GIF badges, the shared image lightbox/fallback states, and
  expiry motion for those pages.
- Shared Void controls have explicit `:focus-visible` outlines so feed actions,
  menus, rich image triggers, thread controls, notification buttons, and avatar
  controls remain keyboard-visible.
- The `/p/{id}` post detail page adds the `void-thread-*` class family for the
  Spectral Thread layout: a centered single-column shell, sticky topbar, muted
  root/parent context echoes, selected-post hierarchy, nested Signal Rail,
  previous/next thread jump links, newest-reply and expand-all controls,
  collapsible reply branch controls, compact reply composer, and reply timeline
  connector. Mobile rules intentionally restore the avatar column and connector
  for this page after the broader feed mobile rules hide them elsewhere.
- Public profile cards use `.profile-trust-status` for mute/block feedback
  inside the same Void shell treatment as the rest of the profile page.
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
