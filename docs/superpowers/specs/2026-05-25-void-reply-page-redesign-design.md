# Void Reply Page Redesign Design

## Decision Summary

Redesign the post/reply page around Option C, the "Spectral Thread" direction.
The page should feel closer to X/Twitter in reading rhythm, hierarchy, and
thread ergonomics while keeping the Void identity through dark signal styling,
faded context, and lifespan-aware metadata.

The redesign is limited to the `/p/{id}` post page experience. It should not
change post APIs, reply persistence, lifespan calculation, or the shared feed
contract unless implementation uncovers a small display-only gap.

## Goals

- Make reply pages easier to scan by using a single-column thread layout.
- Make the selected post feel like the central object on the page.
- Preserve the Void look: dark shell, teal/gold signal colors, quiet uncanny
  context, and lifespan language.
- Keep the page mobile-first and avoid the current card-heavy/thread-stats
  layout.
- Keep existing interactions: reply, like, delete where allowed, author links,
  parent/root context links, expired state handling, and link previews.

## Non-Goals

- No backend data model changes.
- No new frontend framework or npm workflow.
- No redesign of the global feed, profile feed, or WFL pages.
- No change to the post lifespan algorithm.
- No new moderation or reporting behavior.

## Layout

The page becomes a centered single-column timeline instead of a two-column
content/sidebar layout.

The top of the page should use a compact sticky-feeling thread header:

- Back affordance to the Void feed or browser history.
- Title text such as `Post`.
- Secondary metadata such as reply count and live/expired status.
- Optional overflow affordance if existing post actions require it.

The large marketing-style hero should be removed from this page. The page should
open directly into the thread surface.

## Selected Post

The selected post is the anchor of the page. It should render larger than
replies and closer to an X/Twitter post detail view:

- Author avatar/initial, username, handle-style username, and timestamp.
- Larger post body text.
- Link previews using existing shared rendering behavior.
- Timestamp and lifespan/status metadata below the body.
- Action row for reply, like, delete when allowed, and any existing post actions.

The selected post should still use the shared feed rendering helpers where that
keeps behavior consistent, but implementation may add a detail-mode class or
wrapper so the selected post can have stronger hierarchy without changing every
feed card.

## Context Echoes

When the selected post is itself a reply, root/parent context should appear
above it as muted "signal echoes" instead of prominent cards.

Each context echo should:

- Be visually quieter than the selected post.
- Show the author handle and a short text preview.
- Link to the root or parent post.
- Use subtle connector lines or fading borders to imply thread ancestry.
- Fall back to "Context unavailable" if the context fetch fails.

This keeps Twitter-like context while making it feel native to the Void.

## Reply Composer

The reply composer should sit directly under the selected post, similar to X.

Behavior:

- Logged-in users can write and submit a reply from the page.
- Anonymous users should see an obvious login/signup prompt only where reply
  interaction requires it.
- The composer should be compact by default and should not create a large empty
  panel.

If the current inline feed reply composer already handles this behavior, reuse
it. If not, expose the existing `#replyComposer` in the new layout and wire it
through the current post creation API.

## Replies Timeline

Replies should render as one continuous timeline below the composer:

- Avatar column on the left.
- Subtle vertical connector line for reply continuity.
- Author row, body, metadata, and actions.
- Direct replies to the selected post first, preserving current behavior.
- Empty state should be compact and Void-toned, not a large standalone card.

Deep thread behavior can remain based on existing thread API data. This design
does not require full recursive tree rendering unless current APIs already make
it straightforward. The page can continue showing direct replies to the selected
post, with context echoes providing root/parent navigation.

## Visual Style

Use the existing Void shell as the base:

- Background: dark, low-contrast, not a bright card wall.
- Primary text: pale blue-white.
- Secondary text: muted blue-gray.
- Accent colors: teal for active/live signal, gold for context/ancestry.
- Borders: thin, low-opacity signal lines.
- Cards: avoid separate floating cards where a timeline row will work.

Do not add decorative orbs, heavy gradients, or oversized hero sections.

## Responsiveness

The design should be mobile-first:

- Single column at all widths.
- Max readable width on desktop.
- No sidebar.
- Buttons and action rows must wrap without overlapping.
- Long usernames, URLs, and post text must not overflow.

## Accessibility

- Preserve semantic main/section structure.
- Use real buttons and links for actions.
- Keep visible focus states.
- Ensure contrast for muted metadata remains readable.
- Keep status text available as text, not color alone.

## Implementation Notes

Expected files:

- `website/src/main/resources/templates/post.html`
- `website/src/main/resources/static/js/post.js`
- `website/src/main/resources/static/css/main.css`
- `website/src/main/resources/static/js/README.md`
- `website/src/main/resources/static/css/README.md`
- `website/src/main/java/dev/christopherbell/post/README.md`

Keep JavaScript vanilla. Prefer adding small rendering helpers in `post.js`
instead of creating broad shared abstractions unless shared feed behavior truly
needs it.

## Testing And Verification

- Run `node --check website/src/main/resources/static/js/post.js`.
- Run relevant Java tests if template routing or controller behavior changes.
- Use Playwright or browser screenshots at mobile and desktop widths to verify:
  - selected post is visible without overlap,
  - context echoes do not crowd the focused post,
  - reply rows wrap correctly,
  - actions remain usable,
  - empty and expired states render coherently.

## Open Decisions

- Whether the back control should always link to `/void` or use browser history
  when available.
- Whether the reply composer should be always visible for logged-in users or
  expand after pressing a Reply button.
