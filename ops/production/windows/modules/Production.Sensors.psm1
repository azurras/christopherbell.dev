Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:PawnIoUri = 'https://github.com/namazso/PawnIO.Setup/releases/download/2.2.0/PawnIO_setup.exe'
$script:PawnIoSha256 = '1F519A22E47187F70A1379A48CA604981C4FCF694F4E65B734AAA74A9FBA3032'
$script:PawnIoSignerThumbprint = 'F380DCC9F706E2756A5047B832FFE719E1BC35F5'
$script:PawnIoVersion = '2.2.0'
$script:PawnIoRegistryPaths = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\PawnIO',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\PawnIO')

function Assert-SensorAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Sensor provider operations require elevated PowerShell.'
    }
}

function Assert-NoActiveSensorThreat {
    $active = @(Get-MpThreat -ErrorAction Stop | Where-Object {
        $_.IsActive -and [string]$_.ThreatName -match '(?i)(Winring0|PawnIO)'
    })
    if ($active.Count -gt 0) { throw 'Microsoft Defender reports an active sensor-provider threat.' }
}

function Assert-PawnIoInstaller {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'PawnIO installer is missing.' }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $script:PawnIoSha256) {
        throw 'PawnIO installer SHA-256 verification failed.'
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    $thumbprint = if ($signature.SignerCertificate) { [string]$signature.SignerCertificate.Thumbprint } else { '' }
    if ([string]$signature.Status -ne 'Valid' -or $thumbprint -ne $script:PawnIoSignerThumbprint) {
        throw 'PawnIO installer publisher verification failed.'
    }
}

function Get-PawnIoInstallation {
    foreach ($path in $script:PawnIoRegistryPaths) {
        if (Test-Path -LiteralPath $path) {
            $entry = Get-ItemProperty -LiteralPath $path
            $driver = Get-CimInstance Win32_SystemDriver -Filter "Name='PawnIO'" -ErrorAction SilentlyContinue
            $driverPath = if ($driver) {
                [Environment]::ExpandEnvironmentVariables([string]$driver.PathName).Trim('"')
            } else { $null }
            if ($driverPath -and $driverPath.StartsWith('\SystemRoot', [StringComparison]::OrdinalIgnoreCase)) {
                $driverPath = Join-Path $env:SystemRoot $driverPath.Substring(12)
            }
            if ($driverPath -and $driverPath.StartsWith('\??\')) { $driverPath = $driverPath.Substring(4) }
            $driverSignature = if ($driverPath -and (Test-Path -LiteralPath $driverPath -PathType Leaf)) {
                (Get-AuthenticodeSignature -LiteralPath $driverPath).Status.ToString()
            } else { 'Missing' }
            return [pscustomobject]@{
                Version = [string]$entry.DisplayVersion
                Driver = if ($driver) { [string]$driver.State } else { 'Missing' }
                DriverPath = $driverPath
                DriverSignature = $driverSignature
                UninstallString = [string]$entry.UninstallString
            }
        }
    }
    return $null
}

function Assert-PawnIoInstallation {
    $installation = Get-PawnIoInstallation
    if (-not $installation -or $installation.Version -ne $script:PawnIoVersion) {
        throw "PawnIO $($script:PawnIoVersion) is not installed."
    }
    if ($installation.Driver -ne 'Running') { throw 'PawnIO driver is not Running.' }
    if ($installation.DriverSignature -ne 'Valid') { throw 'PawnIO driver signature is not valid.' }
    if (-not $installation.UninstallString -or $installation.UninstallString.Trim().Length -eq 0) {
        throw 'PawnIO verified uninstall registration is missing.'
    }
    return $installation
}

function Write-ProductionSensorConfig {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][bool]$Enabled)
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($config.PSObject.Properties.Name -contains 'sensorLibrariesEnabled') {
        if ($config.PSObject.Properties['sensorLibrariesEnabled'].Value -isnot [bool]) {
            throw 'sensorLibrariesEnabled must be a Boolean.'
        }
        $config.sensorLibrariesEnabled = $Enabled
    } else {
        $config | Add-Member -NotePropertyName sensorLibrariesEnabled -NotePropertyValue $Enabled
    }
    $next = "$Path.next"
    try {
        $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $next -Encoding utf8
        Move-Item -LiteralPath $next -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $next) { Remove-Item -LiteralPath $next -Force }
    }
}

function Set-ProductionSensorState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][bool]$Enabled,
        [string]$ConfigPath = 'C:\ProgramData\christopherbell.dev\config\deploy.json',
        [switch]$WhatIf
    )
    Assert-SensorAdministrator
    if ($Enabled) { Assert-NoActiveSensorThreat; Assert-PawnIoInstallation | Out-Null }
    if ($WhatIf) { Write-Output "Would set sensorLibrariesEnabled=$($Enabled.ToString().ToLowerInvariant()) and verify ChristopherBellDev."; return }
    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    $sensorProperty = $config.PSObject.Properties['sensorLibrariesEnabled']
    if (-not $sensorProperty -or $sensorProperty.Value -isnot [bool]) {
        throw 'sensorLibrariesEnabled must be a Boolean.'
    }
    $previous = $sensorProperty.Value
    Write-ProductionSensorConfig -Path $ConfigPath -Enabled $Enabled
    try {
        Restart-Service -Name ChristopherBellDev
        $updated = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
        Test-ProductionEndpoints $updated ([int]$updated.productionPort)
    } catch {
        Write-ProductionSensorConfig -Path $ConfigPath -Enabled $previous
        Restart-Service -Name ChristopherBellDev
        throw
    }
}

function Install-PawnIoProvider {
    [CmdletBinding()]
    param([string]$Root = 'C:\ProgramData\christopherbell.dev', [switch]$WhatIf)
    Assert-SensorAdministrator
    $configPath = Join-Path $Root 'config\deploy.json'
    Set-ProductionSensorState -Enabled $false -ConfigPath $configPath -WhatIf:$WhatIf
    if ($WhatIf) { Write-Output 'Would download, verify, Defender-scan, and install PawnIO 2.2.0 without enabling sensors.'; return }
    Protect-ProductionPath -Path $Root
    $directory = Join-Path $Root 'sensors'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    Protect-ProductionPath -Path $directory
    $staging = Join-Path $directory ([guid]::NewGuid().ToString('D'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    Protect-ProductionPath -Path $staging
    $installer = Join-Path $staging 'PawnIO_setup-2.2.0.exe'
    try {
        Invoke-WebRequest -Uri $script:PawnIoUri -OutFile $installer
        Protect-ProductionTree -Path $staging
        Assert-ProtectedProductionTree -Path $staging
        Assert-PawnIoInstaller -Path $installer
        Start-MpScan -ScanType CustomScan -ScanPath $installer
        Assert-NoActiveSensorThreat
        Assert-ProtectedProductionTree -Path $staging
        Assert-PawnIoInstaller -Path $installer
        $process = Start-Process -FilePath $installer -ArgumentList '/S' -Wait -PassThru
        if ($process.ExitCode -eq 3010) { throw 'PawnIO installation stopped: reboot required; sensors remain disabled.' }
        if ($process.ExitCode -ne 0) { throw "PawnIO installer exited with code $($process.ExitCode); sensors remain disabled." }
        $installation = Assert-PawnIoInstallation
        Start-MpScan -ScanType CustomScan -ScanPath $directory
        Assert-NoActiveSensorThreat
        return $installation
    } finally {
        if (Test-Path -LiteralPath $staging) {
            Remove-Item -LiteralPath $staging -Recurse -Force
        }
    }
}

function Get-ProductionSensorStatus {
    [CmdletBinding()]
    param([string]$ConfigPath = 'C:\ProgramData\christopherbell.dev\config\deploy.json')
    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    $sensorProperty = $config.PSObject.Properties['sensorLibrariesEnabled']
    if (-not $sensorProperty -or $sensorProperty.Value -isnot [bool]) {
        throw 'sensorLibrariesEnabled must be a Boolean.'
    }
    $installation = Get-PawnIoInstallation
    $activeThreats = @(Get-MpThreat -ErrorAction Stop | Where-Object {
        $_.IsActive -and [string]$_.ThreatName -match '(?i)(Winring0|PawnIO)'
    })
    [pscustomobject]@{
        Enabled = $sensorProperty.Value
        PawnIoVersion = if ($installation) { $installation.Version } else { 'NotInstalled' }
        Driver = if ($installation) { $installation.Driver } else { 'Missing' }
        DriverPath = if ($installation) { $installation.DriverPath } else { $null }
        DriverSignature = if ($installation) { $installation.DriverSignature } else { 'Missing' }
        UninstallRegistered = $null -ne $installation -and $installation.UninstallString -and $installation.UninstallString.Trim().Length -gt 0
        ActiveThreats = $activeThreats.Count
    }
}

Export-ModuleMember -Function Assert-PawnIoInstaller,Get-PawnIoInstallation,Assert-PawnIoInstallation,Install-PawnIoProvider,Set-ProductionSensorState,Get-ProductionSensorStatus
