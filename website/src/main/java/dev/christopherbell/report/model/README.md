# Report Models

Owns moderation report persistence and API records.

## What Lives Here

- `PostReport` is the Mongo-backed moderation report entity.
- `ReportCreateRequest` carries report submission input.
- `ReportResolveRequest` carries admin resolution input.
- `ReportResolution` and `ReportStatus` constrain moderation state.

## Design Notes

- Reports reference posts and accounts by id so moderation actions can operate
  even when display data changes.
- Resolution and status enums keep admin workflows explicit and auditable.

## Update This Doc

Update this README when report fields, resolution behavior, or moderation status
values change.
