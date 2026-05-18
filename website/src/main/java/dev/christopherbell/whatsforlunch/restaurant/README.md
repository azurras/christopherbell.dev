# Restaurant

Owns the data model and APIs for What's For Lunch restaurants.

## What Lives Here

- Restaurant create/read/update/delete APIs.
- Public nearby lunch picks, legacy daily lunch picks, and admin-only maintenance endpoints.
- Daily pick persistence and replacement behavior after admin deletion.
- OpenStreetMap import with coordinates, same-name/address updates, duplicate-name protection, and duplicate cleanup.
- Restaurant DTOs, import summaries, daily pick models, and repository interfaces.

## Location Picks

- `GET /api/whatsforlunch/restaurant/2026-05-17/nearby` accepts browser latitude and longitude.
- Nearby picks are filtered to restaurants with saved coordinates inside a fifteen mile radius.
- Each nearby request shuffles candidates again and returns up to three spots, preferring non-fast-food restaurants before fast-food fallback records.
- Admin deletes remove the restaurant from the database. The WFL page then requests a fresh nearby list to replace the deleted card.

## Update This Doc

Update this README when restaurant fields, import matching/merge rules, duplicate rules, daily pick rules, nearby pick rules, admin endpoints, or public WFL response shapes change.
