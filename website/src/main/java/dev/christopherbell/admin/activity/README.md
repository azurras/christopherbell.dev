# Admin Activity

Owns the audit-style activity feed shown in the Back Office.

## What Lives Here

- `AdminActivityController` exposes the admin-only recent activity endpoint.
- `AdminActivityService` records admin actions with actor, target, message, metadata, and timestamp details.
- `AdminActivityRepository` owns MongoDB access for recent admin activity records.

## Design Notes

This package exists so admin activity stays independent from other Back Office orchestration. Feature services can record admin actions through `AdminActivityService` without taking ownership of activity storage or formatting.

## Update This Doc

Update this README when admin activity event fields, retention rules, endpoint behavior, or recording semantics change.
