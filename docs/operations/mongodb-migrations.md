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

### Exact domain collection consolidation

The 52 allowlisted domain kinds consolidate into exactly 14 physical
collections. Start with the read-only metadata and checksum preview:

```powershell
.\prod.cmd mongo-consolidation-preview
```

The preview never creates a backup, stops a service, starts a process, mutates
MongoDB, or prints document values. Stop if it is incomplete, reports an
unexpected kind/collection, or does not use manifest digest
`576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24`.

Run the mutating command only in an approved maintenance window:

```powershell
.\prod.cmd mongo-consolidate -ConfirmDomainCollectionCutover
```

The confirmation is exact; `deploy` and `auto-deploy` cannot supply it. One
fixed `locks\deploy.lock` is held from the fresh hash-bound backup and second
dry restore through isolated candidate proof, writer stop with SCM recovery
suspended, live stage/publication, target start and verification, second writer
stop, one-at-a-time legacy deletion, marker finalization, target restart,
recovery restoration, and auto-deploy refresh. The candidate uses only a
generated non-production database and configured candidate port.

Deletion is last. Before the first deletion intent, recovery reverses the
publication and resumes the exact old release. At or after that intent, recovery
restores the bound archive and passes `restore-verify` before the old release can
be selected or started. If recovery cannot be proved, the website stays stopped
with recovery suspended.

Use the guarded rollback command, never generic release rollback, for this
schema boundary:

```powershell
.\prod.cmd mongo-consolidation-rollback -WhatIf
.\prod.cmd mongo-consolidation-rollback -ConfirmDomainCollectionRollback
```

The protected marker binds manifest, evidence, backup, target/legacy releases,
and deletion state. The prior Music v1 marker format remains readable for
rollback compatibility, but its former public cutover switches are retired.

While a domain cutover marker is pending, deploy, auto-deploy, rollback, and
manual restart remain blocked except through the guarded domain recovery path.
Preserve the protected archive, sidecar, evidence, and cutover-state files until
the rollback window is explicitly closed.

Every WinSW launch, including boot and recovery restarts, runs the same strict marker/current-release
guard before Java. Stable target and legacy markers permit only their exact bound release. A
deployment transition may create one atomic authorization for the exact marker state/target/legacy
tuple, release, purpose, expiry, issuer PID, and issuer process start identity. The launch script
requires that exact issuer to remain alive and consumes the authorization once; the deployment
revokes the exact returned token on success and failure, so it cannot be replayed by recovery or a
later manual start. The launcher also checks its own and the module's installed hashes against the
protected bundle manifest before importing any guard code. If the domain marker
is absent, the guard starts only a legacy domain-schema release. The retained
Music v1 compatibility path still requires its migration-014 probe. Active or unknown migration state,
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
