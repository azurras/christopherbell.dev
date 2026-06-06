# Post Expiration

Owns Void post lifespan behavior.

## What Lives Here

- `PostExpirationService` calculates root post lifespans, repairs missing expiration data, synchronizes reply expiration with the root, deletes expired post trees, and runs the scheduled cleanup job.

## Design Notes

All replies live exactly as long as the root thread. Root likes, replies, and reply likes extend the root lifespan, then the root expiration is synchronized across every reply.

The scheduled cleanup job stays quiet when there is no work. It logs at info level only when it repairs posts missing expiration timestamps or deletes expired post trees.

