# Native Windows Production Operations

This runbook is the source of truth for installing, migrating, deploying, and
recovering `christopherbell.dev` on its Windows production computer. MongoDB and
the website start at Windows boot without an interactive login. WSL is retained
only as a temporary migration fallback.

Never delete the WSL database or verified archives during migration. Any
collection, document-count, or index mismatch blocks cutover. Do not stop the
existing port-8080 process until the native candidate passes on port 8081.

## Prerequisites

- Elevated PowerShell 7.
- Java 25, native Git, Node.js, MongoDB Server, MongoDB Database Tools, and
  `mongosh` installed on Windows.
- The `MongoDB` Windows service and access to the existing Debian WSL source.
- A real production account email for the mutation-free login smoke check.
- Pester 5.x for development tests only.

The installer downloads WinSW v2.12.0 x64 from the official GitHub release and
requires SHA-256
`05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA`.

## ProgramData Layout and Security

Runtime state lives under `C:\ProgramData\christopherbell.dev`:

- `config`: `deploy.json` and secret `app.env`; ACL limited to SYSTEM and
  Administrators.
- `releases`: immutable directories named by full Git SHA.
- `current` and `previous`: validated release junctions.
- `service`, `tools`, `state`, `locks`, `logs`, `worktrees`, and `gradle-home`:
  service artifacts and bounded operational state.

Backups use `backupRoot` from `deploy.json`; the checked-in example points to
`A:\Projects\christopherbell.dev-backups`.

## First Installation

From the repository root in elevated PowerShell:

```powershell
.\prod.cmd install
```

On a new machine, this creates configuration examples and then stops because
placeholder values are intentionally invalid. Edit:

```text
C:\ProgramData\christopherbell.dev\config\deploy.json
C:\ProgramData\christopherbell.dev\config\app.env
```

Use absolute native Windows paths, a real smoke-account email, a stable JWT
secret of at least 32 characters, and the native MongoDB URI. Never commit or
paste `app.env`. Preview and finish installation:

```powershell
.\prod.cmd install -WhatIf
.\prod.cmd install
```

The resulting `ChristopherBellDev` service depends on `MongoDB`, starts
automatically, restarts after failure, and passes secrets only through its
process environment.

## Pre-Cutover Validation

Run all automated tests and previews before host changes:

```powershell
Invoke-Pester .\ops\production\windows\tests -Output Detailed
.\prod.cmd deploy -WhatIf
.\prod.cmd migrate -WhatIf
```

`migrate -WhatIf` creates and validates a fresh compressed archive plus source
inventory but does not stop WSL or restore the production target.

## MongoDB Migration

Schedule a maintenance window. Confirm the new archive, SHA-256 sidecar, and
inventory JSON exist. Then explicitly authorize the write freeze:

```powershell
.\prod.cmd migrate -ConfirmCutover
```

The command stops the configured WSL website and WSL MongoDB processes after
the verified archive and source inventory are complete, then starts native
MongoDB. WSL database files remain untouched as the rollback source. The command
restores first to `christopherbell_restore_check` and compares canonical
collection counts and indexes before restoring `christopherbell`. Any mismatch
stops native MongoDB, and attempts to restart WSL MongoDB and the WSL website.

Before ending the window, run a native candidate on port 8081 and confirm:

- `GET /` returns HTTP 200.
- The configured smoke email with an intentionally wrong password returns HTTP
  401 and never `RESOURCE_NOT_FOUND`.
- Candidate logs contain no scheduler or monthly catch-up import start.

## Deploy and Automatic Updates

Manual deployment:

```powershell
.\prod.cmd deploy
```

The command resolves freshly fetched `origin/main`, builds in a disposable
detached worktree, validates on 8081, switches release junctions, restarts the
service, verifies 8080, and automatically restores the prior junction on
failure.

Install the boot-persistent one-minute poller:

```powershell
.\prod.cmd auto-install
.\prod.cmd auto-status
```

The `ChristopherBellAutoDeploy` Scheduled Task runs as SYSTEM at startup. It
uses outbound `git ls-remote`; it exposes no port and stores no GitHub token.
Failed SHAs observe the configured backoff, while a newer SHA is immediately
eligible.

## Daily Operations

```powershell
.\prod.cmd status
.\prod.cmd logs
.\prod.cmd releases
.\prod.cmd restart
.\prod.cmd backup
.\prod.cmd rollback -WhatIf
.\prod.cmd rollback
```

Rollback requires valid `current` and `previous` junctions and reverts its own
switch if verification fails. Backup never removes the previous known-good
archive.

## Reboot Acceptance

After backup and rollback rehearsal, reboot Windows without signing in. From a
separate device or after login, verify:

```powershell
Get-Service MongoDB,ChristopherBellDev | Select-Object Name,Status,StartType
.\prod.cmd status
.\prod.cmd auto-status
Invoke-WebRequest http://127.0.0.1:8080/
```

Both services must be Running and Automatic, port 8080 must return HTTP 200,
and the automatic-deploy task must show a recent check. Keep WSL and all source
data intact for a recommended seven-day soak with at least one successful
reboot.

## Failure Recovery

- Candidate failure: production remains untouched; inspect build/candidate logs.
- Port-8080 verification failure: automatic rollback restores the prior release.
- Native MongoDB mismatch: stop native cutover and restart the preserved WSL
  website/database path.
- Boot failure: start services manually, inspect Service Control Manager,
  WinSW, MongoDB, and auto-deploy logs, and keep WSL fallback available.

## Upgrade and Uninstall

Normal app upgrades require only a merge to `main`; the poller deploys the new
SHA. Updating operations code requires rerunning `install` and `auto-install`
from the merged checkout so ProgramData service/tools copies are refreshed.

```powershell
.\prod.cmd auto-remove
.\prod.cmd uninstall -WhatIf
.\prod.cmd uninstall
```

Uninstall removes only the website service. It preserves MongoDB, database
files, backups, secrets, releases, and WSL. Destructive cleanup is a separate,
explicitly approved operation after soak closure.
