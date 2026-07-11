# MongoDB Backup and Restore Runbook

Native Windows production backups are created with:

```powershell
.\prod.cmd backup
```

The archive is stored under `backupRoot` from
`C:\ProgramData\christopherbell.dev\config\deploy.json`. The default example is
`A:\Projects\christopherbell.dev-backups`. Every backup must be non-empty, pass
`mongorestore.exe --dryRun`, and have a JSON sidecar containing its SHA-256.
The command never deletes the prior verified archive.

## Inspect and Verify

```powershell
$archive = Get-ChildItem A:\Projects\christopherbell.dev-backups\*.archive.gz |
  Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
Get-Item $archive.FullName
Get-FileHash $archive.FullName -Algorithm SHA256
& 'C:\Program Files\MongoDB\Tools\100\bin\mongorestore.exe' `
  --archive $archive.FullName --gzip --dryRun
```

Compare the computed hash with the adjacent `.sha256.json` file. If any check
fails, preserve the previous known-good backup and stop.

## Validation Restore

Restore into a temporary database first:

```powershell
& 'C:\Program Files\MongoDB\Tools\100\bin\mongorestore.exe' `
  --uri mongodb://127.0.0.1:27017 `
  --archive $archive.FullName --gzip --drop `
  '--nsFrom=christopherbell.*' `
  '--nsTo=christopherbell_restore_check.*'
```

Generate canonical inventories for the source and validation databases using
the same `Get-MongoInventory` implementation used by `prod migrate`. Collection
names, document counts, index names, index keys, and uniqueness flags must match
exactly. An intentionally removed document or index must make comparison fail.

Run the application against the validation database on a non-production port.
Confirm `GET /` returns HTTP 200 and the known smoke email returns HTTP 401 with
an invalid password, never `RESOURCE_NOT_FOUND`.

## Production Restore

Restoring `christopherbell` is destructive and requires a maintenance window,
a stopped website service, an explicit target check, a current backup, and a
successful validation restore. Only then run the equivalent restore with:

```text
--nsTo=christopherbell.* --drop
```

Recompute the inventory and repeat the port-8081 checks before starting or
restarting `ChristopherBellDev` on port 8080.

## WSL Migration Fallback

The original Debian WSL MongoDB files and the archives under
`A:\Projects\christopherbell.dev-backups` remain rollback evidence throughout
the migration soak. Do not unregister WSL, delete its database files, or remove
those archives during initial cutover. If native inventory or smoke checks fail,
stop native services and restart the preserved WSL production path.

Record the archive path, SHA-256, source and target inventories, operator,
timestamp, HTTP results, and any rollback action in the migration test report.
