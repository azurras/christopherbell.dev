# MongoDB Application Migrations

The application runs immutable, ordered MongoDB migrations before it becomes
ready. Every deployment creates a verified production backup and validates the
candidate against a disposable clone of that backup before live cutover.
Application rollback does not reverse MongoDB data.

## Authoring Contract

- Add a new `ApplicationMigration` with a unique, ordered ID.
- Make the operation additive and idempotent wherever MongoDB permits it.
- Preserve compatibility with the previously deployed application version.
- Derive and review a stable SHA-256 checksum for the migration descriptor.
- Never edit the ID, checksum, or behavior of an applied migration. Use a new
  compensating migration when data or schema behavior must change.

## Deployment Boundary

The port-8081 candidate receives a generated, allowlisted database name and
runs against only the restored clone. Candidate migrations and verification
cannot touch `christopherbell`. Cleanup drops only that exact candidate database;
the verified archive and SHA-256 sidecar remain available as evidence.

Live cutover stops the prior website writer before switching the release and
starting the new binary. Starting the new binary crosses the forward-only live
migration boundary. From that point, any migration, readiness, endpoint, or
public-route failure leaves the website stopped and unready. Never restart the
prior binary against potentially migrated data. Preserve the backup and failure
evidence, then repair the data/application forward or perform an explicitly
approved production restore. Deployment never restores live data automatically.

The runner serializes deployments with the fixed `application-migrations` lease
in `application_leases`. Lifecycle records live in `application_migrations` and
use `RUNNING`, `APPLIED`, or `FAILED` status.

## Inspecting a Blocked Startup

Connect `mongosh` to the intended database, then inspect only the migration and
lease involved:

```javascript
db.application_migrations.find(
  {_id: "<migration-id>"},
  {checksum: 1, description: 1, status: 1, startedAt: 1, completedAt: 1,
    failureCategory: 1})
db.application_leases.find(
  {_id: "application-migrations"},
  {acquiredAt: 1, expiresAt: 1})
```

A checksum mismatch, `RUNNING` record, or `FAILED` record intentionally stops
startup. Correct the underlying failure first. Confirm no deployment is active
and that the lease is absent or expired. If the operation was not safely
idempotent, restore the pre-deployment backup instead of editing migration state.

### Music runtime-state rollback exception

Migration 014 leaves both legacy collections intact, but the new release writes only
`music_runtime_state`. A binary rollback therefore requires reverse-copying the latest queue
and radio state before the prior release starts. This is an emergency compatibility operation,
not a routine application rollback step. Keep the `ChristopherBellDev` writer stopped and first
preview the fixed database and three namespaces:

```powershell
.\prod.cmd music-runtime-rollback -WhatIf
```

After confirming that preview, run the command with `-ConfirmMusicRuntimeRollback`. The dispatcher
routes confirmed execution through the coordinated binary rollback boundary; do not run a separate
reverse-copy followed by `rollback`. One acquisition of the fixed production `locks\deploy.lock`
is retained while the writer is stopped, a fresh full backup and SHA-256 sidecar are verified, the
exact singleton BSON is reverse-copied and read back, the release junctions are switched, and the
legacy writer is started through an exact, expiring, single-use transition authorization and
health-checked. Only then does the protected atomic schema-direction marker become
`LEGACY_ACTIVE_RECONCILIATION_REQUIRED`. A later failure stops the writer and reports the retained
backup path with its original cause. Generic `rollback` never performs this reverse-copy and
refuses to select the marker-owned legacy release with exact guidance to the confirmed command.
No step drops, deletes, or renames a collection.

While reconciliation is required, automatic deployment and manual restart are blocked. An
interactive `deploy` validates its candidate, stops the legacy writer under the same deploy lock,
creates and verifies a new backup, copies each currently present legacy singleton forward with
exact raw-shape and readback validation, switches to the target release, changes the marker to
`TARGET_ACTIVE`, and only then starts the target writer. Queue-only, radio-only, both-present, and
both-absent states are valid; a retained destination singleton that conflicts with an absent legacy
singleton fails closed. Task 6 must invoke the protected deploy with `-MusicSchemaCutover`; after
the new release and migration pass health verification, that same still-held deploy lock is used
to change the pre-start `TARGET_CUTOVER_IN_PROGRESS` marker to `TARGET_ACTIVE` with the exact
current and previous release identities before any other deployment or rollback command can run.
The pending state blocks deploy, auto-deploy, rollback, and manual restart after a crash or failed
final marker write. `Confirm-ProductionMusicTargetActive` is the bounded
repair/confirmation seam for an already verified cutover, not a replacement for the single-lock
first-cutover path.

Every WinSW launch, including boot and recovery restarts, runs the same strict marker/current-release
guard before Java. Stable target and legacy markers permit only their exact bound release. A
deployment transition may create one atomic authorization for an exact release, marker state,
purpose, and expiry; the launch script consumes it once, so it cannot be replayed by recovery or a
later manual start. If the marker is absent, the guard starts only a legacy-compatible release and
only after the database probe proves migration 014 inactive. Active or unknown migration state,
malformed marker data, a mismatched release, and an expired or replayed authorization all block the
writer. Manual `prod.cmd restart` remains blocked while reconciliation is required; guarded boot,
service recovery, and sensor restarts may restart only the exact marker-owned legacy release.

Keep the service stopped if any gate, replacement, or readback check fails. Preserve the reported
backup, allowlisted phase/error code, and failure cause, and obtain approval before attempting a
broader production restore. Do not manually restart the service while this operation or another
deployment command owns `deploy.lock`.

Keep the website service stopped throughout this investigation. Do not start
the prior release as a diagnostic step after the live migration boundary.

Only after the cause is corrected, the backup is confirmed, and the affected
data is known safe may an operator remove the single incomplete record:

```javascript
db.application_migrations.deleteOne({_id: "<exact-incomplete-migration-id>"})
```

Record that bounded recovery in the deployment report and restart the
application. Never delete the collection, all migration records, or unrelated
leases.
