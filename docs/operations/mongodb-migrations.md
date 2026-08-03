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
