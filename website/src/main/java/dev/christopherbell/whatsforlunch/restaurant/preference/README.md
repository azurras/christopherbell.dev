# WFL Preferences

Owns saved What's For Lunch filters.

## What Lives Here

- `WhatsForLunchPreferenceRepository` stores per-account cuisine filters and preferred search radius.

## Design Notes

- Preference behavior still runs through `RestaurantService` during this package refactor so endpoint behavior stays stable.
- The repository lives here because saved filters are member-owned WFL preference state, not restaurant catalog data.

## Update This Doc

Update this README when saved filter fields, validation, defaults, or preference persistence behavior changes.
