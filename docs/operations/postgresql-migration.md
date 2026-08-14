# MongoDB to PostgreSQL migration

The standalone `postgresqlMigration` command supports `shadow`, `finalize`, `reconcile`, and
`status`. It is not a web endpoint. `shadow` publishes deterministic typed rehearsal rows without
deleting target rows. Only `finalize` may delete frozen-source rows that are absent from the source.

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
crash, keep `ChristopherBellDev` stopped and keep the deployment lock held. Confirm that no
PostgreSQL finalization process or transaction is active and reconcile the recorded run before an
authenticated administrator issues `fsyncUnlock`. Do not restart the website writer before the
source and typed PostgreSQL rows have been reconciled.
