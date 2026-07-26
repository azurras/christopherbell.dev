# Admin Activity

Owns the audit-style activity feed shown in the Back Office.

## What Lives Here

- `AdminActivityController` exposes the legacy recent list and the stable,
  filterable, paged audit ledger.
- `AdminActivityService` records admin actions with actor, target, message,
  metadata, timestamp, reason, and allowlisted before/after state.
- `AdminActivityRepository` owns MongoDB access for recent admin activity records.
- `ModerationAuditCommand` validates bounded audit events before a domain
  mutation begins; `AdminActivityQueryService` validates and executes ledger
  filters with stable `createdOn`/`_id` ordering.

## Design Notes

This package exists so admin activity stays independent from other Back Office
orchestration. Moderation events are append-only. State maps admit only role,
status, and resolution; metadata is allowlisted so credentials, email addresses,
and content bodies cannot leak into the audit record.

## Update This Doc

Update this README when admin activity event fields, retention rules, endpoint behavior, or recording semantics change.
