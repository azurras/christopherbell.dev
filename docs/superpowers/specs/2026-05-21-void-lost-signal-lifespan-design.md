# Void Lost Signal Lifespan Design

## Goal

Make every Void surface feel like a lonely dark transmission while turning the
existing post lifespan model into a visible part of feed behavior.

## Scope

This design covers Void-related surfaces that render posts:

- `/void`
- `/p/{postId}` thread pages
- `/profile` personal Void feed
- `/u/{username}` public user feed

It also covers shared post-card behavior used by those pages:

- live lifespan countdowns
- like-driven lifespan updates
- expiry animation and removal from the current view
- backend expiration enablement and cleanup

The design does not replace global navigation structure, create a separate Void
application shell, change post lifespan math beyond the approved policy, or add
new feed ranking rules.

## Visual Direction

The selected direction is **Lost Signal**.

Void surfaces should feel like a weak transmission floating in a dark empty
space:

- near-black page foundations
- restrained cyan signal accents
- crisp, low-noise panel borders
- quiet grid/static hints where they support depth
- high-contrast readable post text

The theme must not read as a dark card embedded in a normal bright page. Once a
visitor enters a Void-related surface, the viewport between top navigation and
the bottom of the page should feel continuous and dark.

## Site Chrome

Void pages keep the existing navigation and footer structure so people do not
lose the normal site wayfinding.

On Void-related pages, the nav and footer receive a Void-aware styling hook:

- same links, menus, and interaction behavior
- darker surfaces and borders that blend into Lost Signal
- no bright footer gap after short feeds or thread pages
- page background remains dark through the bottom of the viewport

Non-Void pages keep their existing site chrome.

## Void Page Surfaces

Void-related templates opt into one shared Lost Signal skin. The skin should
cover the hero, content background, cards, feed controls, and empty states.

### `/void`

The Void feed should be the strongest expression of the theme:

- hero copy and composer live on the same dark surface
- composer remains direct and task-focused
- filter and sort controls remain obvious
- feed cards read as signals in the Void rather than white social cards

### Thread Pages

Thread pages carry the same skin through:

- selected post panel
- reply list
- reply composer
- thread summary card
- context snippets

The root post and replies use the same card lifespan UI as feed cards.

### Profile And Public User Pages

Profile and public user pages keep their current information hierarchy while
their Void feed areas participate in the same Lost Signal surface.

- account/profile summaries remain usable and legible
- post panels, feed cards, feed empty states, and post-related actions carry the
  Void styling
- Void shell styling keeps the page from snapping back to bright whitespace near
  the footer

## Lifespan Policy

Post expiration is enabled for the Void.

The lifespan policy is:

- a newly-created post starts with 24 hours
- each active like adds 24 hours
- removing a like removes that like's 24-hour extension

The backend remains authoritative. The existing `expiresOn` timestamp is the
shared contract for rendering and cleanup.

## Countdown UI

Every shared post card shows a live lifespan countdown in its header area near
post metadata. It updates once per second and uses the card's `expiresOn` value.

Countdown behavior:

- use a readable tabular timer format
- show the remaining time to the second
- keep the timer visible but visually secondary to post text and author
- update immediately after a like response returns a newer `expiresOn`
- stay consistent across feeds, threads, profile feeds, and public user feeds

Posts without `expiresOn` during transition should not break rendering. The
renderer may omit or soften the timer until expiration data is available from
the backend.

## Like Interaction

Like behavior already returns updated feed item data. Shared post-card rendering
should use that response to update:

- like count
- liked state
- lifespan countdown target timestamp

The user should see the timer gain or lose the server-returned remaining time
after like toggle without reloading the page.

## Expiry Behavior

When a countdown reaches zero in the browser:

1. The post card enters a brief Lost Signal suction animation.
2. The card visually compresses inward, loses signal, and collapses from the
   current list.
3. Shared feed views remove that card from the current DOM.

The frontend animation communicates expiry. Storage deletion remains owned by
the backend expiration rules and scheduled cleanup.

On a selected thread page, an expiring focused post should not leave a broken
page. It should animate away and leave a clear expired state in the surrounding
thread context.

## Backend Behavior

The existing backend expiration model is reused:

- post creation calculates initial expiration
- like toggle recalculates expiration from creation time and active like count
- expired reads do not return active posts
- scheduled cleanup deletes expired posts
- legacy posts missing expiration timestamps are backfilled when needed

Configuration should turn expiration on for the active application defaults
used by deployed Void behavior.

## Frontend Structure

Shared behavior should stay shared:

- `feed-render.js` owns post card countdown markup and card-level timer lifecycle
- page entry modules continue to call the shared feed renderer
- page templates receive only the styling hooks needed to identify Void-related
  surfaces
- `main.css` owns the Lost Signal shell, Void-aware chrome, countdown styling,
  and expiry animation

Timer logic should avoid per-card leaks when cards are removed or when feed
content is rerendered.

## Error And Edge Cases

- Anonymous visitors can read timers on public Void feeds.
- If a like request fails, timer state does not pretend to change.
- If an expired card is still present client-side until cleanup, the countdown
  animation removes it from that page once it reaches zero.
- If a page fetches after backend cleanup, missing posts continue through the
  existing not-found or empty-state paths.
- Timer rendering must remain readable on narrow mobile card headers.

## Testing

Backend coverage should confirm:

- expiration-enabled configuration path is active
- new posts have the approved 24-hour base expiration
- likes and unlikes recalculate `expiresOn` by active like count
- cleanup still deletes expired posts

Frontend verification should cover:

- syntax checks for touched JavaScript
- shared countdown formatting and expiry behavior with the smallest practical
  JavaScript verification path available in this repo
- visual/manual verification across `/void`, `/p/{postId}`, `/profile`, and
  `/u/{username}` after implementation

Documentation should be updated with:

- Void expiration behavior in the post feature docs
- shared feed timer responsibility in frontend docs where shared renderer
  ownership changes
- Void shell styling responsibility in CSS docs
