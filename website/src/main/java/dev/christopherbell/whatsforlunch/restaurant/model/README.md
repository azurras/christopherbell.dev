# Restaurant Models

Owns What's For Lunch restaurant persistence and API records.

## What Lives Here

- `Restaurant` is the Mongo-backed restaurant entity.
- `Address` is embedded restaurant address data, including optional latitude and longitude for nearby WFL picks.
- `RestaurantCreateRequest` and `RestaurantUpdateRequest` carry admin input.
- `RestaurantDetail` is the API response shape.
- `RestaurantFavorite` stores one favorite marker per account and restaurant.
- `RestaurantFavoriteRequest` is the favorite/unfavorite API contract.
- `RestaurantRating` stores one whole-number rating per account and restaurant.
- `RestaurantRatingRequest` and `RestaurantRatingSetRequest` are the rating API contracts.
- `RestaurantImportResult` summarizes OpenStreetMap import work.
- `RestaurantImportState` stores monthly OpenStreetMap import scheduler state.
- `RestaurantDedupeResult` summarizes duplicate-name cleanup.
- `DailyLunchPicks` stores the daily pick ids for a specific date.
- `WhatsForLunchPreference` stores saved cuisine filters and radius by account id.
- `WhatsForLunchPreferenceRequest` and `WhatsForLunchPreferenceDetail` are the preference API contracts.
- `WhatsForLunchSession` stores a fixed three-restaurant voting session for logged-in members and shared-link joiners.
- `WhatsForLunchSessionCreateRequest`, `WhatsForLunchSessionRestaurantsRequest`,
  `WhatsForLunchSessionVoteRequest`, and
  `WhatsForLunchSessionDetail` are the shared-session API contracts.

## Design Notes

- Restaurant identity is name-sensitive and address-aware. Imports update exact
  same-name/same-address matches but skip duplicate names at different addresses.
- Nearby lunch suggestions require saved coordinates. OpenStreetMap imports set
  coordinates from node latitude/longitude or way/relation center data.
- Daily picks store ids rather than embedded restaurant snapshots so admin
  deletes and replacements operate on current restaurant records.
- Import/dedupe result records are explicit because admin tools need counts for
  logs and UI feedback.
- Import state is persisted so startup can detect and catch up missed monthly
  imports without relying on log history.
- Preferences are separate from `Account` so WFL personalization stays inside
  the WFL feature package.
- Session details expose usernames and vote groupings, not participant account
  ids, because the page only needs member-facing labels.
- Replacing session restaurants uses its own request record so refreshes do not
  share the invite-only fields from session creation.
- Ratings are stored separately from restaurants so rating writes do not modify
  imported restaurant data. Aggregate totals are attached to `RestaurantDetail`.
  Signed-in callers also get their own `myRating` value on `RestaurantDetail`.
  Browser writes use `RestaurantRatingSetRequest` so provider ids such as
  OpenStreetMap ids stay in JSON instead of URL path segments.
- Favorites are stored separately from restaurants so member personalization does
  not modify imported restaurant data. Browser writes use `RestaurantFavoriteRequest`
  for the same provider-id reason as ratings. Signed-in callers get `myFavorite`
  on `RestaurantDetail` so pages can render heart state without another lookup.

## Update This Doc

Update this README when restaurant identity rules, address fields, import result
fields, daily pick storage, nearby pick behavior, preference storage, session
voting storage, rating/favorite storage, or dedupe behavior changes.
