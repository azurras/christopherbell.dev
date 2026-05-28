# Void Homepage Redesign Design

## Goal

Make the root homepage feel like the front door to the Void instead of a light
general-purpose landing page. The first impression should point visitors toward
the live feed, while still keeping the site's other destinations discoverable.

## Approved Direction

Use Option A, "Feed Gateway", from:

`.superpowers/brainstorm/1779846106-homepage-void/content/homepage-void-directions.html`

The hero copy should be mostly about the Void. Lunch, Messages, and Tools should
feel secondary to the homepage's main purpose.

## Page Structure

- Keep the route as `/` and the template as `templates/index.html`.
- Add the same Void shell treatment used by Void-adjacent pages:
  `site-page void-shell-page` on the body and dark homepage-specific classes.
- Replace the current light hero with a dark Void-first hero.
- Use one primary hero action only:
  - `Enter Void` -> `/void`
- Keep destination cards below the hero for secondary paths:
  - `Void` -> `/void`
  - `Messages` -> `/messages` for signed-in users via existing page behavior
  - `What's For Lunch` -> `/wfl`
  - `Tools`-oriented cards such as VIN Decoder and ZIP Coordinates when space
    allows without crowding the layout

## Copy

Hero copy should avoid broad marketing language. It should describe the Void as
the site's live signal:

- Kicker: `christopherbell.dev`
- Heading: `Drop into the Void.`
- Body: emphasize short posts, replies, half-formed thoughts, and live activity.
- Primary button: `Enter Void`

Supporting copy may mention secondary destinations, but not in the hero body.

## Visual Design

- Use the existing Void palette: dark background, teal/gold accents, muted text,
  and restrained panel borders.
- Avoid decorative card nesting. Hero content should sit in a dark panel/shell,
  with secondary destinations in individual cards below or beside it.
- Keep cards at 8px radius or less.
- Preserve responsive behavior: on mobile, the hero should stack before the
  activity/sidebar content and destination cards should become one column.
- Do not introduce frontend dependencies, build steps, or npm tooling.

## Frontend Behavior

No new API calls are required. The page remains mostly static and continues to
load `/js/app.js` for shared nav/footer behavior.

## Documentation

Update:

- `website/src/main/java/dev/christopherbell/view/README.md` for the homepage
  route's Void-first purpose.
- `website/src/main/resources/static/css/README.md` for the homepage Void styling
  ownership.

## Testing

Add or update focused tests:

- `ViewControllerTest` should assert `/` renders the Void-first homepage text.
- Run `./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest`.
- Run `git diff --check` on touched files.

If only HTML/CSS changes are made, no JavaScript syntax check is required unless
a JS file is touched.
