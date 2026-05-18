# Admin Models

Owns persistence models for administrative audit activity.

## What Lives Here

- `AdminActivity` records who performed an admin action, what changed, and when.

## Design Notes

- Admin activity is append-only audit data. Prefer adding context fields over
  mutating past records.
- Keep this model small so admin workflows can log consistently without coupling
  to each feature's internal entity shape.

## Update This Doc

Update this README when admin audit fields or retention assumptions change.
