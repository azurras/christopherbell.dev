# WSL Production Tool Retirement Design

## Document Status

Approved for planning. The user explicitly approved retaining WSL while removing all tools that operated `christopherbell.dev` from it.

## Context

`christopherbell.dev` now runs as native Windows services: `MongoDB`, `ChristopherBellDev`, and `cloudflared`. The native website and database start automatically, automatic application deployment follows `origin/main`, and the Cloudflare tunnel route `www.christopherbell.dev` is served by a Windows `cloudflared` replica.

The cutover initially retained Debian WSL as a rollback source. That fallback became unsafe after steady state because starting WSL automatically reactivated its old cloudflared, nginx, and MongoDB units. The repository also continued to expose a WSL migration command and WSL-specific configuration fields.

## Goals

- Keep Debian WSL installed for unrelated development and services.
- Make native Windows the only production runtime for the website, MongoDB, and Cloudflare Tunnel.
- Remove WSL cloudflared, nginx, MongoDB server/shell/tools, website launch artifacts, service units, package repositories, signing keys, logs, and database files.
- Rotate the Cloudflare tunnel token and run the replacement connector as an automatic Windows service.
- Remove the WSL migration/fallback command surface from repository production tooling so it cannot be invoked accidentally.
- Keep native MongoDB backup, restore verification, rollback, deployment, and automatic deployment operations.
- Document native cloudflared installation, upgrade, health verification, and recovery.
- Update checked-in setup/startup automation so a new Windows production installation registers MongoDB, the website, cloudflared, and automatic deployment without depending on WSL.

## Non-Goals

- Do not unregister or delete the Debian WSL distribution.
- Do not remove unrelated WSL services, packages, user files, Ollama, SSH, or development tools.
- Do not delete verified archives under `A:\Projects\christopherbell.dev-backups`.
- Do not change the application API, database schema, or Cloudflare public hostname.
- Do not automate Cloudflare dashboard authentication or persist a tunnel token in the repository.

## Runtime Design

The steady-state production dependency chain is:

```text
Cloudflare edge
  -> cloudflared Windows service (Automatic)
  -> http://127.0.0.1:8080
  -> ChristopherBellDev Windows service (Automatic)
  -> MongoDB Windows service (Automatic)
```

WSL is not in the request path. Starting or stopping Debian must not affect public availability, the native database, deployment polling, or backups.

The Cloudflare tunnel token is rotated in the authenticated dashboard. The replacement token is passed directly to the elevated Windows service installer, is never printed in logs, and is cleared from the clipboard after registration. Cloudflare must report only the healthy `windows_amd64` replica before WSL connector removal is considered complete.

## WSL Retirement Scope

Remove only website-production components:

- Packages: `cloudflared`, `nginx`, `nginx-common`, `mongodb-database-tools`, `mongodb-mongosh`, and all `mongodb-org*` packages.
- Units: `cloudflared.service`, its update service/timer, `nginx.service`, and `mongod.service`.
- State: `/var/lib/mongodb`, `/var/log/mongodb`, nginx website configuration, MongoDB configuration, and obsolete Cloudflare configuration.
- Package sources and signing keys dedicated to MongoDB and Cloudflare.
- Any website-specific systemd user unit, cron entry, temporary production log, or WSL launch helper if present.

Do not run `apt autoremove`; unrelated packages must be preserved.

## Repository Changes

- Remove `migrate` and `ConfirmCutover` from `ops/production/windows/prod.ps1`.
- Stop importing `Production.Migrate` and delete the retired module and its WSL-specific tests.
- Remove WSL distro/start/stop fields from `deploy.example.json`, `Read-ProductionConfig`, and test fixtures.
- Update command help and dependency-free command-surface tests.
- Keep candidate database override behavior in deployment because it remains useful for safe validation.
- Add `cloudflared` service state to `Get-ProductionStatus` so routine status checks prove all three native services.
- Correct native backup and dry-run commands to use equals-form MongoDB URI/archive arguments, with regression coverage.
- Rewrite the Windows production runbook and README steady-state sections so WSL is explicitly out of scope and native cloudflared install/upgrade/recovery is documented.
- Update setup scripts to detect the signed machine-wide cloudflared executable, register or validate its automatic Windows service, and fail clearly when the operator has not supplied a tunnel token through an approved secret-input path.
- Update startup scripts and service configuration so the boot contract covers `MongoDB`, `ChristopherBellDev`, `cloudflared`, and `ChristopherBellAutoDeploy` without an interactive login.
- Add Makefile targets or documented command aliases for install, status, deploy, backup, cloudflared upgrade, and post-reboot verification where they provide stable wrappers over the existing `prod.cmd` interface; tunnel tokens must never be command-line constants in versioned files.
- Document fresh-machine setup, routine application releases, cloudflared upgrades, reboot acceptance, secret rotation, logs, backup/restore, rollback, and uninstall/retirement.

## Failure Handling

- Do not remove WSL packages until the Windows tunnel replica is healthy and the public endpoint returns HTTP 200 with WSL cloudflared/nginx/mongod stopped.
- If native cloudflared fails before WSL removal, restart the disabled WSL units temporarily using the still-valid replacement token only if explicitly reconfigured. The rotated old token is not a recovery mechanism.
- If package removal fails partway, keep the native services running, inventory remaining WSL artifacts, and resume targeted removal. Do not use broad recursive deletion outside the listed paths.
- Native application rollback remains release-junction based and does not roll back MongoDB or Cloudflare.

## Testing

- Run the complete Windows production Pester suite.
- Add assertions that `prod.ps1`, production config, and help expose no WSL migration fields or commands.
- Add setup/startup tests proving cloudflared is required, automatic, included in status output, and installed without persisting a token in versioned files.
- Add native backup argument tests for attached URI/archive values and IPv4 loopback.
- Verify Windows services `cloudflared`, `ChristopherBellDev`, and `MongoDB` are `Running` and `Automatic`.
- Verify `http://127.0.0.1:8080/` and `https://www.christopherbell.dev/` return HTTP 200.
- Verify native MongoDB ping succeeds.
- Verify WSL commands `cloudflared`, `nginx`, `mongod`, `mongosh`, `mongodump`, and `mongorestore` are absent and their units/data directories do not exist.
- Verify Cloudflare reports a healthy `windows_amd64` replica and no Linux replica.

## Completion Criteria

- Native Windows is the sole production path for tunnel, app, and database.
- Public traffic remains healthy after WSL website services are stopped and removed.
- Debian WSL remains installed and unrelated services are untouched.
- Repository production tooling contains no executable WSL migration/fallback path.
- Native backup/restore verification is covered by passing tests.
- Operations documentation describes install, upgrade, status, backup, rollback, and recovery without relying on WSL.
- Checked-in setup/startup scripts can reproduce the native boot-persistent service topology and provide a single documented post-reboot verification command.
