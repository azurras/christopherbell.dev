Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-ProductionConfig {
    [CmdletBinding()]
    param([string]$Path = 'C:\ProgramData\christopherbell.dev\config\deploy.json')

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing deploy config: $Path"
    }
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    foreach ($name in 'repositoryPath','remote','branch','programDataRoot','javaExe','nodeExe','mongoToolsPath','mongoShellExe','cloudflaredExe','backupRoot','publicUrl','smokeAccountEmail') {
        if (-not ($config.PSObject.Properties.Name -contains $name) -or
            [string]::IsNullOrWhiteSpace([string]$config.$name)) {
            throw "Missing deploy config value: $name"
        }
    }
    foreach ($name in 'candidatePort','productionPort') {
        $port = [int]$config.$name
        if ($port -lt 1 -or $port -gt 65535) { throw "$name must be between 1 and 65535." }
    }
    $sensorProperty = $config.PSObject.Properties['sensorLibrariesEnabled']
    if (-not $sensorProperty -or $sensorProperty.Value -isnot [bool]) {
        throw 'sensorLibrariesEnabled must be a Boolean.'
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
    foreach ($name in 'repositoryPath','javaExe','nodeExe','mongoToolsPath','mongoShellExe','cloudflaredExe','backupRoot') {
        if (-not (Test-Path -LiteralPath $config.$name)) { throw "Configured path does not exist: $name" }
    }
    return $config
}

function ConvertTo-NativeProcessArgument {
    [CmdletBinding()]
    param([AllowEmptyString()][Parameter(Mandatory)][string]$Argument)

    if ($Argument.Length -eq 0) { return '""' }
    if ($Argument -notmatch '[\s"]') { return $Argument }

    $escaped = [Text.StringBuilder]::new()
    [void]$escaped.Append('"')
    $backslashCount = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq [char]'\') {
            $backslashCount++
            continue
        }
        if ($character -eq [char]'"') {
            [void]$escaped.Append([char]'\', (2 * $backslashCount) + 1)
            [void]$escaped.Append('"')
            $backslashCount = 0
            continue
        }
        if ($backslashCount -gt 0) {
            [void]$escaped.Append([char]'\', $backslashCount)
            $backslashCount = 0
        }
        [void]$escaped.Append($character)
    }
    if ($backslashCount -gt 0) {
        [void]$escaped.Append([char]'\', 2 * $backslashCount)
    }
    [void]$escaped.Append('"')
    return $escaped.ToString()
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
    $escapedArguments = @($ArgumentList | ForEach-Object { ConvertTo-NativeProcessArgument -Argument $_ })
    $start.Arguments = [string]::Join(' ', $escapedArguments)
    foreach ($entry in $Environment.GetEnumerator()) {
        $start.EnvironmentVariables[$entry.Key] = [string]$entry.Value
    }
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

function New-ProtectedProductionAcl {
    [CmdletBinding()]
    param([switch]$Directory)

    $acl = if ($Directory) {
        [Security.AccessControl.DirectorySecurity]::new()
    } else {
        [Security.AccessControl.FileSecurity]::new()
    }
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $inheritance = if ($Directory) {
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        [Security.AccessControl.InheritanceFlags]::None
    }
    $propagation = [Security.AccessControl.PropagationFlags]::None
    $allow = [Security.AccessControl.AccessControlType]::Allow

    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($administrators)
    foreach ($identity in $system,$administrators) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance,
            $propagation,
            $allow)
        [void]$acl.AddAccessRule($rule)
    }
    return $acl
}

function Assert-ProductionPathNotReparse {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Protected production path must not contain a reparse point: $Path"
    }
    return $item
}

function Assert-ProductionTreeNotReparse {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    Assert-ProductionPathNotReparse -Path $Path | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction Stop) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Protected production tree must not contain a reparse point: $($item.FullName)"
        }
    }
}

function Assert-ProtectedProductionPath {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    Assert-ProductionPathNotReparse -Path $Path | Out-Null
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    if (-not $acl.AreAccessRulesProtected) {
        throw "Protected production path must disable ACL inheritance: $Path"
    }
    $allowed = @('S-1-5-18','S-1-5-32-544')
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($allowed -notcontains $owner) {
        throw "Protected production path has an untrusted owner: $Path"
    }
    $rules = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 2) {
        throw "Protected production path must have exactly two privileged ACL entries: $Path"
    }
    foreach ($rule in $rules) {
        $identity = $rule.IdentityReference.Value
        $fullControl = [Security.AccessControl.FileSystemRights]::FullControl
        if ($allowed -notcontains $identity -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            ($rule.FileSystemRights -band $fullControl) -ne $fullControl) {
            throw "Protected production path grants access outside SYSTEM and Administrators: $Path"
        }
    }
}

function Protect-ProductionPath {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    $item = Assert-ProductionPathNotReparse -Path $Path
    $acl = New-ProtectedProductionAcl -Directory:$item.PSIsContainer
    Set-Acl -LiteralPath $Path -AclObject $acl -ErrorAction Stop
    Assert-ProtectedProductionPath -Path $Path
}

function Protect-ProductionTree {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    Assert-ProductionTreeNotReparse -Path $Path
    Protect-ProductionPath -Path $Path
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction Stop) {
        Protect-ProductionPath -Path $item.FullName
    }
    Assert-ProtectedProductionTree -Path $Path
}

function Assert-ProtectedProductionTree {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    Assert-ProductionTreeNotReparse -Path $Path
    Assert-ProtectedProductionPath -Path $Path
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction Stop) {
        Assert-ProtectedProductionPath -Path $item.FullName
    }
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
    $required = @('APP_JWT_SECRET','RESEND_API_KEY','APP_MAIL_FROM','SPRING_MONGODB_URI')
    $optional = @('APP_SHARED_FOLDER_ENABLED')
    $allowed = @($required) + @($optional)
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z0-9_]+)=(.*)$') { throw 'Invalid app.env line.' }
        if ($allowed -notcontains $Matches[1]) { throw "Unsupported app.env key: $($Matches[1])" }
        $values[$Matches[1]] = $Matches[2]
    }
    foreach ($requiredKey in $required) {
        if (-not $values.ContainsKey($requiredKey) -or
            [string]::IsNullOrWhiteSpace($values[$requiredKey])) {
            throw "Missing app.env value: $requiredKey"
        }
    }
    if ($values.ContainsKey('APP_SHARED_FOLDER_ENABLED') -and
        $values.APP_SHARED_FOLDER_ENABLED -notin @('true','false')) {
        throw 'APP_SHARED_FOLDER_ENABLED must be a Boolean.'
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

function Get-TrustedGitArguments {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepositoryPath,
        [Parameter(Mandatory)][string[]]$ArgumentList
    )
    $safeDirectory = [IO.Path]::GetFullPath($RepositoryPath).Replace('\','/')
    return @('-c',"safe.directory=$safeDirectory",'-C',$RepositoryPath) + $ArgumentList
}

function Show-ProductionHelp {
    @'
Usage: prod.cmd <command> [-WhatIf]

Commands: install, deploy, status, logs, restart, releases, rollback, backup,
          verify-startup, uninstall, auto-install, auto-deploy, auto-status,
          auto-remove, sensor-install, sensor-status, sensor-enable,
          sensor-disable
'@ | Write-Output
}

Export-ModuleMember -Function Read-ProductionConfig,Invoke-CheckedProcess,Enter-DeploymentLock,New-ProtectedProductionAcl,Assert-ProductionPathNotReparse,Assert-ProductionTreeNotReparse,Assert-ProtectedProductionPath,Protect-ProductionPath,Protect-ProductionTree,Assert-ProtectedProductionTree,Wait-HttpStatus,Read-ProductionEnvironment,Assert-ReleasePath,Get-JunctionTarget,Set-AtomicJunction,Get-TrustedGitArguments,Show-ProductionHelp
