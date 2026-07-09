# MongoDB Backup and Restore Runbook

Use this runbook for production MongoDB backups, restore preparation, and
restore smoke checks for `christopherbell.dev`. Run the commands from a host
that has network access to MongoDB and the MongoDB Database Tools installed.

## Required Environment

Set these values in the shell that runs the backup or restore commands:

```bash
export MONGODB_URI="mongodb://<host>:<port>"
export MONGODB_DATABASE="christopherbell"
export BACKUP_DIR="/var/backups/christopherbell.dev/mongodb"
export BACKUP_DATE="$(date -u +%Y%m%dT%H%M%SZ)"
export BACKUP_ARCHIVE="$BACKUP_DIR/$MONGODB_DATABASE-$BACKUP_DATE.archive.gz"
```

Use the production MongoDB URI format required by the deployment. If the URI
already includes the database name, keep `MONGODB_DATABASE` set to the same
database so archive names and restore commands stay explicit.

Optional values for restore validation:

```bash
export RESTORE_DATABASE="christopherbell_restore_check"
export RESTORE_URI="mongodb://<host>:<port>"
export SERVER_PORT=8082
```

## Storage Location

Store production archives under the host's protected backup area, rooted at:

```text
/var/backups/christopherbell.dev/mongodb
```

Archive filenames should use the database and UTC timestamp:

```text
christopherbell-YYYYMMDDTHHMMSSZ.archive.gz
```

Move or replicate completed archives to the production backup storage provider
used for the host. Keep local filesystem permissions limited to the service or
operator account that performs backups.

## Backup

Create the backup directory and run `mongodump` with a compressed archive:

```bash
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

mongodump \
  --uri="$MONGODB_URI" \
  --db="$MONGODB_DATABASE" \
  --archive="$BACKUP_ARCHIVE" \
  --gzip
```

Record the resulting archive path in the deployment or incident log for the
change window.

## Verify a Backup

Confirm that the archive exists and is not empty:

```bash
test -s "$BACKUP_ARCHIVE"
ls -lh "$BACKUP_ARCHIVE"
```

List archive contents without restoring them:

```bash
mongorestore \
  --archive="$BACKUP_ARCHIVE" \
  --gzip \
  --dryRun
```

If either check fails, do not delete the previous known-good backup.

## Restore

Restores can overwrite data. Prefer restoring into a staging or temporary
validation database first. Only restore into production after confirming the
target URI, target database, and maintenance window.

Restore into a validation database:

```bash
mongorestore \
  --uri="$RESTORE_URI" \
  --nsFrom="$MONGODB_DATABASE.*" \
  --nsTo="$RESTORE_DATABASE.*" \
  --archive="$BACKUP_ARCHIVE" \
  --gzip \
  --drop
```

Restore into the original database only when the production target has been
confirmed:

```bash
mongorestore \
  --uri="$MONGODB_URI" \
  --db="$MONGODB_DATABASE" \
  --archive="$BACKUP_ARCHIVE" \
  --gzip \
  --drop
```

## Restore Smoke Check

After restoring into a validation database, start the app against that database
on a non-production port:

```bash
export SPRING_PROFILES_ACTIVE=local
export SPRING_DATA_MONGODB_URI="$RESTORE_URI/$RESTORE_DATABASE"
export SERVER_PORT=8082

./gradlew :website:bootRun
```

In another shell, request a public page:

```bash
curl -i "http://localhost:$SERVER_PORT/"
```

Expected result:

- HTTP status is `200 OK`.
- The response body contains the public home page title or markup.
- The application logs do not show MongoDB connection or authentication errors.

For production restores, repeat the same smoke pattern against the production
process after the restore and deployment restart are complete.

## Operational Notes

- Keep at least one previously verified archive until the new archive has passed
  verification.
- Do not paste credentials into tickets, pull requests, or shell history shared
  with other users.
- Test restore procedures periodically against a non-production MongoDB
  instance.
- Document backup archive path, restore target, operator, timestamp, and
  smoke-check result in the deployment or incident log.
