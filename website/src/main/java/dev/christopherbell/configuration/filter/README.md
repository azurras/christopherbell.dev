# Configuration Filters

Owns cross-cutting servlet filters that are not specific to one feature.

## What Lives Here

- `RateLimitFilter` protects the app from excessive per-client request volume.
- `RequestSizeLimitFilter` rejects requests that exceed the configured payload size limit.

## Design Notes

Filters in this package should stay small and deterministic. Add focused filter tests for limit boundaries and bypass behavior when behavior changes.

