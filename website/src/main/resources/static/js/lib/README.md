# Frontend Library

Owns reusable browser-side modules that are not tied to one page.

## What Lives Here

- `api.js` defines API paths, including grouped admin-facing routes used by the Back Office.
- `util.js` owns auth storage helpers, request headers, JSON response handling,
  formatting, sanitizing, and small DOM utilities.
- `composer.js` initializes reusable post composer behavior.
- `feed-context.js` builds the context object used by feed rendering.
- `feed-render.js` renders post cards, mention links, likes, replies, menus, and
  inline reply composers.
- `infinite.js` owns reusable cursor-based infinite scrolling.
- `util.js` contains small formatting and escaping helpers.

## How It Works

- Page modules compose these helpers instead of importing from each other.
- Shared helpers receive callbacks for page-specific actions, which keeps
  dependency direction from page -> library.
- Feed rendering is intentionally centralized so post cards behave the same on
  home, profile, user feed, and thread pages.

## Design Notes

- Keep helper modules small and dependency-light. If a module starts needing page
  selectors, move that code back into a page entry module.
- Prefer explicit context objects over hidden imports for behavior that varies by
  page, such as delete permissions or reply handlers.
- Shared modules should be safe to import on any page.
- `fetchJson` clears stale auth state on `401` but only redirects when the caller
  passes `redirectOnUnauthorized: true`. Public tools and nav background fetches
  should not opt into redirects.

## Update This Doc

Update this README when shared frontend helper responsibilities, feed rendering
contracts, API helper behavior, or infinite-scroll behavior changes.
