# Boot-Persistent Production Deployment Design

## Document Status

Approved for implementation planning on 2026-07-11.

## Problem

`christopherbell.dev` currently runs as a Gradle `bootRun` process launched from an interactive Windows/WSL session. The process is not owned by a service manager, so it disappears when the desktop or WSL session ends and does not return automatically after a Windows reboot. Deployments also depend on the state of a mutable checkout and require several manual commands.

The production host is also the development machine. Any replacement workflow must preserve the live MongoDB data, avoid stopping port 8080 until a candidate has passed validation, and make frequent deployments from merged GitHub code routine and reversible.

## Goals

- Start MongoDB and the website automatically when the Windows computer starts, without requiring an interactive login.
- Run the website under systemd rather than an interactive Gradle process.
- Deploy the exact current commit at `origin/main` with one memorable command.
- Build and test from a clean detached worktree rather than the active development checkout.
- Validate a candidate on a non-production port before switching production.
- Make the production switch atomic and automatically reversible.
- Keep multiple previous releases for operator-initiated rollback.
- Keep production credentials out of Git, process arguments, and deployment logs.
- Document installation, operation, deployment, rollback, recovery, troubleshooting, and removal.

## Non-Goals

- Provisioning a remote server or container platform.
- Moving MongoDB out of the existing Debian WSL distribution.
- Automatically deploying unmerged pull-request branches.
- Running deployments automatically when GitHub changes; deployment remains an explicit operator action.
- Replacing the existing MongoDB backup and restore runbook.

## Operator Interface

The repository root will expose these targets:

```text
make prod-install
make prod-deploy
make prod-status
make prod-logs
make prod-restart
make prod-rollback
make prod-releases
make prod-uninstall
```

The Makefile is a small interface only. Version-controlled scripts under `ops/production/` own validation, installation, deployment, retention, rollback, and status logic. Scripts must fail on unset variables, failed commands, and failed pipelines.

## Filesystem Layout

```text
/opt/christopherbell.dev/
  current -> releases/<git-sha>/
  previous -> releases/<prior-git-sha>/
  releases/
    <git-sha>/
      app.jar
      release.env

/etc/christopherbell.dev/
  app.env
  deploy.env

/run/lock/
  christopherbell-deploy.lock
```

`release.env` records non-secret provenance such as the full Git SHA, build timestamp, and source remote. `app.env` contains runtime secrets such as `APP_JWT_SECRET`, `RESEND_API_KEY`, and `APP_MAIL_FROM`. `deploy.env` contains non-runtime operator settings, including the smoke-test account email and release-retention count. Both `/etc` files are root-owned with mode `0600`.

## systemd Runtime

`ops/production/systemd/christopherbell.service` will be installed as a system service. It will:

- require and start after `mongod.service`;
- start after `network-online.target`;
- run the application as the configured WSL operator account;
- read `/etc/christopherbell.dev/app.env` through `EnvironmentFile`;
- execute `/usr/bin/java -jar /opt/christopherbell.dev/current/app.jar` with the `prod` profile and port 8080;
- restart after unexpected failure with a bounded delay;
- use journald for stdout and stderr;
- use conservative systemd hardening that still permits read access to the release, network access, and required temporary files.

The service will not run Gradle and will not depend on a repository checkout after installation.

## Windows Boot Activation

systemd can start services only after the Debian WSL distribution is running. A Windows Scheduled Task will therefore:

- trigger at Windows startup;
- run as the Windows account that owns the Debian WSL distribution;
- use “Run whether user is logged on or not” with stored Windows credentials;
- execute `wsl.exe -d Debian --exec /bin/true`;
- use the highest available privileges;
- retry after transient startup failure.

The repository will include a PowerShell installer and task definition/template where practical. Because Windows must securely store the account credential, the final task registration may require an elevated interactive prompt. No Windows password may be stored in the repository or a script argument.

## Clean Build Source

`make prod-deploy` always deploys the latest merged `origin/main`, regardless of the branch or dirty state of the checkout from which it is invoked.

The deploy script will:

1. Resolve the configured repository and remote.
2. Acquire an exclusive deployment lock.
3. Fetch `origin main` without altering the caller's branch or files.
4. Resolve and record the full `origin/main` SHA.
5. Create a disposable detached worktree for that SHA under a deployment workspace.
6. Run the configured Gradle verification/build command with `--no-daemon`.
7. Copy the resulting boot JAR into a staging release directory.
8. Write non-secret release provenance.
9. Remove the disposable worktree on success or failure.

No deployment may read build artifacts from the mutable development checkout.

## Candidate Smoke Profile

A `deploy-smoke` profile will extend the production configuration while disabling startup imports, scheduled collection jobs, and other application-ready mutations that are unsafe to duplicate during candidate validation. Candidate verification will use the live MongoDB database but must not modify the administrator record or application data.

The smoke process will run the staged JAR on a configurable alternate port, defaulting to 8081. Verification requires:

- the base page returns HTTP 200;
- a MongoDB-backed read endpoint returns successfully;
- a login attempt for the configured known account with a deliberately invalid diagnostic password returns `INVALID_TOKEN`, not `RESOURCE_NOT_FOUND`;
- the process remains alive for the duration of the checks.

The smoke process is always stopped before the production switch. Diagnostic passwords and account emails are not printed.

## Atomic Deployment

After candidate verification succeeds:

1. Record the current release target.
2. Atomically update `previous` to the current target when one exists.
3. Atomically replace `current` with the staged release symlink.
4. Restart `christopherbell.service`.
5. Poll port 8080 with a bounded timeout.
6. Repeat the home, database-read, and account-lookup checks.

If restart or production verification fails, deployment will atomically restore the former `current` target, restart the service, verify the rollback, and exit non-zero with clear log commands. A failed candidate never changes `current`.

## Release Retention and Rollback

Successful deployment retains the active release, the `previous` release, and a configurable number of additional recent releases. Cleanup never deletes a directory referenced by `current` or `previous`.

`make prod-rollback` switches `current` to `previous`, moves the displaced active release to `previous`, restarts the service, and performs the same bounded production verification. It refuses to run without a valid previous release.

`make prod-releases` lists SHA, build time, active/previous status, and disk usage.

## Installation and Upgrade

`make prod-install` is idempotent. It will:

- verify Java, MongoDB, systemd, Git, Gradle wrapper, curl, and required privileges;
- create the `/opt` and `/etc` layouts with restrictive ownership;
- install or refresh the systemd unit and deployment scripts;
- create sample environment files only when real files do not exist;
- reload systemd and enable MongoDB and the website service;
- deploy `origin/main` through the normal candidate-validation path;
- print the Windows Scheduled Task registration step when it cannot complete credentialed registration automatically.

Updating the deployment tooling is achieved by pulling or checking out a repository revision containing the new scripts and rerunning `make prod-install`. Existing secrets and releases are preserved.

## Observability

- `make prod-status` displays systemd state, active release SHA, previous release SHA, port state, and recent health result.
- `make prod-logs` follows `journalctl -u christopherbell.service`.
- Deployment output includes phase names and actionable failures but never secret values.
- Each deployment records the deployed SHA and timestamps in release metadata and journald.

## Testing Strategy

Automated tests will exercise scripts with temporary directories and stub commands where host-level integration is not safe. Coverage includes:

- resolving and building `origin/main` rather than the caller's branch;
- exclusive locking;
- staging and atomic symlink switching;
- failed candidate behavior;
- failed production verification and automatic rollback;
- retention without deleting active targets;
- explicit rollback behavior;
- idempotent installation;
- profile configuration that disables candidate-side mutations;
- Makefile target wiring and shell syntax.

Host integration validation will:

- install the unit without stopping the existing port 8080 process;
- run a candidate on port 8081;
- switch production only after the candidate passes;
- verify systemd restart recovery;
- stop and start the Debian WSL distribution during a controlled window;
- reboot Windows and confirm the scheduled task activates WSL, MongoDB, and the website without login.

The final Windows reboot requires explicit operator coordination because it interrupts the development host.

## Documentation Deliverables

The production operations documentation will cover:

- architecture and ownership boundaries;
- required packages and permissions;
- first installation;
- environment-file fields and secure permissions;
- Windows startup task creation and verification;
- normal deployment from `origin/main`;
- release listing and rollback;
- status and journald usage;
- failure behavior and automatic rollback;
- MongoDB backup/restore integration;
- WSL, systemd, MongoDB, port, Java, and Git troubleshooting;
- upgrade and uninstall procedures;
- full reboot acceptance checklist.

## Acceptance Criteria

- One command deploys the exact latest `origin/main` commit from a clean worktree.
- Production never switches before automated tests and alternate-port smoke checks pass.
- A failed production switch restores the previous verified release automatically.
- `christopherbell.service` owns port 8080 and automatically restarts after process failure.
- Windows startup activates Debian WSL without interactive login, which starts MongoDB and the website.
- Secrets are absent from Git, command arguments, and logs.
- Operators can deploy, inspect, follow logs, restart, list releases, roll back, and uninstall through documented Make targets.
- A controlled Windows reboot demonstrates the complete boot path.
