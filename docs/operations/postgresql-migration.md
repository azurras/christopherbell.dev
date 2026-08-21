# MongoDB to PostgreSQL migration

Prepare and verify the native database, roles, backups, restore proof, and
unprivileged pgAdmin registrations first by following the
[native Windows PostgreSQL runbook](postgresql.md).

The standalone `postgresqlMigration` command supports `shadow`, `finalize`, `reconcile`, and
`status`. It is not a web endpoint. `shadow` publishes deterministic typed rehearsal rows without
deleting target rows. Only `finalize` may delete frozen-source rows that are absent from the source.

Production operators must not invoke `finalize` directly. The sole public
production entry point is:

```powershell
.\ops\production\windows\prod.cmd postgres-cutover -ConfirmPostgreSqlCutover
```

Use it only after explicit approval for an up-to-30-minute maintenance window.
It owns the writer stop, final archive and restore proof, signed finalization
authority, reconciliation, PostgreSQL backup and restore proof, candidate,
one-way authority marker, listener activation, verification, and soak journal.

## Finalization write exclusion

Run `finalize` only in an approved native-Windows maintenance window. The command requires all of
these conditions and fails closed if any condition changes:

- the fixed `C:\ProgramData\christopherbell.dev\locks\deploy.lock` is held exclusively;
- the Windows service `ChristopherBellDev` reports `STOPPED`;
- the signed authority files under the fixed protected PostgreSQL-migration authority directory
  and its frozen writer evidence remain valid; and
- MongoDB reports a server-wide `fsyncLock` continuously from the final source reread until after
  the PostgreSQL transaction commits.

The MongoDB principal in `POSTGRESQL_MIGRATION_SOURCE_URI` therefore needs authenticated admin
permission for `fsync` with `lock: true`, `currentOp`, and `fsyncUnlock`. The lock permits the
migration's reads but blocks every MongoDB write on that server. The migration never writes source
documents. An independent writer remains blocked until PostgreSQL commits and the command releases
the lock in `finally`.

MongoDB deliberately retains an fsync lock when the client process disconnects. After a command
crash, the operating system releases that process's file-lock ownership; the continued presence of
`deploy.lock` does not prove exclusion. Keep `ChristopherBellDev` stopped and leave MongoDB fsync-
locked. An approved recovery process must reacquire the protected deployment lock exclusively,
then confirm that no PostgreSQL finalization process or transaction is active and reconcile the
recorded run before an authenticated administrator issues `fsyncUnlock`. Until that recovery
exclusion is owned, the retained MongoDB fsync lock and stopped website service are the writer-
safety boundary. Do not restart the website writer before the source and typed PostgreSQL rows have
been reconciled.

## Durable phases and recovery

The protected cutover journal permits only this ordered chain:

```text
PLANNED -> WRITERS_STOPPED -> MONGO_ARCHIVED -> POSTGRESQL_FINALIZED
-> POSTGRESQL_RECONCILED -> POSTGRESQL_BACKED_UP -> CANDIDATE_VERIFIED
-> AUTHORITY_PUBLICATION_STARTED -> AUTHORITY_PUBLISHED
-> PRODUCTION_ACTIVE -> PRODUCTION_VERIFIED -> SOAKING
```

Every transition is hash-bound to its prior phase and an immutable evidence
sidecar. A resumed command revalidates the release, lock token, database
identities, catalog digest, target JDBC digest, transition order, and journal
digest before performing another effect.

Before `AUTHORITY_PUBLICATION_STARTED`, a failure may return to MongoDB only
when the authority marker is absent and MongoDB `currentOp` explicitly reports
`fsyncLock:false`. Otherwise leave `ChristopherBellDev` stopped and follow the
authenticated unlock procedure above. The terminal pre-authority state is
`ROLLED_BACK`.

At `AUTHORITY_PUBLICATION_STARTED` or later, rollback to MongoDB is forbidden,
including when authority-marker persistence is uncertain. The terminal fault
state is `FORWARD_RECOVERY_REQUIRED`; repair PostgreSQL and continue the
recorded release forward. Do not delete or rewrite the journal or sidecars.

After `SOAKING`, retain the stopped MongoDB service, final MongoDB archive,
PostgreSQL archive, authority evidence, journal, and bridge role for at least
the recorded 14-day soak. Retain the final MongoDB archive for the recorded
90-day period. Task 10 decommissioning is a separate approved operation after
the soak evidence is complete.
