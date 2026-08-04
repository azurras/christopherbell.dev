# Restaurant vote persistence

Owns one `UP` or `DOWN` vote per account and restaurant in the retained
`whatsforlunch_ratings` collection. `RestaurantVoteQueryRepository` calculates
bounded public summaries and Top 10 Liked order inside MongoDB. V013 is the only
supported bridge from legacy numeric documents; runtime code does not dual-read.

## What Lives Here

- `RestaurantVoteRepository` stores one member vote per restaurant.
- `RestaurantVoteSummary` validates a nonblank restaurant ID, nonnegative
  UP/DOWN counts, and a total equal to their sum.
- `RestaurantVoteQueryRepository` aggregates candidate summaries and public
  leaderboard results.

## Design Notes

- The physical collection and unique restaurant/account index retain their
  historical names through V013; callers use vote-named Java types only.
- Top 10 Liked includes only restaurants with votes and sorts approval ratio
  descending, vote count descending, then restaurant ID ascending. Query results
  are bounded to 50.
- Vote writes and aggregate enrichment remain owned by `RestaurantService`; this
  package owns persistence and aggregate-query boundaries only.

## Update This Doc

Update this README when vote persistence, aggregate invariants, collection
migration, or leaderboard ordering changes.
