# WSL Production Tool Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make native Windows the only supported production runtime and remove every executable WSL migration/fallback path while keeping reproducible setup, startup, backup, deployment, and cloudflared operations.

**Architecture:** The existing `prod.cmd`/PowerShell module boundary remains the single operations interface. Configuration becomes Windows-only, installation validates or registers the three automatic Windows services, status and startup verification include cloudflared and the startup deployment task, and native backup/restore validation uses MongoDB Tools-compatible attached arguments. Documentation and Makefile targets expose the same commands without storing tunnel credentials.

**Tech Stack:** PowerShell 7, Pester 5, WinSW, Windows Service Control Manager, Task Scheduler, MongoDB Database Tools, cloudflared, GNU Make, Markdown.

## Global Constraints

- Keep Debian WSL installed and preserve unrelated WSL packages, files, Ollama, and SSH.
- Do not restore any WSL production package, unit, database, or website launcher.
- Never commit, print, or persist a Cloudflare tunnel token in repository files, test output, or logs.
- Use `C:\Program Files (x86)\cloudflared\cloudflared.exe` as the checked-in example path while allowing an absolute configured path.
- Require `MongoDB`, `ChristopherBellDev`, and `cloudflared` to be Running with Automatic startup for acceptance.
- Require `ChristopherBellAutoDeploy` to be registered as a SYSTEM AtStartup task for acceptance.
- Preserve all verified archives under `A:\Projects\christopherbell.dev-backups`.
- Follow TDD: write each focused failing Pester test, observe failure, implement the smallest production change, and rerun the focused test before the full suite.

## Document Status

Ready for execution.

## Branch

`codex/retire-wsl-production-tools`

## Goals

- Retire WSL migration commands, modules, configuration, tests, and documentation.
- Make native cloudflared setup, automatic startup, status, upgrade, and verification reproducible.
- Fix native backup/dry-run MongoDB argument compatibility.
- Provide documented Makefile and `prod.cmd` entry points for install, deploy, status, backup, upgrade, and reboot verification.

## Non-Goals

- Do not delete or unregister Debian WSL.
- Do not change application code or MongoDB schema.
- Do not change the Cloudflare hostname `www.christopherbell.dev` or tunnel `mugi`.
- Do not automate Cloudflare dashboard login or token rotation.

## Open Questions

None. The user approved native Windows cloudflared, WSL runtime package removal, documentation updates, and reproducible setup/startup scripts.

## File Structure

- `ops/production/windows/prod.ps1`: Windows-only command dispatch and secret token-path parameter.
- `ops/production/windows/modules/Production.Common.psm1`: Windows-only config contract and command help.
- `ops/production/windows/modules/Production.Install.psm1`: native service setup, cloudflared registration, recovery/startup policy.
- `ops/production/windows/modules/Production.Operations.psm1`: status, backup, and post-reboot verification.
- `ops/production/windows/modules/Production.Migrate.psm1`: delete; one-time WSL migration is retired.
- `ops/production/windows/config/deploy.example.json`: native executable paths, ports, public URL, and no WSL fields.
- `ops/production/windows/tests/*.Tests.ps1`: command/config/install/status/backup/startup regression coverage; delete migration-only tests.
- `Makefile`: stable wrappers for native production commands.
- `README.md` and `docs/operations/windows-production.md`: steady-state installation and operations source of truth.

---

### Task 1: Remove the WSL migration command and configuration contract

**Files:**
- Modify: `ops/production/windows/tests/Production.Command.Tests.ps1:1-20`
- Modify: `ops/production/windows/tests/Production.Common.Tests.ps1:5-35`
- Modify: `ops/production/windows/prod.ps1:1-34`
- Modify: `ops/production/windows/modules/Production.Common.psm1:4-37,152-161`
- Modify: `ops/production/windows/config/deploy.example.json:1-22`
- Delete: `ops/production/windows/modules/Production.Migrate.psm1`
- Delete: `ops/production/windows/tests/Production.Migrate.Tests.ps1`

**Interfaces:**
- Consumes: existing `prod.cmd` forwarding contract and `Read-ProductionConfig([string]$Path)`.
- Produces: Windows-only commands; config properties `cloudflaredExe: string` and `publicUrl: string`; no `migrate`, `ConfirmCutover`, or `wsl*` properties.

- [ ] **Step 1: Write failing command/config retirement tests**

Add to `Production.Command.Tests.ps1`:

```powershell
It 'does not expose the retired WSL migration command' {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script = Get-Content (Join-Path $root 'ops\production\windows\prod.ps1') -Raw
    $help = & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') help
    $script | Should -Not -Match "'migrate'"
    ($help -join "`n") | Should -Not -Match '\bmigrate\b'
    $script | Should -Not -Match 'Production\.Migrate'
}
```

Replace the WSL fields in `$script:validConfig` in `Production.Common.Tests.ps1` with:

```powershell
cloudflaredExe=(New-Item -ItemType File -Force (Join-Path $TestDrive 'cloudflared.exe')).FullName
publicUrl='https://www.christopherbell.dev/'
```

Add:

```powershell
It 'loads a Windows-only configuration without WSL fields' {
    $validConfig | ConvertTo-Json | Set-Content $configPath
    $config = Read-ProductionConfig -Path $configPath
    $config.publicUrl | Should -Be 'https://www.christopherbell.dev/'
    $config.PSObject.Properties.Name | Should -Not -Contain 'wslDistro'
}
```

- [ ] **Step 2: Run focused tests and observe failure**

Run:

```powershell
Invoke-Pester .\ops\production\windows\tests\Production.Command.Tests.ps1,.\ops\production\windows\tests\Production.Common.Tests.ps1 -Output Detailed
```

Expected: failures because `migrate`, `Production.Migrate`, and required WSL config fields still exist.

- [ ] **Step 3: Implement the Windows-only command and config surface**

Change the `prod.ps1` parameters/imports/handlers to:

```powershell
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help','install','deploy','status','logs','restart','releases','rollback','backup','verify-startup','uninstall','auto-install','auto-deploy','auto-status','auto-remove')]
    [string]$Command = 'help',
    [switch]$WhatIf,
    [string]$CloudflareTokenPath
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Join-Path $PSScriptRoot 'modules'
foreach ($module in 'Production.Deploy','Production.Install','Production.Operations','Production.AutoDeploy','Production.Common') {
    Import-Module (Join-Path $moduleRoot "$module.psm1") -Force
}

$handlers = @{
    help = { Show-ProductionHelp }
    install = { Install-ProductionRuntime -WhatIf:$WhatIf -CloudflareTokenPath $CloudflareTokenPath }
    deploy = { Invoke-ProductionDeploy -WhatIf:$WhatIf }
    status = { Get-ProductionStatus }
    logs = { Watch-ProductionLogs }
    restart = { Restart-ProductionService -Verify }
    releases = { Get-ProductionReleases }
    rollback = { Invoke-ProductionRollback -WhatIf:$WhatIf }
    backup = { New-ProductionBackup }
    'verify-startup' = { Test-ProductionStartup }
    uninstall = { Uninstall-ProductionRuntime -WhatIf:$WhatIf }
    'auto-install' = { Install-AutoDeployTask -WhatIf:$WhatIf }
    'auto-deploy' = { Start-AutoDeployLoop }
    'auto-status' = { Get-AutoDeployStatus }
    'auto-remove' = { Remove-AutoDeployTask -WhatIf:$WhatIf }
}
```

Change `Read-ProductionConfig` required string fields to:

```powershell
foreach ($name in 'repositoryPath','remote','branch','programDataRoot','javaExe','nodeExe','mongoToolsPath','mongoShellExe','cloudflaredExe','backupRoot','publicUrl','smokeAccountEmail') {
```

Include `cloudflaredExe` in the absolute-path existence loop. Replace help text with:

```powershell
Commands: install, deploy, status, logs, restart, releases, rollback, backup,
          verify-startup, uninstall, auto-install, auto-deploy, auto-status,
          auto-remove
```

Replace the WSL block in `deploy.example.json` with:

```json
"cloudflaredExe": "C:\\Program Files (x86)\\cloudflared\\cloudflared.exe",
"backupRoot": "A:\\Projects\\christopherbell.dev-backups",
"publicUrl": "https://www.christopherbell.dev/",
```

Delete `Production.Migrate.psm1` and `Production.Migrate.Tests.ps1` with `apply_patch` after removing their imports and commands.

- [ ] **Step 4: Run focused tests and verify pass**

Run the same focused Pester command. Expected: all command/common tests pass; `help` contains `verify-startup` and no `migrate`.

- [ ] **Step 5: Commit Task 1**

```powershell
git add ops/production/windows/prod.ps1 ops/production/windows/modules/Production.Common.psm1 ops/production/windows/config/deploy.example.json ops/production/windows/tests/Production.Command.Tests.ps1 ops/production/windows/tests/Production.Common.Tests.ps1
git add -u ops/production/windows/modules/Production.Migrate.psm1 ops/production/windows/tests/Production.Migrate.Tests.ps1
git commit -m "Retire WSL production migration commands"
```

---

### Task 2: Make native cloudflared setup and startup verification reproducible

**Files:**
- Modify: `ops/production/windows/tests/Production.Install.Tests.ps1:1-21`
- Modify: `ops/production/windows/tests/Production.Operations.Tests.ps1:1-12`
- Modify: `ops/production/windows/modules/Production.Install.psm1:8-89`
- Modify: `ops/production/windows/modules/Production.Operations.psm1:6-17,86`

**Interfaces:**
- Consumes: `config.cloudflaredExe`, `config.publicUrl`, `Assert-Administrator`, `Test-ProductionEndpoints`.
- Produces: `Install-CloudflaredService([string]$Executable,[string]$TokenPath,[switch]$WhatIf)`, `Test-ProductionStartup()`, and `CloudflaredService` in status output.

- [ ] **Step 1: Write failing setup/status/startup tests**

Add inside `InModuleScope Production.Install`:

```powershell
It 'requires a token path only when cloudflared is not installed' {
    Mock Get-Service { $null } -ParameterFilter { $Name -eq 'cloudflared' }
    { Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $null } |
        Should -Throw '*CloudflareTokenPath*'
}

It 'installs cloudflared without writing the token to output' {
    $tokenPath = Join-Path $TestDrive 'tunnel-token.txt'
    ('a' * 240) | Set-Content $tokenPath -NoNewline
    Mock Get-Service { $null } -ParameterFilter { $Name -eq 'cloudflared' }
    Mock Invoke-CheckedProcess {}
    Mock Set-Service {}
    $output = Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $tokenPath
    Should -Invoke Invoke-CheckedProcess -ParameterFilter {
        $FilePath -eq 'C:\cloudflared.exe' -and $ArgumentList[0] -eq 'service' -and $ArgumentList[1] -eq 'install' -and $ArgumentList[2].Length -eq 240
    }
    ($output -join '') | Should -Not -Match ('a' * 20)
}
```

Add inside `InModuleScope Production.Operations`:

```powershell
It 'reports cloudflared with native website and MongoDB services' {
    Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 } }
    Mock Get-Service {
        [pscustomobject]@{ Status='Running'; StartType='Automatic' }
    }
    Mock Get-JunctionTarget { $null }
    Mock Get-NetTCPConnection { [pscustomobject]@{ OwningProcess=42 } }
    (Get-ProductionStatus).CloudflaredService | Should -Be 'Running'
}

It 'rejects startup when a required service is not automatic' {
    Mock Read-ProductionConfig { [pscustomobject]@{ publicUrl='https://www.christopherbell.dev/'; productionPort=8080 } }
    Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Manual' } }
    { Test-ProductionStartup } | Should -Throw '*Automatic*'
}
```

- [ ] **Step 2: Run focused tests and observe failure**

```powershell
Invoke-Pester .\ops\production\windows\tests\Production.Install.Tests.ps1,.\ops\production\windows\tests\Production.Operations.Tests.ps1 -Output Detailed
```

Expected: missing `Install-CloudflaredService`, missing `CloudflaredService`, and missing `Test-ProductionStartup`.

- [ ] **Step 3: Implement secret-safe cloudflared setup**

Add to `Production.Install.psm1`:

```powershell
function Install-CloudflaredService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Executable,
        [string]$TokenPath,
        [switch]$WhatIf
    )
    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { throw "Missing cloudflared executable: $Executable" }
    $existing = Get-Service cloudflared -ErrorAction SilentlyContinue
    if (-not $existing) {
        if ([string]::IsNullOrWhiteSpace($TokenPath) -or -not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
            throw 'CloudflareTokenPath must reference a protected file when installing cloudflared.'
        }
        $token = (Get-Content -LiteralPath $TokenPath -Raw).Trim()
        try {
            if ($token.Length -lt 100 -or $token -notmatch '^[A-Za-z0-9_.=-]+$') { throw 'Cloudflare tunnel token is invalid.' }
            if (-not $WhatIf) { Invoke-CheckedProcess $Executable @('service','install',$token) (Split-Path -Parent $Executable) | Out-Null }
        } finally { $token = $null }
    }
    if (-not $WhatIf) {
        Set-Service cloudflared -StartupType Automatic
        & sc.exe failure cloudflared reset= 3600 actions= restart/10000/restart/30000 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to configure cloudflared service recovery.' }
        Start-Service cloudflared
    }
}
```

In `Install-ProductionRuntime`, add `[string]$CloudflareTokenPath`, include cloudflared in `-WhatIf` output, and call:

```powershell
Install-CloudflaredService -Executable $config.cloudflaredExe -TokenPath $CloudflareTokenPath -WhatIf:$WhatIf
```

Export `Install-CloudflaredService`.

- [ ] **Step 4: Implement native status and post-reboot verification**

Add `$cloudflared = Get-Service cloudflared -ErrorAction SilentlyContinue` and this status property:

```powershell
CloudflaredService = if ($cloudflared) { $cloudflared.Status } else { 'NotInstalled' }
```

Add to `Production.Operations.psm1`:

```powershell
function Test-ProductionStartup {
    $config = Read-ProductionConfig
    foreach ($name in 'MongoDB','ChristopherBellDev','cloudflared') {
        $service = Get-Service $name -ErrorAction Stop
        if ([string]$service.Status -ne 'Running') { throw "$name must be Running." }
        if ([string]$service.StartType -ne 'Automatic') { throw "$name must use Automatic startup." }
    }
    $task = Get-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction Stop
    if ([string]$task.State -eq 'Disabled') { throw 'ChristopherBellAutoDeploy must be enabled.' }
    Test-ProductionEndpoints $config $config.productionPort
    Wait-HttpStatus -Uri $config.publicUrl -ExpectedStatus 200 -Timeout ([timespan]::FromSeconds(30)) | Out-Null
    [pscustomobject]@{ Services='RunningAutomatic'; AutoDeployTask=$task.State; NativeEndpoint=200; PublicEndpoint=200 }
}
```

Export `Test-ProductionStartup`.

- [ ] **Step 5: Run focused and full tests**

Run the focused command, then:

```powershell
Invoke-Pester .\ops\production\windows\tests -Output Detailed
```

Expected: all tests pass with no token content in output.

- [ ] **Step 6: Commit Task 2**

```powershell
git add ops/production/windows/modules/Production.Install.psm1 ops/production/windows/modules/Production.Operations.psm1 ops/production/windows/tests/Production.Install.Tests.ps1 ops/production/windows/tests/Production.Operations.Tests.ps1
git commit -m "Add native cloudflared startup management"
```

---

### Task 3: Correct and verify native MongoDB backup arguments

**Files:**
- Modify: `ops/production/windows/tests/Production.Operations.Tests.ps1:3-12`
- Modify: `ops/production/windows/modules/Production.Operations.psm1:48-59`

**Interfaces:**
- Consumes: `Invoke-CheckedProcess`, `config.mongoToolsPath`, `config.backupRoot`.
- Produces: `Get-NativeMongoDumpArguments([string]$Archive): string[]` and `Get-NativeMongoRestoreDryRunArguments([string]$Archive): string[]`.

- [ ] **Step 1: Write failing argument-shape tests**

```powershell
It 'uses attached IPv4 URI and archive arguments for native backups' {
    $dump = Get-NativeMongoDumpArguments 'A:\backups\native.archive.gz'
    $restore = Get-NativeMongoRestoreDryRunArguments 'A:\backups\native.archive.gz'
    $dump | Should -Contain '--uri=mongodb://127.0.0.1:27017'
    $dump | Should -Contain '--archive=A:\backups\native.archive.gz'
    $restore | Should -Contain '--uri=mongodb://127.0.0.1:27017'
    $restore | Should -Contain '--archive=A:\backups\native.archive.gz'
    $dump | Should -Not -Contain '--archive'
    $restore | Should -Not -Contain '--archive'
}
```

- [ ] **Step 2: Run the focused test and observe missing functions**

```powershell
Invoke-Pester .\ops\production\windows\tests\Production.Operations.Tests.ps1 -Output Detailed
```

Expected: FAIL because both argument functions are undefined.

- [ ] **Step 3: Implement attached MongoDB arguments**

```powershell
function Get-NativeMongoDumpArguments {
    param([Parameter(Mandatory)][string]$Archive)
    @('--uri=mongodb://127.0.0.1:27017','--db=christopherbell',"--archive=$Archive",'--gzip')
}

function Get-NativeMongoRestoreDryRunArguments {
    param([Parameter(Mandatory)][string]$Archive)
    @('--uri=mongodb://127.0.0.1:27017',"--archive=$Archive",'--gzip','--dryRun')
}
```

Replace the two inline arrays in `New-ProductionBackup` with these functions and export both functions for Pester.

- [ ] **Step 4: Run focused and full tests**

Expected: the focused test and complete Windows production suite pass.

- [ ] **Step 5: Run a real native backup**

From elevated PowerShell on the production host:

```powershell
.\prod.cmd backup
```

Expected: a non-empty `christopherbell-native-*.archive.gz` and `.sha256.json` appear under `backupRoot`; the dry run returns successfully instead of waiting on stdin.

- [ ] **Step 6: Commit Task 3**

```powershell
git add ops/production/windows/modules/Production.Operations.psm1 ops/production/windows/tests/Production.Operations.Tests.ps1
git commit -m "Fix native MongoDB backup verification"
```

---

### Task 4: Update Makefile, setup/startup documentation, and operator contracts

**Files:**
- Modify: `Makefile:1-4`
- Modify: `README.md:352-390`
- Rewrite: `docs/operations/windows-production.md:1-185`
- Modify: `ops/production/windows/tests/Production.Command.Tests.ps1:1-30`

**Interfaces:**
- Consumes: commands from Tasks 1-3.
- Produces: `make prod-install`, `prod-deploy`, `prod-status`, `prod-backup`, `prod-cloudflare-upgrade`, and `prod-verify-startup` operator entry points.

- [ ] **Step 1: Write failing Makefile/documentation contract test**

```powershell
It 'documents native setup startup and cloudflared upgrades without WSL' {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $makefile = Get-Content (Join-Path $root 'Makefile') -Raw
    $runbook = Get-Content (Join-Path $root 'docs\operations\windows-production.md') -Raw
    $makefile | Should -Match 'prod-cloudflare-upgrade'
    $makefile | Should -Match 'prod-verify-startup'
    $runbook | Should -Match 'CloudflareTokenPath'
    $runbook | Should -Match 'verify-startup'
    $runbook | Should -Match 'winget upgrade --id Cloudflare.cloudflared'
    $runbook | Should -Not -Match '\.\\prod\.cmd migrate'
    $runbook | Should -Not -Match 'WSL fallback'
}
```

- [ ] **Step 2: Run command tests and observe failure**

Expected: missing Makefile targets and stale WSL runbook language.

- [ ] **Step 3: Implement stable Makefile wrappers**

Use:

```make
.PHONY: prod-install prod-deploy prod-status prod-logs prod-restart prod-releases prod-rollback prod-backup prod-verify-startup prod-uninstall prod-auto-install prod-auto-deploy prod-auto-status prod-auto-remove prod-cloudflare-upgrade

prod-install prod-deploy prod-status prod-logs prod-restart prod-releases prod-rollback prod-backup prod-verify-startup prod-uninstall prod-auto-install prod-auto-deploy prod-auto-status prod-auto-remove:
	@cmd.exe /d /c prod.cmd $(@:prod-%=%)

prod-cloudflare-upgrade:
	@winget upgrade --id Cloudflare.cloudflared --exact --source winget --scope machine --accept-package-agreements --accept-source-agreements
	@powershell.exe -NoLogo -NoProfile -Command "Restart-Service cloudflared; if ((Get-Service cloudflared).Status -ne 'Running') { exit 1 }"
```

- [ ] **Step 4: Rewrite operational documentation**

The runbook must contain these concrete sections and commands:

```markdown
## Fresh-Machine Setup
.
\prod.cmd install -CloudflareTokenPath C:\Secure\cloudflared-token.txt
.\prod.cmd auto-install
.\prod.cmd deploy
.\prod.cmd verify-startup

## Application Releases
git push origin main
.\prod.cmd auto-status

## cloudflared Upgrade
winget upgrade --id Cloudflare.cloudflared --exact --source winget --scope machine
Restart-Service cloudflared
.\prod.cmd verify-startup

## Reboot Acceptance
Restart-Computer
.\prod.cmd verify-startup
```

Also document ProgramData ACLs, token rotation and immediate deletion of the temporary token file, service/task startup contracts, logs (`Get-WinEvent`, WinSW logs, auto-deploy state), native backup/restore, release rollback, uninstall, and explicit confirmation that WSL contains no production dependencies.

Update README production summary to name all three native Windows services, automatic deployment, `prod.cmd verify-startup`, and the runbook link.

- [ ] **Step 5: Run command tests and full suite**

Expected: all tests pass and repository search finds no executable WSL migration path:

```powershell
rg -n "prod\.cmd migrate|Production\.Migrate|wslDistro|wslWebsite|wslMongo" ops\production\windows Makefile README.md docs\operations\windows-production.md
```

Expected: no matches.

- [ ] **Step 6: Commit Task 4**

```powershell
git add Makefile README.md docs/operations/windows-production.md ops/production/windows/tests/Production.Command.Tests.ps1
git commit -m "Document native production setup and startup"
```

---

### Task 5: Production verification, review, PR, and merge

**Files:**
- Verify all files changed in Tasks 1-4.
- Update: Builder test report/session memory after merge using repository-scoped skills.

**Interfaces:**
- Consumes: complete native production operations surface.
- Produces: merged PR and evidence that Windows remains the sole production runtime.

- [ ] **Step 1: Run static and automated verification**

```powershell
git diff --check
Invoke-Pester .\ops\production\windows\tests -Output Detailed
.\gradlew.bat --no-daemon :website:test
```

Expected: no whitespace errors and all Pester/website tests pass.

- [ ] **Step 2: Refresh protected ProgramData tooling**

From the branch checkout in elevated PowerShell:

```powershell
.\prod.cmd install
.\prod.cmd auto-install
```

Expected: existing secrets are preserved; WinSW scripts and production tools are refreshed; MongoDB, website, cloudflared, and automatic deployment retain boot-persistent configuration.

- [ ] **Step 3: Exercise live operations**

```powershell
.\prod.cmd deploy -WhatIf
.\prod.cmd status
.\prod.cmd backup
.\prod.cmd verify-startup
```

Expected: `origin/main` resolves, all native services are Running, a verified native backup is written, and native/public endpoints return 200.

- [ ] **Step 4: Re-verify WSL retirement without changing WSL state**

```powershell
wsl.exe -d Debian --exec sh -lc "for c in cloudflared nginx mongod mongosh mongodump mongorestore; do command -v `$c && exit 1; done"
```

Expected: exit `0`; none of the retired commands exists. Do not reinstall packages.

- [ ] **Step 5: Request code review and address only verified findings**

Use `superpowers:requesting-code-review`. Rerun focused tests after any change, then rerun the complete suite.

- [ ] **Step 6: Push and open the PR**

```powershell
git push
gh pr create --repo azurras/christopherbell.dev --base main --head codex/retire-wsl-production-tools --title "Retire WSL production tooling" --body "Retires the WSL migration path, adds reproducible native cloudflared setup/startup verification, fixes native backup arguments, and updates the complete Windows production runbook."
```

- [ ] **Step 7: Wait for required checks and merge**

```powershell
gh pr checks --repo azurras/christopherbell.dev --watch --interval 10
gh pr merge --repo azurras/christopherbell.dev --squash --delete-branch
```

Expected: every required check passes and the PR reports `MERGED`.

- [ ] **Step 8: Save final evidence**

Use Builder `save-test-report` and `save-session-memory`, refresh hub indexes, validate hub state, and commit/push Builder `main`. Record service states, public/native HTTP statuses, backup archive/hash, Pester/Gradle results, PR URL, and merge SHA without recording secrets.

## Risks

- A token path could leak through output or command history. Mitigation: accept only a file path, never output token contents, clear the in-memory variable, and instruct the operator to delete the temporary file immediately.
- Removing migration code could accidentally remove native inventory/backup behavior. Mitigation: keep native backup helpers in `Production.Operations` and add attached-argument tests.
- Setup could report success while cloudflared is Manual or stopped. Mitigation: configure recovery/start mode and require `verify-startup` to inspect Status and StartType.
- Makefile cloudflared upgrade may require elevation. Mitigation: document elevated PowerShell/terminal as a prerequisite and fail on nonzero `winget` or service status.
- Existing protected `deploy.json` still contains retired WSL fields. Mitigation: `Read-ProductionConfig` tolerates extra JSON properties; rerunning install copies the new example without overwriting the active file, and the runbook documents optional manual removal of retired fields.

## Unit Testing

- Pester command/config tests prove the WSL surface is absent.
- Pester install tests prove token-path validation and token-safe output.
- Pester status/startup tests prove cloudflared and boot contracts are enforced.
- Pester backup tests prove attached IPv4 URI/archive MongoDB argument forms.
- Existing deployment, rollback, auto-deploy, and configuration safety tests remain green.

## Local Testing

- Run the complete Pester suite.
- Run `:website:test` to catch unrelated application regressions.
- Run a real native backup and dry run.
- Run `verify-startup` against the live Windows services and public endpoint.
- Verify Cloudflare reports only the Windows replica.
- Verify WSL retired commands remain absent.

## Rollback

- Code rollback: revert the task commit or use the prior release branch; native runtime remains unchanged until `install`/`auto-install` refreshes ProgramData.
- ProgramData tooling rollback: recopy `prod.ps1`, modules, and config examples from the previous merged SHA, then rerun `auto-install`.
- Application rollback: use `prod.cmd rollback`; this switches `current` and `previous` release junctions and does not affect MongoDB or cloudflared.
- Tunnel rollback: restart the existing native `cloudflared` service. Do not restore the invalidated WSL token or removed WSL packages.
- Database recovery: restore only from a verified native/pre-native archive after explicit approval; ordinary code rollback must not modify MongoDB data.

## Completion Criteria

- No executable WSL migration command, module, configuration requirement, test, Makefile target, or runbook instruction remains.
- Native cloudflared setup, automatic startup, status, upgrade, and verification are reproducible and documented.
- All automated and live verification passes.
- The PR is merged and Builder evidence is committed to `main`.
