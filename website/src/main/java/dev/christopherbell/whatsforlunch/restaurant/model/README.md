# Restaurant Models

Owns What's For Lunch restaurant persistence and API records.

## What Lives Here

- `Restaurant` is the Mongo-backed restaurant entity.
- `Address` is embedded restaurant address data, including optional latitude and longitude for nearby WFL picks.
- `RestaurantCreateRequest` and `RestaurantUpdateRequest` carry admin input.
- `RestaurantDetail` is the API response shape.
- `RestaurantImportResult` summarizes OpenStreetMap import work.
- `RestaurantDedupeResult` summarizes duplicate-name cleanup.
- `DailyLunchPicks` stores the daily pick ids for a specific date.

## Design Notes

- Restaurant identity is name-sensitive and address-aware. Imports update exact
  same-name/same-address matches but skip duplicate names at different addresses.
- Nearby lunch suggestions require saved coordinates. OpenStreetMap imports set
  coordinates from node latitude/longitude or way/relation center data.
- Daily picks store ids rather than embedded restaurant snapshots so admin
  deletes and replacements operate on current restaurant records.
- Import/dedupe result records are explicit because admin tools need counts for
  logs and UI feedback.

## Update This Doc

Update this README when restaurant identity rules, address fields, import result
fields, daily pick storage, nearby pick behavior, or dedupe behavior changes.
