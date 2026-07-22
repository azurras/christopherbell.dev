# Native Windows Production Operations

This runbook is the source of truth for installing, starting, deploying,
upgrading, backing up, and recovering `christopherbell.dev` on its Windows
production computer. WSL remains available for unrelated development, but it
contains no production website, MongoDB, nginx, cloudflared, or deployment
component.

## Production Topology

The boot-persistent request path is:

```text
Cloudflare edge
  -> cloudflared Windows service (Automatic)
  -> http://127.0.0.1:8080
  -> ChristopherBellDev Windows service (Automatic)
  -> MongoDB Windows service (Automatic)
```

`ChristopherBellAutoDeploy` is a SYSTEM Scheduled Task with an AtStartup
trigger. It checks `origin/main` once per minute and deploys only when the remote
SHA differs from the active release.

## Prerequisites

- Elevated PowerShell 7 at `C:\Program Files\PowerShell\7\pwsh.exe`.
- Java 25, native Git, Node.js, MongoDB Server, MongoDB Database Tools, and
  `mongosh` installed on Windows.
- cloudflared installed machine-wide with:

```powershell
winget install --id Cloudflare.cloudflared --exact --source winget --scope machine --accept-package-agreements --accept-source-agreements
```

- Pester 5.x for operations tests.
- A real production account email for the mutation-free login smoke check.
- A rotated Cloudflare tunnel token for a fresh connector installation.

The installer downloads WinSW v2.12.0 x64 from the official GitHub release and
requires SHA-256
`05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA`.
It also downloads the pinned FFmpeg `8.0.1-essentials` archive from the HTTPS
URI in `ops/production/windows/config/media-tools-manifest.json` and requires
SHA-256
`E2AAEAA0FDBC397D4794828086424D4AAA2102CEF1FB6874F6FFD29C0B88B673`
before extracting `ffmpeg.exe` or `ffprobe.exe`. The installer then records and
revalidates the individual executable hashes in the protected active-tool
manifest.

## ProgramData Layout and Security

Runtime state lives under `C:\ProgramData\christopherbell.dev`:

- `config`: `deploy.json` and secret `app.env`; ACL restricted to SYSTEM and
  Administrators.
- `releases`: immutable directories named by full Git SHA.
- `current` and `previous`: validated release junctions.
- `service`: WinSW executable, XML, and website startup script.
- `tools`: the installed production command modules used by SYSTEM tasks.
- `state`, `locks`, `logs`, `worktrees`, and `gradle-home`: bounded deployment
  state.

Backups use `backupRoot` from `deploy.json`; the checked-in example points to
`A:\Projects\christopherbell.dev-backups`.

Shared-folder originals and media-worker state are deliberately outside
ProgramData:

- `A:\Shared`: files exposed by the application. The website service retains
  full control; the `LocalService` media worker has read/execute access only.
- `A:\Shared-System`: private job, partial-output, cache, status, cancellation,
  log, lock, and pinned-tool directories. The worker can modify only its job
  processing directories and can only read the pinned-tool directory.

Both roots disable inherited ACLs. SYSTEM and Administrators retain full
control. The `ChristopherBellMediaWorker` WinSW service runs as
`NT AUTHORITY\LocalService` at below-normal priority, and its fixed profiles
produce fragmented H.264/AAC MP4 or AAC M4A output. Job JSON, source paths,
source metadata, deadlines, output paths, and tool hashes are revalidated by
the worker before media processes run.

Never commit or paste `app.env` or a tunnel token. Restrict any temporary token
file to the current administrator, use it only for first installation, and
delete it immediately after cloudflared registration succeeds.

## Fresh-Machine Setup

From the repository root in elevated PowerShell:

```powershell
.\prod.cmd install
```

The first run creates configuration examples and stops because placeholders are
invalid. Edit:

```text
C:\ProgramData\christopherbell.dev\config\deploy.json
C:\ProgramData\christopherbell.dev\config\app.env
```

Use absolute native Windows paths. Set `cloudflaredExe` to the signed
machine-wide executable, `publicUrl` to
`https://www.christopherbell.dev/`, a real smoke-account email, a stable JWT
secret of at least 32 characters, and
`SPRING_MONGODB_URI=mongodb://127.0.0.1:27017`.

Create a temporary protected file containing only the rotated Cloudflare token,
then finish setup:

```powershell
$tokenPath = 'C:\Secure\cloudflared-token.txt'
.\prod.cmd install -CloudflareTokenPath $tokenPath
Remove-Item -LiteralPath $tokenPath -Force
.\prod.cmd auto-install
.\prod.cmd deploy
.\prod.cmd verify-startup
```

When the `cloudflared` service already exists, later `install` runs validate and
refresh configuration without requiring `CloudflareTokenPath`. Setup preserves
existing `app.env` values and merges only newly introduced non-secret defaults
into `deploy.json`.

## Startup Contract

Installation configures:

- `MongoDB`: Automatic startup and restart-on-failure recovery.
- `ChristopherBellDev`: Automatic startup, dependency on MongoDB, and
  restart-on-failure recovery.
- `ChristopherBellMediaWorker`: delayed Automatic startup as LocalService,
  below-normal priority, bounded rolling logs, and restart-on-failure recovery.
- `cloudflared`: Automatic startup and restart-on-failure recovery.
- `ChristopherBellAutoDeploy`: SYSTEM principal, highest privileges, AtStartup
  trigger, and bounded restart policy.

Verify the entire contract with one command:

```powershell
.\prod.cmd verify-startup
```

It requires all three services to be Running and Automatic, confirms the
automatic deployment task is enabled, exercises the native home/login smoke
checks, and requires the public URL to return HTTP 200. The current command's
three-service assertion covers `MongoDB`, `ChristopherBellDev`, and
`cloudflared`; inspect `ChristopherBellMediaWorker` separately as shown below.

## CPU Temperature Provider

CPU temperature is disabled by default in protected `deploy.json`. The website remains healthy
and reports the metric unavailable when PawnIO is absent or disabled. Provider lifecycle commands
are local elevated operations only:

```powershell
.\prod.cmd sensor-status
.\prod.cmd sensor-install -WhatIf
.\prod.cmd sensor-install
.\prod.cmd sensor-enable -WhatIf
.\prod.cmd sensor-enable
.\prod.cmd sensor-disable
```

`sensor-install` downloads only the pinned official PawnIO 2.2.0 installer (registered by Windows
as product version `2.2.0.0`), verifies its SHA-256
and publisher thumbprint, scans it with Microsoft Defender, installs it silently, verifies the
registered version and running driver, scans again, and leaves sensors disabled. The installer is
staged under a new random directory whose ACL allows only SYSTEM and Administrators; reparse points
are rejected and the ACL, hash, and signer are revalidated immediately before launch from the
elevated shell. Exit code
3010 means a reboot is required; stop and obtain explicit reboot approval.

`auto-install` likewise replaces the deployed `tools` tree while its scheduled task is stopped,
rejects reparse points, and restricts the entire tree to SYSTEM and Administrators before the task
is registered again. These protections prevent a standard local account from replacing content
that a later elevated installer or SYSTEM task would execute.

Never add a Defender exclusion. Enable only after the elevated direct probe and a non-production
port candidate return a plausible CPU Celsius value for three refresh windows with stable process
counts and no active Defender detection. On any problem, run `sensor-disable` first. If the driver
must be removed, use the verified PawnIO entry in Windows Installed Apps, then verify registry,
driver, Defender, and website state; never delete driver files manually.

When sensors are enabled, `verify-startup` also fails closed unless PawnIO is installed, its signed
driver is running, Defender reports no active sensor-provider threat, the live extracted resources
retain their pinned hashes and protected ACLs, and a bounded direct probe returns plausible Celsius.

CPU sensor resources are extracted beneath the protected
`config\command-center-sensors` base. A sensor-enabled process holds an
exclusive file lease, builds and verifies each fresh resource set under a
nonmatching staging name, atomically publishes the complete nonce directory,
and then validates and removes every matching stale sibling. Its final
publication step writes an ACL-protected owner marker containing the process
PID and start time. Windows DACL inheritance is disabled and verified through
the fixed JNA bridge because Java NIO ACL entries alone cannot express that
protection flag. Any failure before the marker is published requires strict
rollback of the fresh directory. Deployment smoke candidates force native
sensors off and cannot participate in that lifecycle. Startup verification
matches owner markers to the process listening on the configured production
port and waits up to 15 seconds to find exactly one current live directory.
Stale directories and invalid nonce names are excluded from the live set;
unresolved zero or multiple current live counts still fail closed and sensors
must be disabled.

The pinned LibreHardwareMonitor 0.9.6 probe runs through Windows PowerShell 5.1
because it calls full .NET Framework synchronization APIs that are unavailable
under PowerShell 7. The probe script treats every PowerShell error as
terminating; zero exit with stderr or empty, malformed, or implausible output
still fails startup verification.

The CPU card prefers LibreHardwareMonitor's `CPU Package` sensor, then
`Core Max`, then the maximum remaining actual CPU temperature.
`Distance to TjMax` values are thermal headroom and are never displayed as
temperature. Startup verification derives the expected script and library
hashes from the active `current\app.jar`, so the protected resources remain
verifiable if the release junction rolls back.

The application reads the active release's bounded `release.json` from its
working directory and validates the 40-character SHA for the administrator-only
Application commit card. This remains part of the merge-only application
release and does not depend on refreshing the installed service launcher.

## Application Releases

Normal releases require only a merge or push to `origin/main`. The poller reads
the remote SHA without an inbound webhook or GitHub token. Unchanged checks do
not fetch, build, restart, or modify the database.

Manual deployment remains available:

```powershell
.\prod.cmd deploy -WhatIf
.\prod.cmd deploy
```

Deployment fetches `origin/main`, builds a disposable detached worktree,
validates a candidate on port 8081, switches release junctions, starts the
service, verifies port 8080, and restores the prior junction if verification
fails.

Monitor automatic deployment:

```powershell
.\prod.cmd auto-status
Get-Content C:\ProgramData\christopherbell.dev\state\auto-deploy.json -Raw
```

Failed SHAs observe `autoDeployFailureBackoffSeconds`; a newer SHA is eligible
immediately.

## Daily Operations

```powershell
.\prod.cmd status
.\prod.cmd logs
.\prod.cmd releases
.\prod.cmd restart
.\prod.cmd backup
.\prod.cmd rollback -WhatIf
.\prod.cmd rollback
.\prod.cmd verify-startup
```

Equivalent Makefile targets include `prod-status`, `prod-deploy`,
`prod-backup`, and `prod-verify-startup`.

## Shared-Folder Operations

The website keeps its existing `ChristopherBellDev` service identity and
security context. It owns shared-folder authorization, originals, uploads,
recycle operations, audit records, and job admission. Only media inspection
and transcoding cross into the separate `ChristopherBellMediaWorker` service,
which runs as `NT AUTHORITY\LocalService`.

Enable the feature through the protected
`C:\ProgramData\christopherbell.dev\config\app.env`; do not put secrets in the
checked-in examples or this runbook:

```properties
APP_SHARED_FOLDER_ENABLED=true
APP_SHARED_FOLDER_ROOT=A:/Shared
APP_SHARED_FOLDER_SYSTEM_ROOT=A:/Shared-System
```

The production defaults already select those roots. After changing the
protected environment file, apply it with the existing production workflow:

```powershell
.\prod.cmd restart
```

`A:\Shared` contains user-visible originals. `A:\Shared-System` is private
runtime state with these checked-in layouts:

- `shared-folder-upload-staging` and `shared-folder-upload-quarantine`: owned
  resumable-upload temporary state.
- `shared-folder-recycle` and `shared-folder-recycle-replaced`: recoverable
  deleted or replaced payloads.
- `shared-folder-media-jobs`, `shared-folder-media-staging`,
  `shared-folder-media-partial`, `shared-folder-media-status`, and
  `shared-folder-media-cancel`: bounded worker handoff and in-progress state.
- `shared-folder-media-cache`: completed derived playback media. Originals are
  never replaced by cache entries.
- `shared-folder-media-tools`: immutable pinned tool versions plus the active
  hash manifest.
- `shared-folder-media-logs` and `shared-folder-media-locks`: worker WinSW logs
  and the single-worker lock.

MongoDB holds `shared_folder_media_jobs`, recycle metadata, and the
`shared_folder_audit` collection. Default retention is 30 days for recycle
payloads and 180 days for audit records. Completed media cache is bounded to
250 GB and is evicted least-recently-used while active readers remain leased.
Every 15 minutes the website coordinates one host/Mongo lease before expiring
abandoned uploads, purging expired recycle content, evicting cache, and
reconciling worker results. Do not manually delete job, partial, recycle, or
cache files while either service is running.

Installation disables ACL inheritance on both roots. SYSTEM and
Administrators retain full control. The unchanged website identity retains the
access needed to manage visible and private state. LocalService receives only
read/execute on `A:\Shared`, `A:\Shared-System`, and pinned tools, with Modify
limited to the worker job, staging, partial, cache, status, cancellation, log,
and lock directories. Re-run `prod.cmd install` to reconcile the checked-in
directory, ACL, pinned-tool, and WinSW definitions; do not repair these ACLs by
granting broad drive-level access.

Inspect the sidecar without exposing command lines or job descriptors:

```powershell
Get-Service ChristopherBellMediaWorker |
  Select-Object Name,Status,StartType
Get-Content A:\Shared-System\shared-folder-media-logs\ChristopherBellMediaWorker.out.log -Tail 100
Get-Content A:\Shared-System\shared-folder-media-logs\ChristopherBellMediaWorker.err.log -Tail 100
```

WinSW keeps 14 worker output/error files at 10 MiB each with size-only
rotation. Alert when the worker is not Running/Automatic, the private volume
approaches the configured 100 GB free-space reserve, or bounded logs/audit
contain repeated `MAINTENANCE_*_FAILED`, `worker_failure`, `timed_out`,
`output_limit`, `source_changed`, or `insufficient_space` outcomes. A stopped
worker isolates transcoding failure: ordinary website traffic, authenticated
listing/downloads, and browser-native previews remain owned by the website.

`prod.cmd backup` covers MongoDB metadata, including shared-folder audit,
recycle, and media-job documents; it does not copy `A:\Shared` or
`A:\Shared-System`. Back up originals and recoverable recycle payloads with a
separate ACL-preserving filesystem backup. Cache, staging, partial, status,
cancellation, and logs are rebuildable operational state and should not be
treated as the only copy of content. Quiesce shared-folder writes and the
worker before taking a point-in-time filesystem copy that must correspond to a
MongoDB backup.

After shared-folder application or operations changes merge, use the normal
update flow and refresh the worker process so it loads the installed module:

```powershell
git fetch origin main
git switch main
git pull --ff-only origin main
.\prod.cmd install
.\prod.cmd auto-install
Restart-Service ChristopherBellMediaWorker
.\prod.cmd deploy
.\prod.cmd verify-startup
Get-Service ChristopherBellMediaWorker |
  Select-Object Name,Status,StartType
```

The Gradle entry point `gradlew.bat :website:sharedFolderVerification` runs the
full Java and browser suites, PowerShell 7 worker coverage, PowerShell 7
installer/operations coverage, and the same installer/operations compatibility
fixtures under Windows PowerShell 5.1. Both shells require Pester 5; the Gradle
task includes the redirected Documents `PowerShell\Modules` tree in the 5.1
lookup before failing closed if Pester 5 is unavailable. The production
installer itself still requires elevated PowerShell 7.

For emergency isolation, preserve both roots and disable access before any
rollback:

```powershell
# Set APP_SHARED_FOLDER_ENABLED=false in the protected app.env first.
.\prod.cmd restart
Stop-Service ChristopherBellMediaWorker
.\prod.cmd rollback -WhatIf
.\prod.cmd rollback
.\prod.cmd verify-startup
```

Do not delete originals, recycle payloads, job metadata, or pinned tools during
recovery. If the incident includes a worker service-definition change, use a
clean, reviewed checkout of the prior known-good commit and run
`prod.cmd install` from that checkout to restore its checked-in WinSW/script
definitions; then start the worker, run `prod.cmd verify-startup`, inspect the
worker explicitly, and re-enable shared routes only after both checks pass.

## Logs and Diagnostics

Start with:

```powershell
.\prod.cmd status
.\prod.cmd logs
Get-WinEvent -LogName System -MaxEvents 200 |
  Where-Object ProviderName -eq 'Service Control Manager'
Get-Service MongoDB,ChristopherBellDev,ChristopherBellMediaWorker,cloudflared |
  Select-Object Name,Status,StartType
```

- Website/WinSW logs: `C:\ProgramData\christopherbell.dev\logs`.
- Auto-deploy state: `C:\ProgramData\christopherbell.dev\state\auto-deploy.json`.
- cloudflared: Windows service state and Windows Application/System event logs.
- MongoDB: the log configured by the native MongoDB Server installation.
- WinSW keeps seven 10 MiB output logs with size-only rotation. Do not use
  `roll-by-size-time` or `autoRollAtTime`: WinSW can break the output stream at
  a live time boundary and leave the Java child running outside the service.
- If `ChristopherBellDev` is stopped while port 8080 still responds, treat the
  listener as a possible orphan. Verify the listener PID and its full parent
  chain before terminating anything, refresh the installed service definition,
  and require `verify-startup` to pass before resuming sensor operations.

Do not print service command lines because the cloudflared service registration
contains its tunnel credential.

## Native MongoDB Backup and Restore

Create a compressed archive and dry-run verification:

```powershell
.\prod.cmd backup
```

The command connects explicitly to IPv4 loopback, writes
`christopherbell-native-<UTC timestamp>.archive.gz`, verifies the archive with
`mongorestore --dryRun`, and writes a SHA-256 JSON sidecar. It never removes
older verified archives.

Before restoring production, verify the sidecar hash, restore into a disposable
database, compare required collections/counts/indexes, and exercise a candidate
release against that database. Production restore is an explicit maintenance
operation; ordinary deployment and application rollback do not modify MongoDB.

See [MongoDB Backup and Restore](mongodb-backup-restore.md) for detailed restore
commands.

## Release Rollback

```powershell
.\prod.cmd rollback -WhatIf
.\prod.cmd rollback
```

Rollback requires valid `current` and `previous` release junctions. If the
restored release fails endpoint verification, the command restores the original
junctions and service. It does not roll back MongoDB or cloudflared.

## cloudflared Token Rotation

In the authenticated Cloudflare dashboard, rotate the token for tunnel `mugi`.
Install the replacement token from a protected temporary file:

```powershell
.\prod.cmd install -CloudflareTokenPath C:\Secure\cloudflared-token.txt
Remove-Item -LiteralPath C:\Secure\cloudflared-token.txt -Force
.\prod.cmd verify-startup
```

Rotation invalidates all older connector tokens. Confirm Cloudflare reports a
healthy `windows_amd64` replica before ending the maintenance window.

## cloudflared Upgrade

From an elevated terminal:

```powershell
winget upgrade --id Cloudflare.cloudflared --exact --source winget --scope machine --accept-package-agreements --accept-source-agreements
Restart-Service cloudflared
.\prod.cmd verify-startup
```

Or use:

```powershell
make prod-cloudflare-upgrade
make prod-verify-startup
```

An application release does not upgrade cloudflared. A cloudflared upgrade does
not deploy or restart the Spring Boot application.

## Reboot Acceptance

Perform reboot acceptance from an approved maintenance window:

```powershell
Restart-Computer
```

After Windows starts, do not manually start production services. Verify:

```powershell
Get-Service MongoDB,ChristopherBellDev,ChristopherBellMediaWorker,cloudflared |
  Select-Object Name,Status,StartType
.\prod.cmd verify-startup
.\prod.cmd auto-status
```

The public site must remain available before interactive login. All four
services must be Running and Automatic, and the auto-deploy state must record a
recent check. `verify-startup` checks the website/MongoDB/cloudflared trio;
retain the explicit worker service check until that command includes the
sidecar.

## Failure Recovery

- Candidate failure: production remains on the current release; inspect build
  and candidate logs.
- Port-8080 verification failure: automatic release rollback restores the prior
  junction.
- MongoDB failure: stop deployment activity, inspect the native MongoDB service
  and logs, and restore only from a verified archive after explicit approval.
- cloudflared failure: restart the native service, inspect Windows events, and
  validate the configured public route. Do not reinstall WSL tunnel packages.
- Media-worker failure: stop `ChristopherBellMediaWorker`, leave originals and
  recycle data in place, inspect bounded worker logs and free space, and keep
  shared routes disabled if private-state integrity is uncertain. Website and
  database recovery do not require deleting derived media state.
- Startup-task failure: rerun `auto-install`, inspect Task Scheduler history and
  `auto-deploy.json`, then run `verify-startup`.

## Updating Operations Code

After operations changes merge to `main`, refresh protected runtime copies:

```powershell
git fetch origin main
git switch main
git pull --ff-only origin main
.\prod.cmd install
.\prod.cmd auto-install
.\prod.cmd verify-startup
```

Existing secrets are preserved. New non-secret deploy defaults are merged only
when absent.

## Uninstall and Retirement

```powershell
.\prod.cmd auto-remove
.\prod.cmd uninstall -WhatIf
.\prod.cmd uninstall
```

`uninstall` removes only `ChristopherBellDev`. It preserves native MongoDB,
cloudflared, database files, backups, secrets, and releases. Removing MongoDB,
cloudflared, or their data requires a separate explicit destructive-cleanup
approval.

The Debian WSL distribution may remain installed for unrelated work. Its
production packages and state must stay absent; future website operations must
use this native Windows runbook.
