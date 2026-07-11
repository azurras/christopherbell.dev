Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-ProductionConfig {
    [CmdletBinding()]
    param([string]$Path = 'C:\ProgramData\christopherbell.dev\config\deploy.json')

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing deploy config: $Path"
    }
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    foreach ($name in 'repositoryPath','remote','branch','programDataRoot','javaExe','nodeExe','mongoToolsPath','mongoShellExe','backupRoot','wslDistro','wslWebsiteStopCommand','wslWebsiteStartCommand','wslMongoStopCommand','wslMongoStartCommand','smokeAccountEmail') {
        if (-not ($config.PSObject.Properties.Name -contains $name) -or
            [string]::IsNullOrWhiteSpace([string]$config.$name)) {
            throw "Missing deploy config value: $name"
        }
    }
    foreach ($name in 'candidatePort','productionPort') {
        $port = [int]$config.$name
        if ($port -lt 1 -or $port -gt 65535) { throw "$name must be between 1 and 65535." }
    }
    if ([int]$config.candidatePort -eq [int]$config.productionPort) {
        throw 'Candidate and production ports must differ.'
    }
    if ([int]$config.releaseRetention -lt 2) { throw 'releaseRetention must be at least 2.' }
    if ([int]$config.autoDeployPollSeconds -lt 15) { throw 'autoDeployPollSeconds must be at least 15.' }
    if ([int]$config.autoDeployFailureBackoffSeconds -lt [int]$config.autoDeployPollSeconds) {
        throw 'autoDeployFailureBackoffSeconds must not be shorter than the poll interval.'
    }
    if ([string]$config.smokeAccountEmail -eq 'operator@example.com') {
        throw 'smokeAccountEmail must be configured for a real production account.'
    }
    foreach ($name in 'repositoryPath','javaExe','nodeExe','mongoToolsPath','mongoShellExe','backupRoot') {
        if (-not (Test-Path -LiteralPath $config.$name)) { throw "Configured path does not exist: $name" }
    }
    return $config
}

function Invoke-CheckedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory = (Get-Location).Path,
        [hashtable]$Environment = @{}
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.WorkingDirectory = $WorkingDirectory
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) { [void]$start.ArgumentList.Add($argument) }
    foreach ($entry in $Environment.GetEnumerator()) { $start.Environment[$entry.Key] = [string]$entry.Value }
    $process = [Diagnostics.Process]::Start($start)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    [void]$stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "$([IO.Path]::GetFileName($FilePath)) exited with code $($process.ExitCode)."
    }
    return $stdout
}

function Enter-DeploymentLock {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$LockPath)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LockPath) | Out-Null
    try { return [IO.File]::Open($LockPath, 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { throw 'Another production operation is already running.' }
}

function Wait-HttpStatus {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][uri]$Uri,
        [Parameter(Mandatory)][int]$ExpectedStatus,
        [timespan]$Timeout = [timespan]::FromSeconds(60)
    )
    $deadline = [DateTime]::UtcNow + $Timeout
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -SkipHttpErrorCheck -TimeoutSec 5
            if ([int]$response.StatusCode -eq $ExpectedStatus) { return $response }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for HTTP $ExpectedStatus from $Uri."
}

function Read-ProductionEnvironment {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing environment file: $Path" }
    $allowed = @('APP_JWT_SECRET','RESEND_API_KEY','APP_MAIL_FROM','SPRING_MONGODB_URI')
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z0-9_]+)=(.*)$') { throw 'Invalid app.env line.' }
        if ($allowed -notcontains $Matches[1]) { throw "Unsupported app.env key: $($Matches[1])" }
        $values[$Matches[1]] = $Matches[2]
    }
    foreach ($required in $allowed) {
        if (-not $values.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($values[$required])) {
            throw "Missing app.env value: $required"
        }
    }
    if ($values.APP_JWT_SECRET -match '^replace-with-' -or $values.APP_JWT_SECRET.Length -lt 32) {
        throw 'APP_JWT_SECRET must be a non-placeholder value of at least 32 characters.'
    }
    if ($values.RESEND_API_KEY -eq 're_your_resend_api_key') { throw 'RESEND_API_KEY must not use the example value.' }
    if ($values.APP_MAIL_FROM -eq 'noreply@your-verified-domain.com') { throw 'APP_MAIL_FROM must not use the example value.' }
    return $values
}

function Assert-ReleasePath {
    param($Config, [Parameter(Mandatory)][string]$Path)
    $root = [IO.Path]::GetFullPath((Join-Path $Config.programDataRoot 'releases'))
    $candidate = [IO.Path]::GetFullPath($Path)
    if (-not $candidate.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Release path must remain below the releases directory.'
    }
    return $candidate
}

function Get-JunctionTarget {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $item = Get-Item -LiteralPath $Path -Force
    if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "$Path is not a junction." }
    return [IO.Path]::GetFullPath([string]$item.Target)
}

function Set-AtomicJunction {
    param($Config, [Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Target)
    $safeTarget = Assert-ReleasePath $Config $Target
    if (-not (Test-Path -LiteralPath $safeTarget -PathType Container)) { throw "Release does not exist: $safeTarget" }
    $temporary = "$Path.next"
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    New-Item -ItemType Junction -Path $temporary -Target $safeTarget | Out-Null
    if (Test-Path -LiteralPath $Path) {
        $old = "$Path.old"
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Force }
        Move-Item -LiteralPath $Path -Destination $old
        Move-Item -LiteralPath $temporary -Destination $Path
        Remove-Item -LiteralPath $old -Force
    } else { Move-Item -LiteralPath $temporary -Destination $Path }
}

function Show-ProductionHelp {
    @'
Usage: prod.cmd <command> [-WhatIf]

Commands: install, migrate, deploy, status, logs, restart, releases, rollback,
          backup, uninstall, auto-install, auto-deploy, auto-status, auto-remove
'@ | Write-Output
}

Export-ModuleMember -Function Read-ProductionConfig,Invoke-CheckedProcess,Enter-DeploymentLock,Wait-HttpStatus,Read-ProductionEnvironment,Assert-ReleasePath,Get-JunctionTarget,Set-AtomicJunction,Show-ProductionHelp
