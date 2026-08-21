Describe 'native Windows production command surface' {
    It 'provides the dependency-free command entry points' {
        Test-Path (Join-Path $PSScriptRoot '..\..\..\..\prod.cmd') | Should -BeTrue
        Test-Path (Join-Path $PSScriptRoot '..\..\..\..\Makefile') | Should -BeTrue
        Test-Path (Join-Path $PSScriptRoot '..\prod.ps1') | Should -BeTrue
    }

    It 'prints help successfully' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $output = & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') help
        ($output -join "`n") | Should -Match 'auto-install'
        ($output -join "`n") | Should -Match 'sensor-install'
        ($output -join "`n") | Should -Match 'sensor-status'
        ($output -join "`n") | Should -Match 'sensor-enable'
        ($output -join "`n") | Should -Match 'sensor-disable'
        ($output -join "`n") | Should -Match 'mongo-consolidation-preview'
        ($output -join "`n") | Should -Match 'mongo-consolidate'
        ($output -join "`n") | Should -Match 'mongo-consolidation-rollback'
        foreach ($command in 'postgres-install','postgres-bootstrap','postgres-status',
            'postgres-backup','postgres-restore-check','postgres-pgadmin',
            'postgres-shadow','postgres-reconcile') {
            ($output -join "`n") | Should -Match ([regex]::Escape($command))
        }
        $LASTEXITCODE | Should -Be 0
    }

    It 'loads and exports every PostgreSQL operator command' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help

        foreach ($functionName in 'Install-ProductionPostgreSql','Initialize-ProductionPostgreSql',
            'Get-ProductionPostgreSqlStatus','New-ProductionPostgreSqlBackup',
            'Test-ProductionPostgreSqlRestore','Install-ProductionPgAdmin',
            'Invoke-ProductionPostgreSqlShadow','Invoke-ProductionPostgreSqlReconcile') {
            Get-Command $functionName -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
    }

    It 'requires explicit confirmation before PostgreSQL bootstrap mutation' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help
        Mock Initialize-ProductionPostgreSql { }

        { Invoke-ProductionCommand -Command 'postgres-bootstrap' } |
            Should -Throw '*PostgreSQL bootstrap requires explicit confirmation*'
        Invoke-ProductionCommand -Command 'postgres-bootstrap' -ConfirmPostgreSqlBootstrap

        Should -Invoke Initialize-ProductionPostgreSql -Times 1 -Exactly
    }

    It 'launches with PowerShell 7 when pwsh is not on PATH' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $originalPath = $env:PATH
        try {
            $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
            $output = & "$env:SystemRoot\System32\cmd.exe" /d /c (Join-Path $root 'prod.cmd') help
            ($output -join "`n") | Should -Match 'auto-install'
            $LASTEXITCODE | Should -Be 0
        }
        finally {
            $env:PATH = $originalPath
        }
    }

    It 'keeps every command handler exported after loading all modules' {
        $moduleRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\modules')).Path
        Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
        foreach ($module in 'Production.Deploy','Production.DomainCollections','Production.SharedFolder','Production.Install','Production.Operations','Production.MusicRuntime','Production.AutoDeploy','Production.Sensors') {
            Import-Module (Join-Path $moduleRoot "$module.psm1") -Force
        }

        foreach ($command in 'Invoke-ProductionDeploy','Install-ProductionRuntime','Get-ProductionStatus','Install-AutoDeployTask','Show-ProductionHelp') {
            Get-Command $command -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
        Get-Command Invoke-ProductionMigrationAwareRollback -ErrorAction SilentlyContinue |
            Should -Not -BeNullOrEmpty
        foreach ($command in 'Get-ProductionDomainCollectionPreview',
            'Invoke-ProductionDomainCollectionCutover',
            'Invoke-ProductionDomainCollectionRollback') {
            Get-Command $command -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
        foreach ($command in 'Install-PawnIoProvider','Get-ProductionSensorStatus','Set-ProductionSensorState') {
            Get-Command $command -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
    }

    It 'loads the shared-folder module before the installer' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $script = Get-Content (Join-Path $root 'ops\production\windows\prod.ps1') -Raw

        $script.IndexOf("'Production.SharedFolder'") | Should -BeGreaterThan -1
        $script.IndexOf("'Production.SharedFolder'") | Should -BeLessThan $script.IndexOf("'Production.Install'")
    }

    It 'does not expose the retired WSL migration command' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $script = Get-Content (Join-Path $root 'ops\production\windows\prod.ps1') -Raw
        $help = & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') help
        $script | Should -Not -Match "'migrate'"
        ($help -join "`n") | Should -Not -Match '\bmigrate\b'
        $script | Should -Not -Match 'Production\.Migrate'
    }

    It 'documents native setup startup and cloudflared upgrades without WSL' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $makefile = Get-Content (Join-Path $root 'Makefile') -Raw
        $runbook = Get-Content (Join-Path $root 'docs\operations\windows-production.md') -Raw
        $makefile | Should -Match 'prod-cloudflare-upgrade'
        $makefile | Should -Match 'prod-verify-startup'
        $runbook | Should -Match 'CloudflareTokenPath'
        $runbook | Should -Match 'verify-startup'
        $runbook | Should -Match 'winget upgrade --id Cloudflare\.cloudflared'
        $runbook | Should -Not -Match '\.\\prod\.cmd migrate'
        $runbook | Should -Not -Match 'WSL fallback'
    }

    It 'documents the guarded sensor provider lifecycle and rollback' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $runbook = Get-Content (Join-Path $root 'docs\operations\windows-production.md') -Raw
        foreach ($command in 'sensor-install','sensor-status','sensor-enable','sensor-disable') {
            $runbook | Should -Match ([regex]::Escape($command))
        }
        $runbook | Should -Match '3010'
        $runbook | Should -Match 'Never add a Defender exclusion'
        $runbook | Should -Match 'run `sensor-disable` first'
    }

    It 'uses bounded size-only WinSW log rotation' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        [xml]$service = Get-Content (
            Join-Path $root 'ops\production\windows\service\ChristopherBellDev.xml') -Raw
        $log = $service.service.log

        [string]$log.mode | Should -Be 'roll-by-size'
        [int]$log.sizeThreshold | Should -Be 10240
        [int]$log.keepFiles | Should -Be 7
        $log.autoRollAtTime | Should -BeNullOrEmpty
        $log.pattern | Should -BeNullOrEmpty
    }

    It 'makes PostgreSQL the website service dependency for cutover-ready deployment' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        [xml]$service = Get-Content (
            Join-Path $root 'ops\production\windows\service\ChristopherBellDev.xml') -Raw

        @($service.service.depend) | Should -Be @('postgresql-x64-18')
        @($service.service.depend) | Should -Not -Contain 'MongoDB'
    }

    It 'writes exactly one JSON document for MongoDB inventory' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $makefile = Get-Content (Join-Path $root 'Makefile') -Raw
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help
        Mock Get-ProductionMongoCollectionInventory {
            [pscustomobject]@{
                complete = $true
                database = 'christopherbell'
                generatedAt = '2026-08-09T12:00:00.000Z'
                collections = @()
            }
        }
        $Error.Clear()

        $output = @(Invoke-ProductionCommand -Command 'mongo-inventory')

        $output | Should -HaveCount 1
        $Error | Should -BeNullOrEmpty
        $parsed = $output[0] | ConvertFrom-Json -ErrorAction Stop
        $parsed.complete | Should -BeTrue
        $parsed.database | Should -Be 'christopherbell'
        $makefile | Should -Match '\bprod-mongo-inventory\b'
    }

    It 'routes the read-only domain collection preview without a confirmation switch' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help
        Mock Get-ProductionDomainCollectionPreview { [pscustomobject]@{ complete = $true } }

        $null = Invoke-ProductionCommand -Command 'mongo-consolidation-preview'

        Should -Invoke Get-ProductionDomainCollectionPreview -Times 1 -Exactly
    }

    It 'requires the exact domain collection cutover confirmation before routing mutation' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help
        Mock Invoke-ProductionDomainCollectionCutover { }

        { Invoke-ProductionCommand -Command 'mongo-consolidate' } |
            Should -Throw '*requires explicit confirmation*'
        Invoke-ProductionCommand -Command 'mongo-consolidate' `
            -ConfirmDomainCollectionCutover

        Should -Invoke Invoke-ProductionDomainCollectionCutover -Times 1 -Exactly `
            -ParameterFilter { $Confirm -and -not $WhatIf }
    }

    It 'requires the exact domain collection rollback confirmation before routing mutation' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help
        Mock Invoke-ProductionDomainCollectionRollback { }

        { Invoke-ProductionCommand -Command 'mongo-consolidation-rollback' } |
            Should -Throw '*requires explicit confirmation*'
        Invoke-ProductionCommand -Command 'mongo-consolidation-rollback' `
            -ConfirmDomainCollectionRollback

        Should -Invoke Invoke-ProductionDomainCollectionRollback -Times 1 -Exactly `
            -ParameterFilter { $Confirm -and -not $WhatIf }
    }

    It 'does not expose domain mutation confirmations to automatic deploy' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $script = Get-Content (Join-Path $root 'ops\production\windows\prod.ps1') -Raw

        $script | Should -Match '\[switch\]\$ConfirmDomainCollectionCutover'
        $script | Should -Match '\[switch\]\$ConfirmDomainCollectionRollback'
        $script | Should -Not -Match "'auto-deploy'\s*=\s*\{[^}]*(ConfirmDomainCollectionCutover|ConfirmDomainCollectionRollback)"
        $script | Should -Not -Match '\[switch\]\$MusicSchemaCutover'
        $script | Should -Not -Match '\[switch\]\$ConfirmMusicRuntimeRollback'
        $script | Should -Not -Match "'music-runtime-rollback'"
    }

    It 'routes ordinary deploy without any schema mutation switch' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = . (Join-Path $root 'ops\production\windows\prod.ps1') help
        Mock Invoke-ProductionDeploy { }

        Invoke-ProductionCommand -Command deploy

        Should -Invoke Invoke-ProductionDeploy -Times 1 -Exactly -ParameterFilter {
            -not $Automatic
        }
    }

    It 'rejects unknown commands' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') unknown-command 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }
}
