# What's For Lunch

Owns lunch spot data, location-aware public picks, and legacy daily lunch picks.

## What Lives Here

- Restaurant CRUD and admin-only maintenance endpoints under `restaurant`.
- Public location-aware lunch picks that return three shuffled Austin metro suggestions within fifteen miles of the user's browser location.
- Legacy daily lunch picks persisted per day and refreshed at midnight Central.
- OpenStreetMap import for Austin metro restaurants, including duplicate-name protection, same-name/address updates, cuisine/source metadata, and fast-food deprioritization.
- Duplicate-name cleanup that keeps the Austin record when possible.
- Workflow scaffolding under `workflow`.

## Update This Doc

Update this README when restaurant fields, import behavior, dedupe rules, nearby pick behavior, daily pick behavior, WFL routes, or admin maintenance endpoints change.
