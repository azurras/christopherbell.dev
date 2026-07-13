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
- `cloudflared`: Automatic startup and restart-on-failure recovery.
- `ChristopherBellAutoDeploy`: SYSTEM principal, highest privileges, AtStartup
  trigger, and bounded restart policy.

Verify the entire contract with one command:

```powershell
.\prod.cmd verify-startup
```

It requires all three services to be Running and Automatic, confirms the
automatic deployment task is enabled, exercises the native home/login smoke
checks, and requires the public URL to return HTTP 200.

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

`sensor-install` downloads only the pinned official PawnIO 2.2.0 installer, verifies its SHA-256
and publisher thumbprint, scans it with Microsoft Defender, installs it silently, verifies the
registered version and running driver, scans again, and leaves sensors disabled. The installer is
staged under a new random directory whose ACL allows only SYSTEM and Administrators; reparse points
are rejected and the ACL, hash, and signer are revalidated immediately before elevation. Exit code
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

## Logs and Diagnostics

Start with:

```powershell
.\prod.cmd status
.\prod.cmd logs
Get-WinEvent -LogName System -MaxEvents 200 |
  Where-Object ProviderName -eq 'Service Control Manager'
Get-Service MongoDB,ChristopherBellDev,cloudflared |
  Select-Object Name,Status,StartType
```

- Website/WinSW logs: `C:\ProgramData\christopherbell.dev\logs`.
- Auto-deploy state: `C:\ProgramData\christopherbell.dev\state\auto-deploy.json`.
- cloudflared: Windows service state and Windows Application/System event logs.
- MongoDB: the log configured by the native MongoDB Server installation.

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
Get-Service MongoDB,ChristopherBellDev,cloudflared |
  Select-Object Name,Status,StartType
.\prod.cmd verify-startup
.\prod.cmd auto-status
```

The public site must remain available before interactive login. All three
services must be Running and Automatic, and the auto-deploy state must record a
recent check.

## Failure Recovery

- Candidate failure: production remains on the current release; inspect build
  and candidate logs.
- Port-8080 verification failure: automatic release rollback restores the prior
  junction.
- MongoDB failure: stop deployment activity, inspect the native MongoDB service
  and logs, and restore only from a verified archive after explicit approval.
- cloudflared failure: restart the native service, inspect Windows events, and
  validate the configured public route. Do not reinstall WSL tunnel packages.
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
