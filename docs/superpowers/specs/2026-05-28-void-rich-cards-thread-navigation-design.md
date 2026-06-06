# Void Rich Cards and Thread Navigation Design

## Goal

Improve the Void reading experience by making common post links render as rich,
safe inline media/cards and by making nested reply pages easier to navigate.

## Approved Direction

Use the practical scope from mockup option A:

- Extend the existing shared feed renderer instead of creating separate card
  implementations per page.
- Support only allowlisted rich providers that can be rendered from the URL
  itself: direct images, YouTube, Spotify, SoundCloud, and GitHub cards.
- Keep every original URL clickable in post text.
- Add thread navigation to the post detail page using the existing full-thread
  API payload. No backend API or model change is required.

## Rich Card Behavior

The shared feed renderer detects URLs in post text and stored link previews.
Provider handling is intentionally deterministic:

- YouTube links render privacy-enhanced `youtube-nocookie.com` iframes.
- Direct image URLs ending in `.jpg`, `.jpeg`, `.png`, `.gif`, or `.webp` render
  as lazy images.
- Spotify `track`, `album`, `playlist`, `episode`, and `show` links render as
  Spotify embed iframes.
- SoundCloud links render via the SoundCloud widget iframe.
- GitHub repository, issue, and pull request links render as first-party styled
  anchor cards, not external iframes or API calls.

The renderer deduplicates rich embeds so the same URL appearing in both text and
stored previews only renders once. Stored generic link previews remain available
for URLs that are not handled by a richer provider.

## Thread Navigation Behavior

The `/p/{id}` page already loads the selected post and the whole root thread.
The frontend will use that full thread response to render:

- Previous and next thread links based on chronological thread order.
- A compact nested Signal Rail showing every post in the thread.
- A highlighted current post row.
- Clickable rows that navigate to `/p/{postId}`.

The direct reply list remains focused on replies to the selected post. The new
Signal Rail gives readers a way to move through deeper branches without changing
the existing API contract.

## Architecture

- `static/js/lib/feed-render.js` remains the single owner for shared post card
  rendering.
- `static/js/lib/thread-navigation.js` owns pure thread navigation modeling and
  markup so it can be tested without a browser page module.
- `static/js/post.js` imports the thread navigation helpers and writes the
  result into a dedicated template slot.
- `templates/post.html` adds the dedicated slot.
- `static/css/main.css` adds responsive styles for embeds and thread navigation.

## Safety

No arbitrary iframe URLs are allowed. Every iframe source is generated from an
allowlisted provider parser. User-authored strings that appear in generated HTML
are sanitized before insertion.

## Testing

Add JavaScript unit coverage for:

- Rich provider URL parsing.
- Deduplication across post text and stored previews.
- Rich provider rendering in shared post cards.
- Thread navigation previous/next modeling.
- Nested Signal Rail item generation and selected-post highlighting.

Run `node --check` on touched JavaScript and the relevant `node --test` suites.
