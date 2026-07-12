# Admin

Owns back-office administration views and cross-feature admin operations.

## What Lives Here

- Admin activity recording and reads live under `activity`.
- Admin-facing DTOs under `model`.
- Cross-feature moderation/admin summaries, such as reports and recent operational state.
- Back Office work queues for reports and users.
- Back Office user moderation actions, including approval, suspension,
  activation, and role promotion through the account update API.
- Back Office operations for Location Census ZIP coordinate imports, What's For
  Lunch imports/dedupe, vehicle VIN admin actions, vehicle collection state, and
  admin-only content reads.
- The `commandcenter` subfeature samples OSHI and optional NVIDIA host metrics on
  one schedule, retains bounded in-memory history, and exposes only immutable
  cached snapshot models to its API layer. Provider failures are isolated and
  last-good values become stale instead of blocking application startup.

## Update This Doc

Update this README when Back Office tabs, admin API payloads, or admin activity calculations change.
