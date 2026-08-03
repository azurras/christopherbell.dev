# Restaurant

Owns the data model and APIs for What's For Lunch restaurants.

## What Lives Here

- Restaurant create/read/update/delete APIs.
- Public nearby lunch picks, legacy daily lunch picks, and admin-only maintenance endpoints.
- User WFL preferences for saved cuisine filters. Preference persistence lives in `preference`.
- Shared WFL sessions where logged-in members see the same three restaurants and vote. Session orchestration lives in `session`.
- Binary UP/DOWN restaurant votes from logged-in members, plus public vote totals. Vote persistence and aggregation live in `vote`.
- Favorite restaurants for logged-in members and a public top-liked restaurant API. Favorite persistence lives in `favorite`.
- Daily pick persistence and replacement behavior after admin deletion.
- OpenStreetMap import with coordinates, same-name/address updates, duplicate-name protection, duplicate cleanup, monthly scheduling, and startup catch-up when the previous month was missed.
- Restaurant DTOs, import summaries, daily pick models, and repository interfaces.

## Location Picks

- `GET /api/whatsforlunch/restaurant/2026-05-17/nearby` accepts browser latitude and longitude.
- `GET /api/whatsforlunch/restaurant/2026-05-17/nearby/zip/{zipCode}` accepts a 5-digit ZIP code and uses imported Location Census ZCTA coordinates as the search origin.
- `GET /api/whatsforlunch/restaurant/2026-05-17/profile/{id}` returns a public restaurant profile used by `/wfl/restaurants/{id}`.
- `/wfl/favorites` lists the signed-in user's favorited restaurants.
- `/wfl/top-liked` is the canonical public Top 10 Liked browser/SSR route; legacy `/wfl/top-rated` permanently redirects to it.
- Optional `radiusMiles` query param controls the nearby search radius. Allowed values are `1`, `5`, `10`, `15`, and `20`.
- Optional `cuisine` query params filter nearby picks by OpenStreetMap cuisine tags. Multiple values are allowed.
- `GET /api/whatsforlunch/restaurant/2026-05-17/preferences` is public so `/wfl` can load for anonymous visitors; it returns saved filters for an authenticated token and default filters otherwise.
- Signed-in members can save default cuisine filters with `PUT /api/whatsforlunch/restaurant/2026-05-17/preferences`. Member write endpoints require an authenticated JWT; admin-only endpoints still use explicit `ADMIN` authority checks.
- Signed-in users can also save their preferred radius with the same preferences endpoint.
- Saved filters and radius are used only when a nearby request does not provide explicit values and does not set `useSavedPreferences=false`.
- Nearby picks query coordinate candidates inside a coarse Mongo bounding box, then
  apply the exact selected-radius check in the service before returning results.
- ZIP nearby picks ask the Location ZIP coordinate service for a persisted
  Census Gazetteer ZCTA internal point, then query nearby restaurant coordinates
  by radius.
- Each nearby request shuffles candidates again and returns up to three spots. Fast-food restaurants are eligible without a ranking penalty.
- The browser keeps the current three picks as a solo WFL session across page refreshes. Logged-in users persist that solo session in the backend session collection; anonymous users keep the same picks in local browser storage.
- Clicking "Try 3 more" clears the current solo session and requests a new set of restaurants. In a shared session it replaces the session's restaurants for every participant.
- Admin deletes remove the restaurant from the database. The WFL page then requests a fresh nearby list to replace the deleted card.

## Restaurant Votes

- `PUT /api/whatsforlunch/restaurant/2026-05-17/vote` lets a signed-in member set one binary vote by sending `restaurantId` and `vote` (`"UP"` or `"DOWN"`) in JSON. This avoids putting OpenStreetMap ids with punctuation in the URL path.
- `PUT /api/whatsforlunch/restaurant/2026-05-17/{id}/vote` is the equivalent path form for simple restaurant IDs.
- Numeric, fractional, and unknown vote values are rejected; each member has one persisted vote per restaurant.
- Public restaurant details include `upVotes`, `downVotes`, and `voteCount`; signed-in callers also receive `myVote` when they have voted.
- `GET /api/whatsforlunch/restaurant/2026-05-17/top-liked` returns restaurants with at least one vote, ordered by approval ratio descending, vote count descending, and restaurant ID ascending.
- Browser vote controls submit the binary vote contract and refresh from the
  server-confirmed restaurant detail response.

## Favorites

- `GET /api/whatsforlunch/restaurant/2026-05-17/favorites` returns the signed-in user's favorite restaurants, newest favorite first.
- `PUT /api/whatsforlunch/restaurant/2026-05-17/favorite` favorites one restaurant for the signed-in user.
- `DELETE /api/whatsforlunch/restaurant/2026-05-17/favorite` removes that favorite.
- Favorite state is returned as `myFavorite` on restaurant details when the caller is signed in.
- Favorites live in their own collection so member preferences do not modify imported restaurant records.

## Shared Sessions

- `POST /api/whatsforlunch/restaurant/2026-05-17/sessions` creates a session from exactly three restaurant ids currently shown on the WFL page for a signed-in member.
- The creator is added automatically and can optionally invite members by username.
- Invited members receive a WFL session notification that opens `/wfl?session={id}`.
- Shared links use the same `/wfl?session={id}` URL. Logged-in users join the session automatically from that link.
- Anonymous users who open a session link are prompted to log in or create an account, then return to the session URL after authentication.
- `GET /api/whatsforlunch/restaurant/2026-05-17/sessions/{id}` returns the fixed restaurant list and current votes for participants only.
- `POST /api/whatsforlunch/restaurant/2026-05-17/sessions/{id}/join` adds the signed-in user to a shared-link session.
- `PUT /api/whatsforlunch/restaurant/2026-05-17/sessions/{id}/vote` lets each participant cast or update one vote.
- `PUT /api/whatsforlunch/restaurant/2026-05-17/sessions/{id}/restaurants` replaces the three restaurants for all participants and clears votes for the new slate.
- The WFL browser page polls active sessions so restaurant and vote changes made by one participant appear for the others.

## OpenStreetMap Import

- Manual imports still run through the admin endpoint.
- The default Overpass import covers configured bounding boxes for Austin, the San Francisco Bay Area, New Orleans, and Dallas.
- Imported locality is the first nonblank `addr:city`, `addr:town`, `addr:village`, or `addr:municipality` value that matches a configured supported city. The canonical configured city and state are stored.
- Missing, unsupported, state/country-conflicting, or coordinate-less locations are excluded; the importer never invents a city or state.
- Street and postal-code tags remain optional when the supported locality and coordinates are valid.
- Automated imports run monthly on the fifteenth using `wfl.restaurant-import.monthly.cron`.
- The scheduler logs start, completion, and failure events.
- `RestaurantImportState` stores the last completed import month. On application startup, WFL checks whether the previous month has a completed import; if not, it runs a catch-up import immediately.
- If an existing OpenStreetMap id is renamed to a normalized name owned by another restaurant, preview reports it unchanged and apply skips it without mutation so the remaining import can complete.

## Update This Doc

Update this README when restaurant fields, import matching/merge rules, duplicate rules, daily pick rules, nearby pick rules, vote/favorite rules, session voting rules, import scheduling, admin endpoints, or public WFL response shapes change.
