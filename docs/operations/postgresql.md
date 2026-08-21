# Native Windows PostgreSQL operations

This runbook covers the native PostgreSQL 18.4 runtime used by
`christopherbell.dev`. PostgreSQL is loopback-only, uses SCRAM authentication,
and is managed by the guarded commands in `ops/production/windows/prod.ps1`.
The install, bootstrap, status, backup, shadow, and reconcile commands prepare
or verify PostgreSQL; they do not authorize a MongoDB source freeze, migration
finalization, website cutover, or production restart. Only the separately
confirmed `postgres-cutover` command crosses that authority boundary.

## Authority and safety boundary

Run mutating commands from elevated PowerShell 7 on the production Windows
host. They take the fixed protected deployment lock at
`C:\ProgramData\christopherbell.dev\locks\deploy.lock`. Use `-WhatIf` before
each mutation. Never pass a database password as a command argument or paste
one into a report.

PostgreSQL must remain bound only to `localhost:5432`. The managed host policy
requires `scram-sha-256` for IPv4 loopback, IPv6 loopback, and the local
administrator connection. Do not add a LAN or public CIDR rule.

## Configuration and secrets

The checked-in examples are:

- `ops/production/windows/config/deploy.example.json`
- `ops/production/windows/config/app.env.example`
- `ops/production/windows/config/postgresql.env.example`

The installer copies missing examples to the protected production `config`
directory without replacing existing files. Replace every placeholder in
`postgresql.env` with an independently generated high-entropy value. Protect
the file with the same SYSTEM-and-Administrators-only ACL as `app.env`.

The PostgreSQL administrator and all login roles have separate secrets:

- `christopherbell_migrator`: schema migration only; may assume the non-login
  owner role.
- `christopherbell_app`: production application DML; cannot create schemas.
- `christopherbell_bridge`: temporary migration bridge DML.
- `christopherbell_viewer`: production read-only access for pgAdmin.
- `christopherbell_backup`: dump and isolated restore access.
- `christopherbell_test`: read/write and isolated-schema creation confined to database `test`.

`christopherbell_owner` is deliberately `NOLOGIN` and therefore has no
password. Do not register the administrator, owner, migrator, bridge, or backup
role in pgAdmin.

The website production environment is exactly:

```text
APP_PERSISTENCE_BACKEND=postgresql
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/christopherbell
SPRING_DATASOURCE_USERNAME=christopherbell_app
SPRING_DATASOURCE_PASSWORD=<protected application secret>
```

## Install and bootstrap

Review the dry-run effects first:

```powershell
.\prod.cmd postgres-install -WhatIf
.\prod.cmd postgres-bootstrap -ConfirmPostgreSqlBootstrap -WhatIf
.\prod.cmd postgres-pgadmin -WhatIf
```

With an approved PostgreSQL-preparation window, run:

```powershell
.\prod.cmd postgres-install
.\prod.cmd postgres-bootstrap -ConfirmPostgreSqlBootstrap
.\prod.cmd postgres-pgadmin
.\prod.cmd postgres-status
```

Installation accepts only the signed PostgreSQL 18.4 Windows runtime and the
fixed `postgresql-x64-18` service identity. Bootstrap is idempotent: it creates
or rotates the login roles, creates the `christopherbell` and `test` databases,
revokes default public access, and invokes the migration-only Java entry point
from the pinned active release. That entry point validates database, role,
server address, port, and exact PostgreSQL version before applying Flyway V1
through V27 as the owner role. It then applies runtime grants to all ten
canonical schemas. It does not construct or start the website application.

pgAdmin imports two password-free registrations:

- `christopherbell-test` as `christopherbell_test`;
- `christopherbell-production-viewer` as `christopherbell_viewer`.

Enter those two role passwords interactively in pgAdmin. Do not save or import
privileged credentials.

## Status

```powershell
.\prod.cmd postgres-status
```

The probe uses the application role and fails closed unless it observes the
production database, application role, PostgreSQL 18.4, loopback-only listener,
SCRAM password encryption, no application schema-create privilege, and the
viewer role's read-only default. Output is value-free with respect to secrets.

## Backup and restore proof

Create a custom-format archive and immediately prove it in an isolated restore
database:

```powershell
.\prod.cmd postgres-backup -WhatIf
.\prod.cmd postgres-backup
```

Each successful backup writes the `.dump` plus SHA-256 JSON evidence beneath
the configured PostgreSQL backup root. The backup role performs `pg_dump`; the
administrator creates and drops the isolated `cbrestore_YYYYMMDDHHMMSS`
database; the backup role performs `pg_restore --exit-on-error`. The isolated
database is dropped in `finally`, including after a restore failure.

Recheck a specific archive and checksum without creating another backup:

```powershell
.\prod.cmd postgres-restore-check -WhatIf
.\prod.cmd postgres-restore-check
```

Never restore directly into `christopherbell` or `test` as a verification
step. Retain the archive and checksum evidence according to the production
backup-retention policy.

## Migration and cutover sequence

After PostgreSQL preparation is green:

1. Complete the read-only `shadow` rehearsal and reconciliation in the
   [MongoDB to PostgreSQL migration runbook](postgresql-migration.md).
2. Validate a PostgreSQL-profile website candidate on a non-8080 port.
3. Obtain explicit cutover authority before stopping the writer, finalizing
   the frozen source, changing the live listener, or restarting production.
4. During the rollback window, retain MongoDB and the bridge role. Remove them
   only after the approved soak and backup evidence are complete.

Preview the complete production command without effects:

```powershell
.\prod.cmd postgres-cutover -ConfirmPostgreSqlCutover -WhatIf
```

After an explicit, time-bounded maintenance-window approval, run from elevated
PowerShell 7:

```powershell
.\prod.cmd postgres-cutover -ConfirmPostgreSqlCutover
```

The command permits at most 30 minutes from its persisted `PLANNED` journal to
`SOAKING`. It stops the website writer, creates and dry-restores the final
MongoDB archive, finalizes and reconciles all 52 kinds, creates and verifies a
PostgreSQL backup, tests the PostgreSQL candidate, and only then persists the
authority-publication intent. From `AUTHORITY_PUBLICATION_STARTED` onward,
recovery is forward-only: MongoDB must not be restored as the application
authority. A successful run leaves MongoDB stopped and records a 14-day soak
plus a 90-day MongoDB archive-retention deadline.

## Incident response

- If bootstrap fails before the Java migration entry point, correct the role or
  database bootstrap cause and rerun; it is idempotent.
- If Flyway fails, do not start the PostgreSQL-profile website. Preserve the
  error, inspect the schema history, and correct the migration cause before
  retrying.
- If backup or restore verification fails, treat the backup as invalid and do
  not use it for cutover authority.
- If PostgreSQL is not loopback-only or SCRAM-protected, stop. Restore the
  managed configuration and re-run status before any application start.
- A failed MongoDB finalization has additional writer-freeze recovery rules;
  follow `postgresql-migration.md` and do not improvise an unlock or restart.
- Before `AUTHORITY_PUBLICATION_STARTED`, the wrapper restores the Mongo-backed
  website only after `currentOp` proves MongoDB is not fsync-locked. If the
  lock state is true or cannot be proven, keep the website stopped and perform
  the authenticated recovery procedure below.
- At or after `AUTHORITY_PUBLICATION_STARTED`, never start Mongo-backed writers.
  Repair PostgreSQL, activate the recorded release, and finish production
  verification from the durable cutover journal.
