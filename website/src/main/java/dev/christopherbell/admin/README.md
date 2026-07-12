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
- The `commandcenter` subfeature owns cached OSHI and optional NVIDIA host
  metrics, the bounded configured log tail, one-time action challenges, fixed
  Windows command mappings, and the admin API. Provider failures are isolated;
  last-good values become stale and unavailable metrics stay explicit instead
  of blocking application startup or being fabricated.
- Command-center log reads use only `command-center.log-path`, return a line- and
  byte-bounded incremental tail, recover from rotation or truncation, and never
  return incomplete or oversized line fragments. Credentials are redacted before
  case-insensitive literal text and exact supported severity filters are applied.
- Command-center host actions use the closed set `RESTART_SITE`,
  `RESTART_COMPUTER`, `SHUTDOWN_COMPUTER`, and `CANCEL_PENDING_ACTION`. Actions
  are simulated by default. Windows mode maps those values to fixed WinSW or
  `shutdown.exe` argument arrays; restart and shutdown use the fixed 60-second
  delay, and cancellation uses the fixed `/a` command.
- Every challenged action requires a fresh active approved admin, immediate
  password verification, a single-use two-minute challenge, the exact
  confirmation phrase, throttling, and cooldown enforcement. Passwords are
  never persisted or logged. Challenge storage is bounded and expired entries
  are evicted.
- The method-secured command-center API is rooted at
  `/api/admin/command-center/2026-07-12`. Admins can read `snapshot` and `logs`,
  create `action-challenges`, submit confirmed `actions` (HTTP 202), and cancel
  a pending machine action at `actions/cancel`. Request validation runs before
  the action services, including a 100-character maximum log query. Log level
  `ALL` is the default and applies no severity filter; named levels restrict the
  returned records to that exact supported severity.

### Security Boundary

Every command-center API requires both valid ADMIN JWT authority and a fresh
persisted-account check through `CommandCenterAccessService`; missing accounts,
repository failures, and accounts that are no longer ADMIN, ACTIVE, and approved
fail closed before metrics, logs, challenges, or actions are invoked. The action
service repeats the persisted-account check at execution time as defense in
depth. Command-center APIs never accept shell
fragments, executable paths, service names, log paths, filenames, or arbitrary
arguments from callers. Request values select only the closed action enum or
bounded log filters; server configuration owns every filesystem and process
boundary.

### Verification

Run the focused command-center Java tests, `./gradlew :website:jsTest`,
`./gradlew :website:test`, `./gradlew :website:build`, and `node --check` for the
two command-center JavaScript modules before publishing changes.

## Update This Doc

Update this README when Back Office tabs, admin API payloads, or admin activity calculations change.
