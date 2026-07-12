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
- Command-center log reads use only the configured server-side path, return a
  line- and byte-bounded incremental tail, recover from rotation or truncation,
  and never return incomplete or oversized line fragments. Credentials are
  redacted before case-insensitive literal text and severity filters are applied.
- Command-center host actions use a closed enum, fresh active-approved-admin
  checks, password verification, single-use two-minute challenges, exact
  confirmation phrases, throttling, cooldowns, and fixed simulated or Windows
  argument arrays. Challenge storage is bounded and expired entries are evicted.
  Machine actions always use a fixed 60-second countdown; cancellation is
  serialized with launch and succeeds only after the fixed `/a` process exits
  successfully within its bounded wait. Request values never become executables
  or command arguments.
- The method-secured command-center API is rooted at
  `/api/admin/command-center/2026-07-12`. Admins can read `snapshot` and `logs`,
  create `action-challenges`, submit confirmed `actions` (HTTP 202), and cancel
  a pending machine action at `actions/cancel`. Request validation runs before
  the action services, including a 100-character maximum log query. Log level
  `ALL` is the default and applies no severity filter; named levels restrict the
  returned records to that exact supported severity.

## Update This Doc

Update this README when Back Office tabs, admin API payloads, or admin activity calculations change.
