# Restaurant

Owns the data model and APIs for What's For Lunch restaurants.

## What Lives Here

- Restaurant create/read/update/delete APIs.
- Public nearby lunch picks, legacy daily lunch picks, and admin-only maintenance endpoints.
- User WFL preferences for saved cuisine filters. Preference persistence lives in `preference`.
- Shared WFL sessions where logged-in members see the same three restaurants and vote. Session orchestration lives in `session`.
- Whole-number restaurant ratings from logged-in members, plus public rating totals. Rating persistence lives in `rating`.
- Favorite restaurants for logged-in members and a public top-rated restaurant list. Favorite persistence lives in `favorite`.
- Daily pick persistence and replacement behavior after admin deletion.
- OpenStreetMap import with coordinates, same-name/address updates, duplicate-name protection, duplicate cleanup, monthly scheduling, and startup catch-up when the previous month was missed.
- Restaurant DTOs, import summaries, daily pick models, and repository interfaces.

## Location Picks

- `GET /api/whatsforlunch/restaurant/2026-05-17/nearby` accepts browser latitude and longitude.
- `GET /api/whatsforlunch/restaurant/2026-05-17/nearby/zip/{zipCode}` accepts a 5-digit ZIP code and uses imported Location Census ZCTA coordinates as the search origin.
- `GET /api/whatsforlunch/restaurant/2026-05-17/profile/{id}` returns a public restaurant profile used by `/wfl/restaurants/{id}`.
- `/wfl/favorites` lists the signed-in user's favorited restaurants.
- `/wfl/top-rated` lists the public top 10 rated restaurants.
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

## Ratings

- `PUT /api/whatsforlunch/restaurant/2026-05-17/rating` lets a signed-in member set one rating for a restaurant by sending `restaurantId` and `rating` in JSON. This avoids putting OpenStreetMap ids with punctuation in the URL path.
- `PUT /api/whatsforlunch/restaurant/2026-05-17/{id}/rating` remains available for older callers with simple ids.
- Ratings must be whole numbers from `1` through `5`; fractional values are rejected instead of rounded.
- Public restaurant details include `ratingSum` and `ratingCount`; the UI displays a whole-number `Rating: N/5` or `No Ratings` when nobody has rated the restaurant.
- Signed-in callers also receive `myRating` when they have already rated that restaurant.
- Signed-in views show both the overall rating and the caller's personal rating.
- `GET /api/whatsforlunch/restaurant/2026-05-17/top-rated` returns the highest-rated restaurants with at least one rating.

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
- The default Overpass import covers Austin, the San Francisco Bay Area, New Orleans, and Dallas through semicolon-separated bounding boxes in `wfl.restaurant-import.osm.bbox`.
- Missing OpenStreetMap `addr:city` values default to `Imported Metro` instead of an Austin-specific city label.
- Automated imports run monthly on the fifteenth using `wfl.restaurant-import.monthly.cron`.
- The scheduler logs start, completion, and failure events.
- `RestaurantImportState` stores the last completed import month. On application startup, WFL checks whether the previous month has a completed import; if not, it runs a catch-up import immediately.

## Update This Doc

Update this README when restaurant fields, import matching/merge rules, duplicate rules, daily pick rules, nearby pick rules, rating/favorite rules, session voting rules, import scheduling, admin endpoints, or public WFL response shapes change.
