# WFL Favorites

Owns member favorite restaurants.

## What Lives Here

- `RestaurantFavoriteRepository` stores favorite records keyed by member and restaurant.

## Design Notes

- Favorite behavior still runs through `RestaurantService` during this package refactor so endpoint behavior stays stable.
- Favorites are separate from imported restaurants so member actions never mutate imported catalog records.

## Update This Doc

Update this README when favorite persistence, lookup ordering, or response membership behavior changes.
