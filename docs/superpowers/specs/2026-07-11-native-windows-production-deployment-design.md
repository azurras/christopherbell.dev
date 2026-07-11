# Native Windows Production Deployment Design

## Document Status

Approved for implementation planning on 2026-07-11.

## Problem

`christopherbell.dev` runs on a Windows development computer but production currently depends on WSL, a WSL-local MongoDB server, and an interactive Gradle process. WSL adds a second lifecycle, networking namespace, service manager, and filesystem translation layer. The application disappears when the desktop/WSL process ends and requires manual recovery after reboot.

The application changes frequently, so the replacement must make installation and deployment routine, versioned, testable, and reversible while preserving all production MongoDB data.

## Decision

Remove WSL from the production, deployment, and development workflow. Run MongoDB and the Spring Boot application as native Windows services. Build and test with native Windows Java, Node, Git, PowerShell, and `gradlew.bat`.

Retain the existing WSL database and distro unchanged as a temporary rollback source until native Windows production passes migration validation, service-restart validation, a full reboot test, and an agreed soak period.

## Goals

- Start MongoDB and the website during Windows boot without an interactive login.
- Make Windows Service Control Manager the only production process owner.
- Deploy exactly the latest merged `origin/main` commit with one command.
- Build from a clean detached Windows worktree, never the caller's checkout.
- Validate a candidate on port 8081 before replacing port 8080.
- Use atomic, versioned releases with automatic rollback.
- Preserve and verify every MongoDB collection during WSL-to-Windows migration.
- Keep credentials out of Git, command lines, and ordinary logs.
- Provide status, logs, restart, release listing, rollback, backup, and uninstall commands.
- Document installation, migration, operation, recovery, troubleshooting, and reboot acceptance.

## Non-Goals

- Removing or deleting the WSL distro during initial implementation.
- Automatically deploying on every GitHub push.
- Deploying unmerged pull-request branches.
- Moving production to a remote host or container platform.
- Replacing GitHub Actions CI.

## Operator Interface

The root `prod.cmd` command delegates to signed/local PowerShell scripts under `ops/production/windows/`:

```text
prod install
prod migrate
prod deploy
prod status
prod logs
prod restart
prod releases
prod rollback
prod backup
prod uninstall
```

An optional root Makefile forwards equivalent `prod-*` targets to `prod.cmd`, but Make is not required on Windows.

## Native Runtime

MongoDB runs through its existing `MongoDB` Windows service with an explicitly configured data directory and log path under `C:\ProgramData\MongoDB`. The service uses automatic startup.

The website runs as `ChristopherBellDev` through WinSW. WinSW executes the configured Java 25 binary with:

```text
java.exe -Xrs -jar C:\ProgramData\christopherbell.dev\current\app.jar --spring.profiles.active=prod --server.port=8080
```

The website service depends on `MongoDB`, starts automatically, restarts after unexpected exits, and writes rotated application/wrapper logs under `C:\ProgramData\christopherbell.dev\logs`.

## Filesystem Layout

```text
C:\ProgramData\christopherbell.dev\
  current -> releases\<sha>
  previous -> releases\<sha>
  releases\
    <sha>\
      app.jar
      release.json
  config\
    app.env
    deploy.json
  service\
    ChristopherBellDev.exe
    ChristopherBellDev.xml
  logs\
  backups\
  worktrees\
```

`app.env` contains runtime secrets and receives an ACL limited to Administrators, SYSTEM, and the service identity. `deploy.json` contains non-secret settings such as repository path, alternate port, smoke account email, retention count, Java path, Node path, and MongoDB tools path.

## Configuration and Secrets

Because a raw `.env` file is not a native Windows service feature, the WinSW XML references only non-secret paths. A small PowerShell launcher reads `app.env`, sets allowlisted environment variables in the child process, and then starts Java without printing values. Secret values never appear in service arguments.

The installer preserves existing configuration files, creates `.example` templates when missing, validates required values, and applies restrictive ACLs.

## Clean Build Source

`prod deploy` always fetches `origin/main`, resolves its full SHA, and creates a detached Git worktree under the ProgramData deployment workspace. It does not switch, reset, clean, or otherwise modify the caller's checkout.

The clean worktree runs:

```powershell
.\gradlew.bat --no-daemon :website:build
```

with a deployment-local `GRADLE_USER_HOME` and explicit `NODE_EXE`. The resulting executable boot JAR is copied into a staging release directory with provenance metadata. The worktree is removed in a `finally` path.

## Candidate Smoke Profile

Add `application-deploy-smoke.yml`. The profile combines with `prod` and disables scheduled tasks plus the WFL monthly startup catch-up import, preventing a second candidate process from mutating production data.

Candidate execution uses native Windows Java on port 8081 with the Windows MongoDB database. Validation requires:

- `/` returns HTTP 200;
- a MongoDB-backed read endpoint succeeds;
- a configured known account with a deliberately invalid diagnostic password returns `INVALID_TOKEN`, not `RESOURCE_NOT_FOUND`;
- the candidate remains alive until checks finish.

The candidate is stopped before production switching. Account identifiers and diagnostic credentials are not printed.

## Atomic Release Switching

Windows directory junctions provide stable `current` and `previous` paths. Deployment creates replacement junctions under temporary names and renames them into place while the website service is stopped for the shortest possible interval.

Flow:

1. Build and smoke-test the candidate while production stays on port 8080.
2. Record the active release.
3. Stop `ChristopherBellDev`.
4. Point `previous` to the former active release.
5. Point `current` to the verified candidate.
6. Start `ChristopherBellDev`.
7. Poll port 8080 and repeat the smoke checks.
8. If verification fails, restore the prior junction, restart, verify rollback, and exit non-zero.

Retention never deletes active or previous releases.

## WSL-to-Windows MongoDB Migration

Migration is a dedicated operator command and maintenance window, not part of ordinary deployment.

1. Verify the existing BSON archive and JSON exports.
2. Take a fresh WSL `mongodump` archive and checksum immediately before cutover.
3. Record WSL database, collection, document-count, and index inventories.
4. Stop the existing WSL website process to freeze writes; leave WSL MongoDB running for export.
5. Start the native Windows MongoDB service with a clean/confirmed target database.
6. Restore the archive into a temporary Windows validation database.
7. Compare every collection count and required index.
8. Run the native candidate against the temporary restored database.
9. Restore/switch the validated data into the Windows `christopherbell` database.
10. Run the native candidate on port 8081 against the final Windows database.
11. Install/start the Windows website service on port 8080.
12. Verify login lookup, Mongo-backed endpoints, counts, and indexes.

Any mismatch blocks cutover. WSL MongoDB files, archives, and JSON exports remain untouched throughout the soak period.

## Rollback

Application rollback switches `current` and `previous`, restarts the Windows service, and verifies it.

Migration rollback stops the Windows website service, stops Windows MongoDB, restarts WSL MongoDB and the last known-good WSL website process, and verifies the original WSL database. No WSL data is deleted during implementation.

## Installation

`prod install` is idempotent and requires elevation only for service, ProgramData, ACL, and firewall operations. It:

- validates native Java 25, Node, Git, MongoDB Database Tools, MongoDB service, and PowerShell;
- downloads or verifies a pinned WinSW release with SHA-256 validation;
- creates ProgramData directories and ACLs;
- installs/refreshes the WinSW service definition;
- configures dependency on `MongoDB`, automatic startup, and recovery;
- preserves existing secrets and releases;
- deploys through the normal candidate path after MongoDB migration is complete.

## Observability

- `prod status` shows Windows service state, active/previous SHA, port listeners, MongoDB connectivity, and last verification.
- `prod logs` tails application and WinSW wrapper logs.
- Deployment logs show named phases and actionable errors without secrets.
- `release.json` records Git SHA, source remote, build timestamp, deployment timestamp, and verification result.

## Testing

Pester tests exercise PowerShell functions with temporary directories and stub executables. Coverage includes configuration validation, `origin/main` resolution, clean worktree lifecycle, locking, candidate failure, junction switching, automatic rollback, retention, migration inventory comparison, idempotent install, and uninstall safety.

Java configuration tests verify the smoke profile disables scheduled/startup mutations. Shell-independent tests validate Makefile/prod.cmd command forwarding.

Host acceptance includes candidate validation, service restart recovery, MongoDB restart recovery, application rollback, migration rollback rehearsal, and a full Windows reboot with no login before verifying that both services and port 8080 started automatically.

## Documentation

Repository operations documentation covers prerequisites, native development, WinSW provenance, first install, environment files, ACLs, MongoDB migration, daily deployment, rollback, logs, backup/restore, failure behavior, upgrade, uninstall, WSL fallback, and reboot acceptance.

## Acceptance Criteria

- WSL is not required for build, test, deployment, MongoDB, or production runtime.
- MongoDB and the website start during Windows boot without user login.
- `prod deploy` deploys the exact latest `origin/main` commit from a clean Windows worktree.
- Production remains on port 8080 until candidate verification passes.
- Failed production verification restores the prior release automatically.
- Native MongoDB matches the frozen WSL source collection-for-collection and index-for-index.
- Secrets are absent from Git, process arguments, and logs.
- A full reboot proves native automatic startup.
- WSL remains intact as a documented fallback until the soak period is explicitly closed.
