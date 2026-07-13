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
        $LASTEXITCODE | Should -Be 0
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
        foreach ($module in 'Production.Deploy','Production.Install','Production.Operations','Production.AutoDeploy','Production.Sensors') {
            Import-Module (Join-Path $moduleRoot "$module.psm1") -Force
        }

        foreach ($command in 'Invoke-ProductionDeploy','Install-ProductionRuntime','Get-ProductionStatus','Install-AutoDeployTask','Show-ProductionHelp') {
            Get-Command $command -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
        foreach ($command in 'Install-PawnIoProvider','Get-ProductionSensorStatus','Set-ProductionSensorState') {
            Get-Command $command -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
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

    It 'rejects unknown commands' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') unknown-command 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }
}
