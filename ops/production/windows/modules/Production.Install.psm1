Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
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
    if (-not (Test-Path -LiteralPath $deployTarget)) {
        Copy-Item (Join-Path $configSource 'deploy.example.json') $deployTarget
    } else {
        $defaults = Get-Content (Join-Path $configSource 'deploy.example.json') -Raw | ConvertFrom-Json
        $existing = Get-Content $deployTarget -Raw | ConvertFrom-Json
        foreach ($property in $defaults.PSObject.Properties) {
            if ($existing.PSObject.Properties.Name -notcontains $property.Name) {
                $existing | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
            }
        }
        foreach ($retired in 'wslDistro','wslWebsiteStopCommand','wslWebsiteStartCommand','wslMongoStopCommand','wslMongoStartCommand') {
            $existing.PSObject.Properties.Remove($retired)
        }
        $existing | ConvertTo-Json -Depth 10 | Set-Content $deployTarget -Encoding utf8
    }
    if (-not (Test-Path -LiteralPath $environmentTarget)) { Copy-Item (Join-Path $configSource 'app.env.example') $environmentTarget }
}

function Protect-ProductionSecrets {
    param([string]$Root)
    $config = Join-Path $Root 'config'
    & icacls.exe $config '/inheritance:r' '/grant:r' 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect production configuration ACLs.' }
}

function Install-CloudflaredService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Executable,
        [string]$TokenPath,
        [switch]$WhatIf
    )
    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
        throw "Missing cloudflared executable: $Executable"
    }
    $existing = Get-Service cloudflared -ErrorAction SilentlyContinue
    $tokenProvided = -not [string]::IsNullOrWhiteSpace($TokenPath)
    if (-not $existing -and -not $tokenProvided) {
        throw 'CloudflareTokenPath must reference a protected file when installing cloudflared.'
    }
    if ($tokenProvided) {
        if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
            throw 'CloudflareTokenPath must reference a protected file when installing cloudflared.'
        }
        $token = (Get-Content -LiteralPath $TokenPath -Raw).Trim()
        try {
            if ($token.Length -lt 100 -or $token -notmatch '^[A-Za-z0-9_.=-]+$') {
                throw 'Cloudflare tunnel token is invalid.'
            }
            if (-not $WhatIf) {
                if ($existing) {
                    Invoke-CheckedProcess $Executable @('service','uninstall') (Split-Path -Parent $Executable) | Out-Null
                }
                Invoke-CheckedProcess $Executable @('service','install',$token) (Split-Path -Parent $Executable) | Out-Null
            }
        } finally { $token = $null }
    }
    if (-not $WhatIf) {
        Set-Service cloudflared -StartupType Automatic
        Invoke-CheckedProcess 'sc.exe' @('failure','cloudflared','reset=','3600','actions=','restart/10000/restart/30000') | Out-Null
        Start-Service cloudflared
    }
}

function Install-WinSwBinary {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ServiceRoot)
    $binary = Join-Path $ServiceRoot 'ChristopherBellDev.exe'
    if (Test-Path -LiteralPath $binary -PathType Leaf) { return $binary }
    $download = "$binary.download"
    Invoke-WebRequest $script:WinSwUri -OutFile $download
    if ((Get-FileHash $download -Algorithm SHA256).Hash -ne $script:WinSwSha256) {
        Remove-Item -LiteralPath $download -Force
        throw 'WinSW SHA-256 verification failed.'
    }
    Move-Item -LiteralPath $download -Destination $binary
    return $binary
}

function Install-ProductionRuntime {
    [CmdletBinding()]
    param([switch]$WhatIf, [string]$CloudflareTokenPath)
    Assert-Administrator
    $root = 'C:\ProgramData\christopherbell.dev'
    if ($WhatIf) { Write-Output "Would create $root, preserve configuration, verify WinSW, install ChristopherBellDev, and validate cloudflared."; return }
    New-ProductionDirectories $root
    Install-ConfigurationExamples $root
    $config = Read-ProductionConfig (Join-Path $root 'config\deploy.json')
    Read-ProductionEnvironment (Join-Path $root 'config\app.env') | Out-Null
    Protect-ProductionSecrets $root
    Install-CloudflaredService -Executable $config.cloudflaredExe -TokenPath $CloudflareTokenPath
    Set-Service MongoDB -StartupType Automatic
    & sc.exe failure MongoDB reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure MongoDB service recovery.' }
    $service = Join-Path $root 'service'
    $binary = Install-WinSwBinary -ServiceRoot $service
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

Export-ModuleMember -Function Assert-Administrator,New-ProductionDirectories,Install-ConfigurationExamples,Protect-ProductionSecrets,Install-CloudflaredService,Install-WinSwBinary,Install-ProductionRuntime,Uninstall-ProductionRuntime
