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
