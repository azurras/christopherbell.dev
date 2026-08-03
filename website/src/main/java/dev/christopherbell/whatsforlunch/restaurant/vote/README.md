# WFL Restaurant Votes

Owns persistence and MongoDB aggregation for the binary restaurant-vote domain.

## What Lives Here

- `RestaurantVoteRepository` stores one member `UP` or `DOWN` vote per restaurant.
- `RestaurantVoteSummary` is the validated aggregate result: a nonblank restaurant ID, nonnegative UP/DOWN counts, and a total equal to their sum.
- `RestaurantVoteQueryRepository` aggregates the physical `whatsforlunch_ratings` collection for candidate selection and the public leaderboard.

## Design Notes

- The physical collection and unique restaurant/account index retain their historical names through the V013 data migration; callers use vote-named Java types only.
- Leaderboards include only restaurants with votes and sort approval ratio descending, vote count descending, then restaurant ID ascending. Results are bounded to 50.
- Vote writes and aggregate enrichment remain owned by `RestaurantService`; this package owns persistence and aggregate-query boundaries only.
- Task 4/5 presentation work is deferred. Do not add numeric-rating compatibility types, `/rating` endpoints, or browser helper aliases here.

## Update This Doc

Update this README when vote persistence, aggregate invariants, collection migration, or leaderboard ordering changes.
