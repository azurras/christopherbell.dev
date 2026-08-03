# What's For Lunch

Owns lunch spot data, location-aware public picks, shared voting sessions, and legacy daily lunch picks.

## What Lives Here

- Restaurant CRUD and admin-only maintenance endpoints under `restaurant`.
- Restaurant persistence outages use the shared service-unavailable contract
  with preserved diagnostic causes and a redacted public response.
- Public location-aware lunch picks that query bounded nearby candidates and
  return three rating-weighted suggestions within the user's selected radius
  from their browser location or entered ZIP code. ZIP searches resolve their
  radius origin from imported Location Census ZCTA coordinates before loading
  restaurant candidates.
- New nearby, daily, and deleted-pick replacement draws use one batch of rating
  totals and sample without replacement. Three neutral 3-star virtual ratings
  temper sparse data; unrated restaurants retain the neutral weight and every
  eligible restaurant keeps a positive chance of selection.
- Logged-in shared sessions where up to 20 members see the same three restaurants,
  receive atomic session updates, and vote. The creator alone can reset picks.
  Sessions are active for 24 hours, remain as a read-only archive for 30 more
  days, and carry a TTL deletion deadline.
- Logged-in restaurant ratings with public whole-number rating totals.
- Restaurant websites are persisted and rendered only as absolute HTTP(S) URLs; unsafe legacy values are omitted.
- Legacy daily lunch picks persisted per day and refreshed at midnight Central. Scheduled refreshes
  use the shared renewable Mongo lease so only one application instance writes a given run; request
  fallback generation keeps its existing behavior. Already persisted daily and
  active shared-session picks remain stable until their existing refresh/reset
  action runs.
- Startup-validated OpenStreetMap metro configuration, with Austin, the San
  Francisco Bay Area, New Orleans, and Dallas enabled by default.
- OpenStreetMap import responses are bounded at 16 MiB before JSON parsing, and
  the request deadline remains effective until the complete response body arrives.
- One leased import workflow shared by scheduled and manual runs. Manual runs
  require a short-lived operator-bound preview token, re-fetch the source, and
  reject changed checksums before writing. Durable admin status records bounded
  error categories and public pages receive only source freshness and coverage.
- Restaurant inventory and duplicate-name previews use bounded indexed pages.
  Duplicate apply validates every confirmed group version before deleting anything.
- Restaurant website fields accept only absolute HTTP(S) URLs without credentials;
  response mapping repeats that check before public rendering.
- Workflow scaffolding under `workflow`.

## Update This Doc

Update this README when restaurant fields, import behavior, dedupe rules, nearby
pick behavior, Location ZIP dependencies, rating behavior, shared session
behavior, daily pick behavior, WFL routes, or admin maintenance endpoints
change.
