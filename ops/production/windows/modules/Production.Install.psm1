Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Production.Common.psm1') -Force

$script:WinSwUri = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
$script:WinSwSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This operation requires elevated PowerShell.'
    }
}

function New-ProductionDirectories {
    param([string]$Root)
    foreach ($name in 'backups','config','gradle-home','locks','logs','releases','service','state','tools','worktrees') {
        New-Item -ItemType Directory -Force (Join-Path $Root $name) | Out-Null
    }
}

function Install-ConfigurationExamples {
    param([string]$Root)
    $configSource = Join-Path $PSScriptRoot '..\config'
    $deployTarget = Join-Path $Root 'config\deploy.json'
    $environmentTarget = Join-Path $Root 'config\app.env'
    Copy-Item (Join-Path $configSource 'deploy.example.json') (Join-Path $Root 'config\deploy.example.json') -Force
    Copy-Item (Join-Path $configSource 'app.env.example') (Join-Path $Root 'config\app.env.example') -Force
    if (-not (Test-Path -LiteralPath $deployTarget)) { Copy-Item (Join-Path $configSource 'deploy.example.json') $deployTarget }
    if (-not (Test-Path -LiteralPath $environmentTarget)) { Copy-Item (Join-Path $configSource 'app.env.example') $environmentTarget }
}

function Protect-ProductionSecrets {
    param([string]$Root)
    $config = Join-Path $Root 'config'
    & icacls.exe $config '/inheritance:r' '/grant:r' 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect production configuration ACLs.' }
}

function Install-ProductionRuntime {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    $root = 'C:\ProgramData\christopherbell.dev'
    if ($WhatIf) { Write-Output "Would create $root, preserve configuration, verify WinSW, and install ChristopherBellDev."; return }
    New-ProductionDirectories $root
    Install-ConfigurationExamples $root
    $config = Read-ProductionConfig (Join-Path $root 'config\deploy.json')
    Read-ProductionEnvironment (Join-Path $root 'config\app.env') | Out-Null
    Protect-ProductionSecrets $root
    Set-Service MongoDB -StartupType Automatic
    & sc.exe failure MongoDB reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure MongoDB service recovery.' }
    $service = Join-Path $root 'service'
    $binary = Join-Path $service 'ChristopherBellDev.exe'
    $download = "$binary.download"
    Invoke-WebRequest $script:WinSwUri -OutFile $download
    if ((Get-FileHash $download -Algorithm SHA256).Hash -ne $script:WinSwSha256) {
        Remove-Item -LiteralPath $download -Force
        throw 'WinSW SHA-256 verification failed.'
    }
    Move-Item -LiteralPath $download -Destination $binary -Force
    Copy-Item (Join-Path $PSScriptRoot '..\service\ChristopherBellDev.xml') $service -Force
    Copy-Item (Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1') $service -Force
    if (-not (Get-Service ChristopherBellDev -ErrorAction SilentlyContinue)) {
        & $binary install | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'WinSW service installation failed.' }
    }
    & sc.exe config ChristopherBellDev start= auto depend= MongoDB | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure the website service.' }
    & sc.exe failure ChristopherBellDev reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure website service recovery.' }
}

function Uninstall-ProductionRuntime {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    $binary = 'C:\ProgramData\christopherbell.dev\service\ChristopherBellDev.exe'
    if ($WhatIf) { Write-Output 'Would remove only the ChristopherBellDev service; data and MongoDB remain.'; return }
    if (Get-Service ChristopherBellDev -ErrorAction SilentlyContinue) {
        Stop-Service ChristopherBellDev -ErrorAction SilentlyContinue
        & $binary uninstall | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'WinSW service removal failed.' }
    }
}

Export-ModuleMember -Function Assert-Administrator,New-ProductionDirectories,Install-ConfigurationExamples,Protect-ProductionSecrets,Install-ProductionRuntime,Uninstall-ProductionRuntime
