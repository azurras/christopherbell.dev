# What's For Lunch

Owns lunch spot data, location-aware public picks, shared voting sessions, and legacy daily lunch picks.

## What Lives Here

- Restaurant CRUD and admin-only maintenance endpoints under `restaurant`.
- Public location-aware lunch picks that return three shuffled suggestions within the user's selected radius from their browser location or entered ZIP code.
- Logged-in shared sessions where invited members see the same three restaurants, receive session updates, and vote.
- Logged-in restaurant ratings with public whole-number rating totals.
- Legacy daily lunch picks persisted per day and refreshed at midnight Central.
- OpenStreetMap import for configured metro restaurants, including Austin, the San Francisco Bay Area, New Orleans, and Dallas by default.
- Duplicate-name cleanup that keeps one stable survivor per duplicate group.
- Workflow scaffolding under `workflow`.

## Update This Doc

Update this README when restaurant fields, import behavior, dedupe rules, nearby pick behavior, rating behavior, shared session behavior, daily pick behavior, WFL routes, or admin maintenance endpoints change.
