# WFL Ratings

Owns restaurant rating persistence.

## What Lives Here

- `RestaurantRatingRepository` stores one whole-number rating per member and restaurant.

## Design Notes

- Rating behavior still runs through `RestaurantService` during this package refactor so endpoint behavior stays stable.
- Rating data is used for member writes and public aggregate rating summaries on restaurant details.

## Update This Doc

Update this README when rating validation, aggregation, top-rated sorting, or persistence behavior changes.
