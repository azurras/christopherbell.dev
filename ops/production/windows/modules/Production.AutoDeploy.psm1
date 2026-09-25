Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:FixedProductionRoot = 'C:\ProgramData\christopherbell.dev'

$script:autoDeployStatusWarningEmitted = $false

function New-AutoDeployState {
    [pscustomobject][ordered]@{
        lastCheckedAt=$null
        remoteSha=$null
        attemptedSha=$null
        successfulSha=$null
        failedSha=$null
        failedAt=$null
        error=$null
        toolsSha=$null
        toolSourceSha=$null
        toolRefreshStatus='UNKNOWN'
        toolRefreshAt=$null
        serviceRecoverySha=$null
        serviceRecoveryAt=$null
    }
}

function Read-AutoDeployState {
    param($Config)
    $path = Join-Path $Config.programDataRoot 'state\auto-deploy.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return New-AutoDeployState }
    $json = Get-Content -LiteralPath $path -Raw -ErrorAction Stop
    try {
        $state = ConvertFrom-Json -InputObject $json -ErrorAction Stop
        foreach ($property in @{
            toolsSha = $null
            toolSourceSha = $null
            toolRefreshStatus = 'UNKNOWN'
            toolRefreshAt = $null
            serviceRecoverySha = $null
            serviceRecoveryAt = $null
        }.GetEnumerator()) {
            if (-not $state.PSObject.Properties[$property.Key]) {
                $state | Add-Member -NotePropertyName $property.Key -NotePropertyValue $property.Value
            }
        }
        return $state
    }
    catch { throw 'Automatic deployment state is invalid JSON.' }
}

function Write-AutoDeployState {
    param($Config, $State)
    $path = Join-Path $Config.programDataRoot 'state\auto-deploy.json'
    New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $State | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporary -Encoding utf8
        Move-AutoDeployFileAtomically -TemporaryPath $temporary -DestinationPath $path
    } finally { if (Test-Path $temporary) { Remove-Item $temporary -Force -ErrorAction SilentlyContinue } }
}

function Get-RemoteMainSha {
    param($Config)
    $arguments = Get-TrustedGitArguments $Config.repositoryPath @('ls-remote',$Config.remote,"refs/heads/$($Config.branch)")
    $output = Invoke-CheckedProcess 'git.exe' $arguments $Config.repositoryPath
    $sha = (($output.Trim() -split '\s+')[0]).ToLowerInvariant()
    if ($sha -notmatch '^[0-9a-f]{40}$') { throw 'Remote main returned an invalid SHA.' }
    return $sha
}

function Get-ActiveReleaseSha {
    param($Config)
    $metadata = Join-Path $Config.programDataRoot 'current\release.json'
    if (-not (Test-Path -LiteralPath $metadata -PathType Leaf)) { return $null }
    $sha = (Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json).sha
    if ($sha -notmatch '^[0-9a-f]{40}$') { throw 'Active release metadata contains an invalid SHA.' }
    return $sha
}

function Get-AutoDeployStatusStoreRoot {
    $parent = Split-Path -Parent $script:FixedProductionRoot
    return Join-Path $parent 'christopherbell.dev-status'
}

function Move-AutoDeployFileAtomically {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$TemporaryPath,
        [Parameter(Mandatory)][string]$DestinationPath
    )

    if (Test-Path -LiteralPath $DestinationPath) {
        Replace-AutoDeployFile -TemporaryPath $TemporaryPath -DestinationPath $DestinationPath
        return
    }
    try {
        [IO.File]::Move($TemporaryPath,$DestinationPath)
    } catch {
        if (-not (Test-Path -LiteralPath $DestinationPath)) { throw }
        Replace-AutoDeployFile -TemporaryPath $TemporaryPath -DestinationPath $DestinationPath
    }
}

function Replace-AutoDeployFile {
    param(
        [Parameter(Mandatory)][string]$TemporaryPath,
        [Parameter(Mandatory)][string]$DestinationPath
    )
    $backupPath = "$DestinationPath.$PID.$([guid]::NewGuid().ToString('N')).bak"
    try {
        [IO.File]::Replace($TemporaryPath,$DestinationPath,$backupPath)
    } finally {
        if (Test-Path -LiteralPath $backupPath) {
            Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function New-AutoDeployStatusDirectoryAcl {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
    $allow = [Security.AccessControl.AccessControlType]::Allow
    $none = [Security.AccessControl.PropagationFlags]::None
    $fullInheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    foreach ($identity in $system,$administrators) {
        [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            $fullInheritance,
            $none,
            $allow))
    }
    [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $users,
        [Security.AccessControl.FileSystemRights]::ReadAndExecute,
        [Security.AccessControl.InheritanceFlags]::ContainerInherit,
        $none,
        $allow))
    [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $users,
        [Security.AccessControl.FileSystemRights]::Read,
        [Security.AccessControl.InheritanceFlags]::ObjectInherit,
        $none,
        $allow))
    return $acl
}

function Assert-AutoDeployStatusDirectory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (-not $item.PSIsContainer -or
        $item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Automatic deployment status directory is not a normal directory.'
    }
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    if (-not $acl.AreAccessRulesProtected) {
        throw 'Automatic deployment status directory must disable ACL inheritance.'
    }
    $rules = @($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]))
    $expected = @(
        [pscustomobject]@{
            Sid='S-1-5-18'
            Rights=[Security.AccessControl.FileSystemRights]::FullControl
            Inheritance=[Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        },
        [pscustomobject]@{
            Sid='S-1-5-32-544'
            Rights=[Security.AccessControl.FileSystemRights]::FullControl
            Inheritance=[Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        },
        [pscustomobject]@{
            Sid='S-1-5-32-545'
            Rights=[Security.AccessControl.FileSystemRights]::ReadAndExecute
            Inheritance=[Security.AccessControl.InheritanceFlags]::ContainerInherit
        },
        [pscustomobject]@{
            Sid='S-1-5-32-545'
            Rights=[Security.AccessControl.FileSystemRights]::Read
            Inheritance=[Security.AccessControl.InheritanceFlags]::ObjectInherit
        })
    if ($rules.Count -ne $expected.Count) {
        throw 'Automatic deployment status directory has an unexpected ACL.'
    }
    $rightsMask = [int]::MaxValue -bxor [int][Security.AccessControl.FileSystemRights]::Synchronize
    foreach ($entry in $expected) {
        $matches = @($rules | Where-Object {
            $_.IdentityReference.Value -eq $entry.Sid -and
            $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            (([int]$_.FileSystemRights -band $rightsMask) -eq
                ([int]$entry.Rights -band $rightsMask)) -and
            $_.InheritanceFlags -eq $entry.Inheritance -and
            $_.PropagationFlags -eq [Security.AccessControl.PropagationFlags]::None
        })
        if ($matches.Count -ne 1) {
            throw 'Automatic deployment status directory has an unexpected ACL.'
        }
    }
}

function Initialize-AutoDeployStatusStore {
    [CmdletBinding()]
    param([string]$StatusRoot = (Get-AutoDeployStatusStoreRoot))

    $root = [IO.Path]::GetFullPath($StatusRoot)
    $parent = Split-Path -Parent $root
    Assert-ProductionPathNotReparse -Path $parent | Out-Null
    if (Test-Path -LiteralPath $root) {
        Assert-AutoDeployStatusDirectory -Path $root
        return $root
    }

    $stage = Join-Path $parent (
        '.christopherbell.dev-status-{0}' -f [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Path $stage -ErrorAction Stop | Out-Null
        Set-Acl -LiteralPath $stage -AclObject (New-AutoDeployStatusDirectoryAcl) -ErrorAction Stop
        if (@(Get-ChildItem -LiteralPath $stage -Force -ErrorAction Stop).Count -ne 0) {
            throw 'Automatic deployment status stage was modified during creation.'
        }
        Assert-AutoDeployStatusDirectory -Path $stage
        Assert-ProductionPathNotReparse -Path $parent | Out-Null
        try {
            [IO.Directory]::Move($stage,$root)
        } catch {
            if (-not (Test-Path -LiteralPath $root)) { throw }
            Assert-AutoDeployStatusDirectory -Path $root
        }
        Assert-AutoDeployStatusDirectory -Path $root
        return $root
    } finally {
        if (Test-Path -LiteralPath $stage -PathType Container) {
            if (@(Get-ChildItem -LiteralPath $stage -Force -ErrorAction Stop).Count -eq 0) {
                [IO.Directory]::Delete($stage,$false)
            }
        }
    }
}

function Assert-AutoDeployStatusFile {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.PSIsContainer -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Automatic deployment status file is not a normal file.'
    }
    if ($item.Length -gt 8192) { throw 'Automatic deployment status file exceeds its size limit.' }
    $rules = @((Get-Acl -LiteralPath $Path -ErrorAction Stop).GetAccessRules(
        $true,$true,[Security.Principal.SecurityIdentifier]))
    $system = 'S-1-5-18'
    $administrators = 'S-1-5-32-544'
    $users = 'S-1-5-32-545'
    foreach ($rule in $rules) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rule.IdentityReference.Value -notin @($system,$administrators,$users)) {
            throw 'Automatic deployment status file has an unexpected ACL.'
        }
        if ($rule.IdentityReference.Value -eq $users -and
            ($rule.FileSystemRights -band (
                [Security.AccessControl.FileSystemRights]::Write -bor
                [Security.AccessControl.FileSystemRights]::Delete -bor
                [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
                [Security.AccessControl.FileSystemRights]::TakeOwnership)) -ne 0) {
            throw 'Automatic deployment status file grants users write access.'
        }
    }
    if (-not ($rules | Where-Object {
        $_.IdentityReference.Value -eq $users -and
        ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Read) -eq
            [Security.AccessControl.FileSystemRights]::Read
    })) {
        throw 'Automatic deployment status file is not readable by standard users.'
    }
}

function Get-AutoDeployStatusMessage {
    param([Parameter(Mandatory)][string]$Outcome)
    switch ($Outcome) {
        'CHECKING' { 'Checking the trusted main branch and active release.' }
        'UP_TO_DATE' { 'The active release matches the trusted main branch.' }
        'BACKING_OFF' { 'Retry is deferred for the recorded failed revision.' }
        'DEPLOYING' { 'Building and validating the latest trusted revision.' }
        'SUCCEEDED' { 'The new release is active.' }
        'DEPLOYMENT_FAILED' { 'Automatic deployment failed; details remain in protected diagnostics.' }
        'CHECK_FAILED' { 'The automatic deployment check failed before release validation.' }
        'BLOCKED' { 'Automatic deployment is blocked by a protected migration gate.' }
        'TOOLS_UPDATED' { 'Trusted deployment tools were refreshed; the next scheduled poll will continue.' }
        default { throw 'Automatic deployment status outcome is invalid.' }
    }
}

function Get-AutoDeployFailureMessage {
    param([Parameter(Mandatory)][System.Exception]$Exception)

    $pending = [Collections.Generic.Stack[System.Exception]]::new()
    $messages = [Collections.Generic.List[string]]::new()
    $pending.Push($Exception)
    while ($pending.Count -gt 0 -and $messages.Count -lt 3) {
        $cause = $pending.Pop()
        if ($cause -is [System.AggregateException]) {
            $innerExceptions = @($cause.Flatten().InnerExceptions)
            for ($index = $innerExceptions.Count - 1; $index -ge 0; $index--) {
                $pending.Push($innerExceptions[$index])
            }
        } elseif ($cause.InnerException) {
            $pending.Push($cause.InnerException)
        } elseif (-not [string]::IsNullOrWhiteSpace($cause.Message)) {
            $messages.Add([string]$cause.Message)
        }
    }
    return [string]::Join(' | ', $messages.ToArray())
}

function Get-AutoDeploySafeFailureDetail {
    param([string]$Message)

    if ([string]::IsNullOrWhiteSpace($Message)) { return $null }
    $detail = $Message -replace '[\r\n\t]+', ' '
    $detail = [regex]::Replace(
        $detail,
        "(?i)\b[a-z][a-z0-9+.-]*://[^\s<>""']+",
        '[redacted]')
    $detail = [regex]::Replace(
        $detail,
        '(?i)\b(password|passwd|pwd|secret|token|api[_-]?key)\s*[:=]\s*[^\s,;]+',
        '$1=[redacted]')
    $detail = [regex]::Replace(
        $detail,
        '(?i)([''"])(?:[a-z]:\\|\\\\[^\\\s]+\\)[^''"]*\1',
        '[redacted]')
    $detail = [regex]::Replace(
        $detail,
        '(?i)(?<![\w])(?:[a-z]:\\|\\\\[^\\\s]+\\)[^\s,"''<>;]+',
        '[redacted]')
    $detail = [regex]::Replace(
        $detail,
        '(?<![\w:])/(?:[^/\s:]+/)+[^/\s:]+',
        '[redacted]')
    $detail = [regex]::Replace($detail, '\s+', ' ').Trim()
    if ($detail.Length -gt 240) { $detail = $detail.Substring(0, 240).TrimEnd() }
    return $detail
}

function New-UnavailableAutoDeployStatus {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('STORE_NOT_INITIALIZED','STATUS_NOT_PUBLISHED','ACCESS_DENIED',
            'FUTURE_TIMESTAMP','INVALID')]
        [string]$Reason
    )
    $message = switch ($Reason) {
        'STORE_NOT_INITIALIZED' { 'The automatic deployment status store has not been initialized.' }
        'STATUS_NOT_PUBLISHED' { 'The automatic deployment poller has not published its first result.' }
        'ACCESS_DENIED' { 'The automatic deployment status cannot be read with this account.' }
        'FUTURE_TIMESTAMP' { 'The automatic deployment status timestamp is later than the local clock.' }
        'INVALID' { 'The automatic deployment status is invalid.' }
    }
    return [pscustomobject]@{
        available = $false
        freshness = 'UNAVAILABLE'
        status = 'UNKNOWN'
        reason = $Reason
        updatedAt = $null
        message = $message
    }
}

function Publish-AutoDeployStatusBestEffort {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Outcome,
        [Parameter(Mandatory)]$State,
        [string]$FailureCategory = 'NONE',
        [string]$ActiveSha,
        [string]$RetryAt,
        [string]$StatusRoot
    )

    try {
        $arguments = @{
            Outcome = $Outcome
            State = $State
            FailureCategory = $FailureCategory
            ActiveSha = $ActiveSha
            RetryAt = $RetryAt
        }
        if ($StatusRoot) { $arguments.StatusRoot = $StatusRoot }
        Publish-AutoDeployStatus @arguments
        return $true
    } catch {
        if (-not $script:autoDeployStatusWarningEmitted) {
            $script:autoDeployStatusWarningEmitted = $true
            $category = if ($_.Exception -is [UnauthorizedAccessException]) {
                'ACCESS_DENIED'
            } else {
                'WRITE_FAILED'
            }
            try {
                $warningMessage = 'Automatic deployment status could not be published ({0}); ' -f $category
                $warningMessage += 'operator-visible status may be stale.'
                Write-Warning $warningMessage
            } catch {
                # Warning preferences must not replace the deployment result.
            }
        }
        return $false
    }
}

function Publish-AutoDeployStatus {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('CHECKING','UP_TO_DATE','BACKING_OFF','DEPLOYING','SUCCEEDED',
            'DEPLOYMENT_FAILED','CHECK_FAILED','BLOCKED','TOOLS_UPDATED')]
        [string]$Outcome,
        [Parameter(Mandatory)]$State,
        [ValidateSet('NONE','REMOTE_CHECK','PROTECTED_PRECONDITION','DEPLOYMENT',
            'CANDIDATE_STARTUP','STATUS_STORE')]
        [string]$FailureCategory = 'NONE',
        [string]$ActiveSha,
        [string]$RetryAt,
        [datetime]$UpdatedAt = (Get-Date).ToUniversalTime(),
        [string]$StatusRoot = (Get-AutoDeployStatusStoreRoot)
    )

    Assert-AutoDeployStatusDirectory -Path $StatusRoot
    $path = Join-Path $StatusRoot 'auto-deploy.json'
    if (Test-Path -LiteralPath $path) { Assert-AutoDeployStatusFile -Path $path }
    $record = [ordered]@{
        schemaVersion = 1
        updatedAt = $UpdatedAt.ToUniversalTime().ToString('o')
        status = $Outcome
        remoteSha = $State.remoteSha
        activeSha = $ActiveSha
        attemptedSha = $State.attemptedSha
        successfulSha = $State.successfulSha
        failedSha = $State.failedSha
        failedAt = $State.failedAt
        retryAt = $RetryAt
        toolsSha = $State.toolsSha
        toolSourceSha = $State.toolSourceSha
        toolRefreshStatus = $State.toolRefreshStatus
        toolRefreshAt = $State.toolRefreshAt
        failureCategory = $FailureCategory
        failureDetail = if ($Outcome -in @('BACKING_OFF','DEPLOYMENT_FAILED',
                'CHECK_FAILED','BLOCKED')) {
            Get-AutoDeploySafeFailureDetail -Message ([string]$State.error)
        } else { $null }
    }
    foreach ($name in @('remoteSha','activeSha','attemptedSha','successfulSha','failedSha','toolsSha','toolSourceSha')) {
        $value = [string]$record[$name]
        if ($value -and $value -notmatch '^[0-9a-f]{40}$') {
            throw 'Automatic deployment status contains an invalid revision.'
        }
    }
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $record | ConvertTo-Json -Depth 3 |
            Set-Content -LiteralPath $temporary -Encoding utf8 -ErrorAction Stop
        Assert-AutoDeployStatusFile -Path $temporary
        Move-AutoDeployFileAtomically -TemporaryPath $temporary -DestinationPath $path
        Assert-AutoDeployStatusFile -Path $path
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-AutoDeployTaskSchedulerEntry {
    [CmdletBinding()]
    param([object]$SchedulerService)

    try {
        if (-not $SchedulerService) {
            $SchedulerService = New-Object -ComObject Schedule.Service
        }
        $SchedulerService.Connect()
        $task = $SchedulerService.GetFolder('\').GetTask('ChristopherBellAutoDeploy')
        return [pscustomobject]@{
            registered = $true
            state = [int]$task.State
            reason = 'NONE'
        }
    } catch {
        $queryException = $_.Exception
        while ($queryException.InnerException) {
            $queryException = $queryException.InnerException
        }
        $reason = switch ([int]$queryException.HResult) {
            -2147024891 { 'ACCESS_DENIED' }
            -2147024894 { 'TASK_NOT_REGISTERED' }
            default { 'QUERY_FAILED' }
        }
        return [pscustomobject]@{
            registered = $false
            state = $null
            reason = $reason
        }
    }
}

function Get-AutoDeployPollerStatus {
    $entry = Get-AutoDeployTaskSchedulerEntry
    if (-not $entry.registered) {
        if ($entry.reason -eq 'TASK_NOT_REGISTERED') {
            return [pscustomobject]@{
                pollerState = 'NOT_REGISTERED'
                pollerReason = 'TASK_NOT_REGISTERED'
            }
        }
        $reason = if ($entry.reason -in @('ACCESS_DENIED','QUERY_FAILED')) {
            [string]$entry.reason
        } else {
            'QUERY_FAILED'
        }
        return [pscustomobject]@{
            pollerState = 'UNKNOWN'
            pollerReason = $reason
        }
    }
    $state = switch ([int]$entry.state) {
        1 { 'DISABLED' }
        2 { 'QUEUED' }
        3 { 'READY' }
        4 { 'RUNNING' }
        default { $null }
    }
    if (-not $state) {
        return [pscustomobject]@{
            pollerState = 'UNKNOWN'
            pollerReason = 'UNRECOGNIZED_STATE'
        }
    }
    return [pscustomobject]@{
        pollerState = $state
        pollerReason = 'NONE'
    }
}

function Add-AutoDeployPollerStatus {
    param(
        [Parameter(Mandatory)]$Status,
        [Parameter(Mandatory)]$PollerStatus
    )
    Add-Member -InputObject $Status -NotePropertyMembers @{
        pollerState = [string]$PollerStatus.pollerState
        pollerReason = [string]$PollerStatus.pollerReason
    } -Force
    return $Status
}

function Get-AutoDeployWebsiteHealth {
    [CmdletBinding()]
    param([ValidateRange(1,65535)][int]$Port = 8080)

    try {
        $services = @(Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop)
    } catch {
        $reason = if ($_.Exception -is [UnauthorizedAccessException]) {
            'ACCESS_DENIED'
        } else {
            'SERVICE_QUERY_FAILED'
        }
        return [pscustomobject]@{
            serviceState = 'UNKNOWN'
            siteHealth = 'UNKNOWN'
            siteHealthReason = $reason
        }
    }

    if ($services.Count -ne 1) {
        return [pscustomobject]@{
            serviceState = 'UNKNOWN'
            siteHealth = 'UNKNOWN'
            siteHealthReason = 'SERVICE_QUERY_FAILED'
        }
    }

    $serviceState = [string]$services[0].Status
    if ($serviceState -cne 'Running') {
        $reason = if ($serviceState -ceq 'Stopped') {
            'SERVICE_STOPPED'
        } else {
            'SERVICE_NOT_RUNNING'
        }
        return [pscustomobject]@{
            serviceState = $serviceState.ToUpperInvariant()
            siteHealth = 'UNHEALTHY'
            siteHealthReason = $reason
        }
    }

    try {
        Wait-HttpStatus `
            -Uri "http://127.0.0.1:$Port/actuator/health/readiness" `
            -ExpectedStatus 200 `
            -Timeout ([timespan]::FromSeconds(5)) | Out-Null
    } catch {
        return [pscustomobject]@{
            serviceState = 'RUNNING'
            siteHealth = 'UNHEALTHY'
            siteHealthReason = 'READINESS_FAILED'
        }
    }

    return [pscustomobject]@{
        serviceState = 'RUNNING'
        siteHealth = 'HEALTHY'
        siteHealthReason = 'NONE'
    }
}

function Invoke-AutoDeployWebsiteRecovery {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$ActiveSha,
        [Parameter(Mandatory)][datetime]$Now,
        [string]$StatusRoot
    )

    $health = Get-AutoDeployWebsiteHealth -Port ([int]$Config.productionPort)
    if ($health.siteHealth -eq 'HEALTHY') { return $true }
    if ($health.siteHealth -eq 'UNKNOWN') {
        Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' `
            -FailureCategory 'PROTECTED_PRECONDITION' -State $State `
            -ActiveSha $ActiveSha -StatusRoot $StatusRoot | Out-Null
        throw "Website health could not be verified: $($health.siteHealthReason)."
    }

    if ([string]$State.serviceRecoverySha -ceq $ActiveSha -and $State.serviceRecoveryAt) {
        $recoveryAt = [datetimeoffset]::Parse(
            [string]$State.serviceRecoveryAt,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind)
        $retryAt = $recoveryAt.AddSeconds([int]$Config.autoDeployFailureBackoffSeconds)
        if ($Now -lt $retryAt.UtcDateTime) {
            Publish-AutoDeployStatusBestEffort -Outcome 'BACKING_OFF' `
                -FailureCategory 'CANDIDATE_STARTUP' -State $State `
                -ActiveSha $ActiveSha -RetryAt $retryAt.ToUniversalTime().ToString('o') `
                -StatusRoot $StatusRoot | Out-Null
            return $false
        }
    }

    $recoveryFailure = $null
    try { Restart-ProductionService -Verify -RequireTargetActive }
    catch { $recoveryFailure = $_.Exception }

    if ($recoveryFailure) {
        $State.serviceRecoverySha = $ActiveSha
        $State.serviceRecoveryAt = (Get-Date).ToUniversalTime().ToString('o')
        $State.error = Get-AutoDeployFailureMessage -Exception $recoveryFailure
        $stateWriteFailure = $null
        try { Write-AutoDeployState $Config $State }
        catch { $stateWriteFailure = $_.Exception }
        Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYMENT_FAILED' `
            -FailureCategory 'CANDIDATE_STARTUP' -State $State `
            -ActiveSha $ActiveSha -StatusRoot $StatusRoot | Out-Null
        if ($stateWriteFailure) {
            throw [AggregateException]::new(
                'Website recovery failed and its retry state could not be persisted.',
                [Exception[]]@($recoveryFailure,$stateWriteFailure))
        }
        throw $recoveryFailure
    }

    $State.serviceRecoverySha = $null
    $State.serviceRecoveryAt = $null
    $State.error = $null
    try {
        Write-AutoDeployState $Config $State
    } catch {
        Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' `
            -FailureCategory 'STATUS_STORE' -State $State `
            -ActiveSha $ActiveSha -StatusRoot $StatusRoot | Out-Null
        throw
    }
    return $true
}

function Get-AutoDeployStatus {
    [CmdletBinding()]
    param(
        [string]$StatusRoot = (Get-AutoDeployStatusStoreRoot),
        [datetime]$Now = (Get-Date).ToUniversalTime()
    )

    $path = Join-Path $StatusRoot 'auto-deploy.json'
    $pollerStatus = Get-AutoDeployPollerStatus
    try {
        if (-not (Test-Path -LiteralPath $StatusRoot -PathType Container -ErrorAction Stop)) {
            return Add-AutoDeployPollerStatus `
                -Status (New-UnavailableAutoDeployStatus -Reason 'STORE_NOT_INITIALIZED') `
                -PollerStatus $pollerStatus
        }
        Assert-AutoDeployStatusDirectory -Path $StatusRoot
        if (-not (Test-Path -LiteralPath $path -PathType Leaf -ErrorAction Stop)) {
            return Add-AutoDeployPollerStatus `
                -Status (New-UnavailableAutoDeployStatus -Reason 'STATUS_NOT_PUBLISHED') `
                -PollerStatus $pollerStatus
        }
        Assert-AutoDeployStatusFile -Path $path
        $record = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $allowedOutcomes = @('CHECKING','UP_TO_DATE','BACKING_OFF','DEPLOYING','SUCCEEDED',
            'DEPLOYMENT_FAILED','CHECK_FAILED','BLOCKED','TOOLS_UPDATED')
        if ($record.schemaVersion -ne 1 -or $record.status -notin $allowedOutcomes -or
            $record.toolRefreshStatus -notin @('UNKNOWN','SUCCEEDED','FAILED') -or
            $record.failureCategory -notin @('NONE','REMOTE_CHECK','PROTECTED_PRECONDITION',
                'DEPLOYMENT','CANDIDATE_STARTUP','STATUS_STORE')) {
            throw 'Automatic deployment status record is invalid.'
        }
        $failureDetailProperty = $record.PSObject.Properties['failureDetail']
        if ($failureDetailProperty) {
            $failureDetail = $failureDetailProperty.Value
            if (($null -ne $failureDetail -and $failureDetail -isnot [string]) -or
                ([string]$failureDetail).Length -gt 240 -or
                [string]$failureDetail -match '[\r\n]') {
                throw 'Automatic deployment status failure detail is invalid.'
            }
        }
        $updatedAtValue = $record.updatedAt
        if ($updatedAtValue -is [datetime]) {
            if ($updatedAtValue.Kind -eq [DateTimeKind]::Unspecified) {
                throw 'Automatic deployment status timestamp has no time zone.'
            }
            $updatedAt = [datetimeoffset]::new($updatedAtValue)
        } else {
            $updatedAt = [datetimeoffset]::Parse(
                [string]$updatedAtValue,[Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind)
        }
        foreach ($name in @('remoteSha','activeSha','attemptedSha','successfulSha','failedSha','toolsSha','toolSourceSha')) {
            $value = [string]$record.$name
            if ($value -and $value -notmatch '^[0-9a-f]{40}$') {
                throw 'Automatic deployment status record is invalid.'
            }
        }
        $age = $Now.ToUniversalTime() - $updatedAt.UtcDateTime
        if ($age.TotalSeconds -lt 0) {
            return Add-AutoDeployPollerStatus `
                -Status (New-UnavailableAutoDeployStatus -Reason 'FUTURE_TIMESTAMP') `
                -PollerStatus $pollerStatus
        }
        $freshness = if ($age.TotalSeconds -gt 180) { 'STALE' } else { 'FRESH' }
        $websiteHealth = Get-AutoDeployWebsiteHealth
        $reportedStatus = [string]$record.status
        $reportedReason = 'NONE'
        if ($websiteHealth.siteHealth -ne 'HEALTHY') {
            $reportedStatus = if ($websiteHealth.siteHealth -eq 'UNHEALTHY') {
                'SERVICE_UNHEALTHY'
            } else {
                'SERVICE_HEALTH_UNKNOWN'
            }
            $reportedReason = [string]$websiteHealth.siteHealthReason
        }
        $message = if ($websiteHealth.siteHealth -eq 'HEALTHY') {
            Get-AutoDeployStatusMessage -Outcome ([string]$record.status)
        } else {
            switch ([string]$websiteHealth.siteHealthReason) {
                'SERVICE_STOPPED' { 'The website service is stopped.' }
                'SERVICE_NOT_RUNNING' { 'The website service is not running.' }
                'READINESS_FAILED' { 'The website service is running but readiness failed.' }
                'ACCESS_DENIED' { 'The website service state cannot be queried with this account.' }
                default { 'Website health could not be verified.' }
            }
        }
        $status = [pscustomobject]@{
            available = $true
            freshness = $freshness
            status = $reportedStatus
            deploymentStatus = [string]$record.status
            reason = $reportedReason
            serviceState = [string]$websiteHealth.serviceState
            siteHealth = [string]$websiteHealth.siteHealth
            siteHealthReason = [string]$websiteHealth.siteHealthReason
            updatedAt = $updatedAt.ToUniversalTime().ToString('o')
            remoteSha = $record.remoteSha
            activeSha = $record.activeSha
            attemptedSha = $record.attemptedSha
            successfulSha = $record.successfulSha
            failedSha = $record.failedSha
            failedAt = $record.failedAt
            retryAt = $record.retryAt
            toolsSha = $record.toolsSha
            toolSourceSha = $record.toolSourceSha
            toolRefreshStatus = $record.toolRefreshStatus
            toolRefreshAt = $record.toolRefreshAt
            failureCategory = [string]$record.failureCategory
            failureDetail = if ($record.PSObject.Properties['failureDetail']) {
                [string]$record.failureDetail
            } else { $null }
            message = $message
        }
        return Add-AutoDeployPollerStatus -Status $status -PollerStatus $pollerStatus
    } catch {
        $reason = if ($_.Exception -is [UnauthorizedAccessException]) { 'ACCESS_DENIED' } else { 'INVALID' }
        return Add-AutoDeployPollerStatus `
            -Status (New-UnavailableAutoDeployStatus -Reason $reason) `
            -PollerStatus $pollerStatus
    }
}

function Invoke-AutoDeployOnce {
    param(
        $Config = (Read-ProductionConfig),
        [string]$StatusRoot
    )
    Assert-ProductionFixedRootBoundary `
        -Config $Config `
        -FixedRoot $script:FixedProductionRoot | Out-Null
    $state = Read-AutoDeployState $Config
    try {
        $direction = Read-ProductionMusicSchemaDirection -Config $Config
    } catch {
        Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' -FailureCategory 'PROTECTED_PRECONDITION' `
            -State $state -StatusRoot $StatusRoot | Out-Null
        throw
    }
    if (-not $direction) {
        Publish-AutoDeployStatusBestEffort -Outcome 'BLOCKED' -FailureCategory 'PROTECTED_PRECONDITION' `
            -State $state -StatusRoot $StatusRoot | Out-Null
        throw ('Automatic deployment is blocked because the Music schema-direction marker is absent. ' +
            'Run the protected first-cutover deploy interactively.')
    }
    if ([string]$direction.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
        Publish-AutoDeployStatusBestEffort -Outcome 'BLOCKED' -FailureCategory 'PROTECTED_PRECONDITION' `
            -State $state -StatusRoot $StatusRoot | Out-Null
        throw ('Automatic deployment is blocked while legacy Music runtime state requires reconciliation. ' +
            'Run the protected deploy command interactively to reconcile before a target writer starts.')
    }
    if ([string]$direction.state -ne 'TARGET_ACTIVE') {
        Publish-AutoDeployStatusBestEffort -Outcome 'BLOCKED' -FailureCategory 'PROTECTED_PRECONDITION' `
            -State $state -StatusRoot $StatusRoot | Out-Null
        throw ('Automatic deployment is blocked because the first Music schema cutover is incomplete. ' +
            'Complete bounded recovery interactively.')
    }
    $now = (Get-Date).ToUniversalTime()
    $state.lastCheckedAt = $now.ToString('o')
    try {
        $active = Get-ActiveReleaseSha $Config
    } catch {
        Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' `
            -FailureCategory 'PROTECTED_PRECONDITION' -State $state `
            -StatusRoot $StatusRoot | Out-Null
        throw
    }
    $recoveryFailure = $null
    $recoveryBackoff = $false
    if ($active) {
        try {
            $continueDeployment = Invoke-AutoDeployWebsiteRecovery `
                -Config $Config -State $state -ActiveSha $active -Now $now `
                -StatusRoot $StatusRoot
            $recoveryBackoff = -not $continueDeployment
        } catch {
            $recoveryFailure = $_
        }
    }
    try {
        $remote = Get-RemoteMainSha $Config
    } catch {
        Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' -FailureCategory 'REMOTE_CHECK' `
            -State $state -StatusRoot $StatusRoot | Out-Null
        throw
    }
    $state.remoteSha = $remote
    if ($remote -eq $active) {
        if ($recoveryFailure) {
            Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYMENT_FAILED' `
                -FailureCategory 'CANDIDATE_STARTUP' -State $state `
                -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
            throw $recoveryFailure
        }
        if ($recoveryBackoff) { return }
        $state.successfulSha = $remote
        $state.error = $null
        $state.failedSha = $null
        $state.failedAt = $null
        Write-AutoDeployState $Config $state
        Publish-AutoDeployStatusBestEffort -Outcome 'UP_TO_DATE' -State $state `
            -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
        return
    }
    if ($state.failedSha -eq $remote -and $state.failedAt) {
        $failedAtValue = $state.failedAt
        if ($failedAtValue -is [datetimeoffset]) {
            $failedAt = $failedAtValue
        } elseif ($failedAtValue -is [datetime]) {
            if ($failedAtValue.Kind -eq [DateTimeKind]::Unspecified) {
                throw 'Automatic deployment failure timestamp has no time zone.'
            }
            $failedAt = [datetimeoffset]::new($failedAtValue)
        } else {
            $failedAt = [datetimeoffset]::Parse(
                [string]$failedAtValue,[Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind)
        }
        $retryAt = $failedAt.AddSeconds([int]$Config.autoDeployFailureBackoffSeconds)
        if ($now -lt $retryAt.UtcDateTime) {
            Write-AutoDeployState $Config $state
            Publish-AutoDeployStatusBestEffort -Outcome 'BACKING_OFF' -State $state `
                -ActiveSha $active -RetryAt $retryAt.ToUniversalTime().ToString('o') `
                -FailureCategory 'DEPLOYMENT' -StatusRoot $StatusRoot | Out-Null
            return
        }
    }
    $state.attemptedSha = $remote
    Write-AutoDeployState $Config $state
    Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYING' -State $state `
        -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
    $deploymentFailure = $null
    try {
        Invoke-ProductionDeploy -Automatic
        $active = Get-ActiveReleaseSha $Config
        if (-not $active) { throw 'Deployment completed without valid active release metadata.' }
        $state.successfulSha = $active
        $state.failedSha = $null
        $state.failedAt = $null
        $state.serviceRecoverySha = $null
        $state.serviceRecoveryAt = $null
        $state.error = $null
    } catch {
        $state.failedSha = $remote
        $state.failedAt = (Get-Date).ToUniversalTime().ToString('o')
        $state.error = Get-AutoDeployFailureMessage -Exception $_.Exception
        Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYMENT_FAILED' `
            -FailureCategory 'DEPLOYMENT' -State $state -ActiveSha $active `
            -StatusRoot $StatusRoot | Out-Null
        $deploymentFailure = $_
    }
    $stateWriteFailure = $null
    try { Write-AutoDeployState $Config $state }
    catch { $stateWriteFailure = $_ }
    if ($deploymentFailure -and $stateWriteFailure) {
        throw [AggregateException]::new(
            'Automatic deployment failed and its state could not be persisted.',
            [Exception[]]@($deploymentFailure.Exception,$stateWriteFailure.Exception))
    }
    if ($deploymentFailure) { throw $deploymentFailure }
    if ($stateWriteFailure) { throw $stateWriteFailure }
    Publish-AutoDeployStatusBestEffort -Outcome 'SUCCEEDED' -State $state `
        -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
}

function Get-AutoDeployToolManifestEntries {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Root)

    $rootPath = [IO.Path]::GetFullPath($Root)
    $manifestName = 'auto-deploy-tools.json'
    return @(
        Get-ChildItem -LiteralPath $rootPath -File -Recurse -Force -ErrorAction Stop |
            Where-Object { $_.Name -cne $manifestName } |
            ForEach-Object {
                $relativePath = $_.FullName.Substring($rootPath.Length).TrimStart('\','/')
                [pscustomobject]@{
                    path = $relativePath.Replace('\','/')
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
                }
            } | Sort-Object -Property path
    )
}

function Assert-AutoDeployToolVersion {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$TreeSha
    )

    Assert-ProductionTreeNotReparse -Path $Root
    Assert-ProtectedProductionTree -Path $Root
    $metadataPath = Join-Path $Root 'auto-deploy-tools.json'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -ErrorAction Stop |
        ConvertFrom-Json -ErrorAction Stop
    if ($metadata.schemaVersion -ne 1 -or $metadata.treeSha -cne $TreeSha -or
        $metadata.sourceCommitSha -notmatch '^[0-9a-f]{40}$' -or
        -not (Test-Path -LiteralPath (Join-Path $Root 'prod.ps1') -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $Root 'modules\Production.AutoDeploy.psm1') -PathType Leaf)) {
        throw 'Existing automatic deployment tool version is invalid.'
    }
    $expectedFiles = @($metadata.files)
    $actualFiles = @(Get-AutoDeployToolManifestEntries -Root $Root)
    if ($expectedFiles.Count -eq 0 -or $expectedFiles.Count -ne $actualFiles.Count) {
        throw 'Existing automatic deployment tool version has an incomplete file manifest.'
    }
    for ($index = 0; $index -lt $expectedFiles.Count; $index++) {
        $relativePath = ([string]$expectedFiles[$index].path).Replace('/','\')
        if ([IO.Path]::IsPathRooted($relativePath) -or $relativePath -match '(^|\\)\.\.(\\|$)') {
            throw 'Existing automatic deployment tool manifest contains an unsafe path.'
        }
        $filePath = [IO.Path]::GetFullPath((Join-Path $Root $relativePath))
        $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
        if (-not $filePath.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
            $relativePath.Replace('\','/') -cne [string]$actualFiles[$index].path -or
            [string]$expectedFiles[$index].sha256 -cne [string]$actualFiles[$index].sha256) {
            throw 'Existing automatic deployment tool version failed its integrity check.'
        }
    }
}

function Update-AutoDeployToolsFromOriginMain {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Config)

    Assert-ProductionFixedRootBoundary `
        -Config $Config -FixedRoot $script:FixedProductionRoot | Out-Null
    $toolsRoot = [IO.Path]::GetFullPath((Join-Path $Config.programDataRoot 'tools'))
    $versionsRoot = Join-Path $toolsRoot 'versions'
    $sourcePath = Join-Path $Config.programDataRoot 'tools\worktrees'
    $task = Get-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction Stop
    if (-not $task) { throw 'ChristopherBellAutoDeploy is not registered.' }

    $currentToolsRoot = Split-Path -Parent $PSScriptRoot
    $currentSha = Split-Path -Leaf $currentToolsRoot
    $versionPattern = '^[0-9a-f]{40}$'
    $remoteSha = Get-RemoteMainSha -Config $Config
    $state = Read-AutoDeployState $Config
    if ($currentSha -match $versionPattern -and
        $state.toolsSha -eq $currentSha -and
        $state.toolSourceSha -eq $remoteSha) {
        return [pscustomobject]@{ Sha=$currentSha; SourceSha=$remoteSha; Switched=$false }
    }

    $sha = Resolve-OriginMainRelease -Config $Config
    $treeArguments = Get-TrustedGitArguments $Config.repositoryPath @(
        'rev-parse',"$($sha):ops/production/windows")
    $treeSha = (Invoke-CheckedProcess 'git.exe' $treeArguments $Config.repositoryPath).Trim().ToLowerInvariant()
    if ($treeSha -notmatch '^[0-9a-f]{40}$') {
        throw 'Trusted origin/main did not resolve to a complete deployment tools tree.'
    }
    $versionRoot = Join-Path $versionsRoot $treeSha
    $worktree = Join-Path $sourcePath ('{0}-{1}' -f $sha,[guid]::NewGuid().ToString('N'))
    $stage = Join-Path $versionsRoot ('.staging-{0}-{1}' -f $treeSha,[guid]::NewGuid().ToString('N'))
    $alreadyCurrent = $currentSha -match $versionPattern -and $currentSha -eq $treeSha

    Assert-ProductionPathNotReparse -Path $Config.programDataRoot | Out-Null
    Protect-ProductionPath -Path $Config.programDataRoot
    New-Item -ItemType Directory -Path $toolsRoot,$versionsRoot,$sourcePath -Force | Out-Null
    foreach ($path in $toolsRoot,$versionsRoot,$sourcePath) {
        Protect-ProductionPath -Path $path
        Assert-ProductionPathNotReparse -Path $path | Out-Null
    }
    if ($alreadyCurrent) {
        return [pscustomobject]@{ Sha=$treeSha; SourceSha=$sha; Switched=$false }
    }

    if (Test-Path -LiteralPath $versionRoot) {
        Assert-AutoDeployToolVersion -Root $versionRoot -TreeSha $treeSha
    } else {
        New-Item -ItemType Directory -Path $sourcePath,$versionsRoot -Force | Out-Null
        $addArguments = Get-TrustedGitArguments $Config.repositoryPath @(
            'worktree','add','--detach',$worktree,$sha)
        $operationFailure = $null
        $cleanupFailures = [Collections.Generic.List[Exception]]::new()
        try {
            Invoke-CheckedProcess 'git.exe' $addArguments $Config.repositoryPath | Out-Null
            $verifyArguments = Get-TrustedGitArguments $worktree @('rev-parse','HEAD')
            $checkedOutSha = (Invoke-CheckedProcess 'git.exe' $verifyArguments $worktree).Trim()
            if ($checkedOutSha -cne $sha) {
                throw 'Automatic deployment tool worktree did not match trusted origin/main.'
            }
            $source = Join-Path $worktree 'ops\production\windows'
            Assert-ProductionTreeNotReparse -Path $source
            if (-not (Test-Path -LiteralPath (Join-Path $source 'prod.ps1') -PathType Leaf) -or
                -not (Test-Path -LiteralPath (Join-Path $source 'modules\Production.AutoDeploy.psm1') -PathType Leaf)) {
                throw 'Trusted origin/main does not contain the complete automatic deployment tools.'
            }
            New-Item -ItemType Directory -Path $stage -ErrorAction Stop | Out-Null
            Get-ChildItem -LiteralPath $source -Force -ErrorAction Stop | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $stage -Recurse -Force -ErrorAction Stop
            }
            $manifestFiles = @(Get-AutoDeployToolManifestEntries -Root $stage)
            [ordered]@{
                schemaVersion = 1
                sourceCommitSha = $sha
                treeSha = $treeSha
                installedAt = (Get-Date).ToUniversalTime().ToString('o')
                files = $manifestFiles
            } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'auto-deploy-tools.json') `
                -Encoding utf8 -ErrorAction Stop
            Protect-ProductionTree -Path $stage
            Assert-AutoDeployToolVersion -Root $stage -TreeSha $treeSha
        } catch {
            $operationFailure = $_.Exception
        } finally {
            if (Test-Path -LiteralPath $worktree) {
                try {
                    $removeArguments = Get-TrustedGitArguments $Config.repositoryPath @(
                        'worktree','remove','--force',$worktree)
                    Invoke-CheckedProcess 'git.exe' $removeArguments $Config.repositoryPath | Out-Null
                } catch {
                    [void]$cleanupFailures.Add($_.Exception)
                }
            }
            if ((Test-Path -LiteralPath $stage -PathType Container) -and $operationFailure) {
                try {
                    Assert-ProductionTreeNotReparse -Path $stage
                    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction Stop
                } catch {
                    [void]$cleanupFailures.Add($_.Exception)
                }
            }
        }
        if ($operationFailure -and $cleanupFailures.Count -gt 0) {
            $failures = [Collections.Generic.List[Exception]]::new()
            [void]$failures.Add($operationFailure)
            foreach ($failure in $cleanupFailures) { [void]$failures.Add($failure) }
            throw [AggregateException]::new(
                'Automatic deployment tools could not be staged or cleaned up.',
                [Exception[]]$failures.ToArray())
        }
        if ($operationFailure) { throw $operationFailure }
        if ($cleanupFailures.Count -gt 0) {
            throw [AggregateException]::new(
                'Automatic deployment tool worktree cleanup failed.',
                [Exception[]]$cleanupFailures.ToArray())
        }
        try {
            [IO.Directory]::Move($stage,$versionRoot)
        } catch {
            $publicationFailure = $_.Exception
            try {
                Assert-ProductionTreeNotReparse -Path $stage
                Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction Stop
            } catch {
                throw [AggregateException]::new(
                    'Automatic deployment tool publication and stage cleanup failed.',
                    [Exception[]]@($publicationFailure,$_.Exception))
            }
            throw $publicationFailure
        }
    }

    $taskScript = Join-Path $versionRoot 'prod.ps1'
    $arguments = '-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden ' +
        "-ExecutionPolicy Bypass -File `"$taskScript`" auto-deploy"
    $action = New-ScheduledTaskAction `
        -Execute (Resolve-PowerShell7Executable) -Argument $arguments
    Set-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -Action $action -ErrorAction Stop |
        Out-Null
    return [pscustomobject]@{ Sha=$treeSha; SourceSha=$sha; Switched=$true }
}

function Start-AutoDeployLoop {
    $script:autoDeployStatusWarningEmitted = $false
    $statusRoot = $null
    $state = New-AutoDeployState
    try {
        $statusRoot = Initialize-AutoDeployStatusStore
    } catch {
        # Status collection is best-effort and must never prevent deployment recovery.
    }
    Publish-AutoDeployStatusBestEffort -Outcome 'CHECKING' -State $state `
        -StatusRoot $statusRoot | Out-Null
    $invokeStarted = $false
    $boundaryValidated = $false
    try {
        $config = Read-ProductionConfig (
            Join-Path $script:FixedProductionRoot 'config\deploy.json')
        Assert-ProductionFixedRootBoundary `
            -Config $config `
            -FixedRoot $script:FixedProductionRoot | Out-Null
        $boundaryValidated = $true
        try {
            $refreshGuard = Enter-ProductionFixedRootDeploymentLock `
                -Config $config -FixedRoot $script:FixedProductionRoot
            try {
                $refresh = Update-AutoDeployToolsFromOriginMain -Config $config
            } finally {
                $refreshGuard.Lock.Dispose()
            }
            $state.toolsSha = $refresh.Sha
            $state.toolSourceSha = $refresh.SourceSha
            $state.toolRefreshStatus = 'SUCCEEDED'
            $state.toolRefreshAt = (Get-Date).ToUniversalTime().ToString('o')
            $storedState = Read-AutoDeployState $config
            $storedState.toolsSha = $state.toolsSha
            $storedState.toolSourceSha = $state.toolSourceSha
            $storedState.toolRefreshStatus = $state.toolRefreshStatus
            $storedState.toolRefreshAt = $state.toolRefreshAt
            Write-AutoDeployState $config $storedState
            $state = $storedState
            if ($refresh.Switched) {
                Publish-AutoDeployStatusBestEffort -Outcome 'TOOLS_UPDATED' -State $state `
                    -StatusRoot $statusRoot | Out-Null
                return
            }
        } catch {
            $toolRefreshFailure = $_.Exception
            $state.toolRefreshStatus = 'FAILED'
            $state.toolRefreshAt = (Get-Date).ToUniversalTime().ToString('o')
            try {
                $storedState = Read-AutoDeployState $config
                $storedState.toolRefreshStatus = $state.toolRefreshStatus
                $storedState.toolRefreshAt = $state.toolRefreshAt
                Write-AutoDeployState $config $storedState
            } catch {
                $statePersistenceFailure = $_.Exception
                throw [AggregateException]::new(
                    'Automatic deployment tool refresh failure could not be persisted.',
                    [Exception[]]@($toolRefreshFailure,$statePersistenceFailure))
            }
        }
        $state = Read-AutoDeployState $config
        $invokeStarted = $true
        Invoke-AutoDeployOnce -Config $config -StatusRoot $statusRoot
    }
    catch {
        if (-not $boundaryValidated) {
            Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' `
                -FailureCategory 'PROTECTED_PRECONDITION' -State $state `
                -StatusRoot $statusRoot | Out-Null
            throw
        }
        $failureRecord = $_
        if (-not $invokeStarted) {
            Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' `
                -FailureCategory 'PROTECTED_PRECONDITION' -State $state `
                -StatusRoot $statusRoot | Out-Null
        } else {
            if ($statusRoot) { $previousStatus = Get-AutoDeployStatus -StatusRoot $statusRoot }
            else { $previousStatus = Get-AutoDeployStatus }
            if ($previousStatus.status -in @('CHECKING','DEPLOYING','UNKNOWN')) {
                try { $state = Read-AutoDeployState $config } catch { }
                Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' `
                    -FailureCategory 'PROTECTED_PRECONDITION' -State $state `
                    -StatusRoot $statusRoot | Out-Null
            }
        }
        $log = Join-Path $script:FixedProductionRoot 'logs\auto-deploy-errors.log'
        try {
            "$(Get-Date -Format o) $($failureRecord.Exception.Message)" |
                Add-Content -LiteralPath $log -ErrorAction Stop
        } catch { }
        throw $failureRecord
    }
}

function Resolve-PowerShell7Executable {
    $executable = Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "PowerShell 7 is required at $executable."
    }
    return $executable
}

function Get-ProductionAutoDeployTask {
    $tasks = @(Get-ScheduledTask -ErrorAction Stop | Where-Object {
        $_.TaskName -eq 'ChristopherBellAutoDeploy' -and $_.TaskPath -eq '\'
    })
    if ($tasks.Count -gt 1) {
        throw 'Multiple root automatic deployment tasks were found.'
    }
    if ($tasks.Count -eq 0) { return $null }
    return $tasks[0]
}

function Stop-ProductionAutoDeployTask {
    Stop-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $task = Get-ProductionAutoDeployTask
        if (-not $task -or [string]$task.State -ne 'Running') { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'ChristopherBellAutoDeploy did not stop before tool replacement.'
}

function Update-ProductionAutoDeployToolsUnderHeldLock {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Config)

    $tools = Join-Path $Config.programDataRoot 'tools'
    $parent = Split-Path -Parent $tools
    $stage = Join-Path $parent ('.tools-stage-{0}' -f [guid]::NewGuid().ToString('N'))
    $backup = Join-Path $parent ('.tools-backup-{0}' -f [guid]::NewGuid().ToString('N'))
    $taskName = 'ChristopherBellAutoDeploy'
    Assert-ProductionFixedRootBoundary `
        -Config $Config -FixedRoot $script:FixedProductionRoot | Out-Null
    Assert-ProductionPathNotReparse -Path $Config.programDataRoot | Out-Null
    Protect-ProductionPath -Path $Config.programDataRoot
    Initialize-AutoDeployStatusStore | Out-Null
    if (Test-Path -LiteralPath $tools) {
        Assert-ProductionTreeNotReparse -Path $tools
    }

    $existingTask = Get-ProductionAutoDeployTask
    $previousTaskEnabled = $existingTask -and [string]$existingTask.State -ne 'Disabled'
    $previousTaskXml = if ($existingTask) {
        Export-ScheduledTask -TaskName $taskName -ErrorAction Stop
    } else {
        $null
    }
    $oldToolsMoved = $false
    $newToolsPublished = $false
    $taskSwitchStarted = $false
    $filesystemSwitchStarted = $false
    $taskRegistrationAttempted = $false
    try {
        New-Item -ItemType Directory -Path $stage -ErrorAction Stop | Out-Null
        Protect-ProductionPath -Path $stage
        Copy-Item (Join-Path $PSScriptRoot '..\*') $stage -Recurse -Force -ErrorAction Stop
        Protect-ProductionTree -Path $stage
        Assert-ProtectedProductionTree -Path $stage
        Assert-ProductionTreeNotReparse -Path $stage

        if ($existingTask) {
            $taskSwitchStarted = $true
            Disable-ScheduledTask -TaskName $taskName -ErrorAction Stop | Out-Null
            Stop-ProductionAutoDeployTask
        }

        $filesystemSwitchStarted = $true
        if (Test-Path -LiteralPath $tools) {
            Move-ProductionAutoDeployToolsDirectory -SourcePath $tools -DestinationPath $backup
            $oldToolsMoved = $true
        }
        Move-ProductionAutoDeployToolsDirectory -SourcePath $stage -DestinationPath $tools
        $newToolsPublished = $true
        Assert-ProductionTreeNotReparse -Path $tools
        Assert-ProtectedProductionTree -Path $tools

        $actionArguments = '-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden ' +
            "-ExecutionPolicy Bypass -File `"$tools\prod.ps1`" auto-deploy"
        $action = New-ScheduledTaskAction `
            -Execute (Resolve-PowerShell7Executable) -Argument $actionArguments
        $startupTrigger = New-ScheduledTaskTrigger -AtStartup
        $repeatingTrigger = New-ScheduledTaskTrigger `
            -Once -At (Get-Date).AddMinutes(1) `
            -RepetitionInterval (New-TimeSpan -Seconds ([int]$Config.autoDeployPollSeconds))
        $settings = New-ScheduledTaskSettingsSet `
            -ExecutionTimeLimit (New-TimeSpan -Hours 2) `
            -RestartCount 3 `
            -RestartInterval (New-TimeSpan -Minutes 1) `
            -MultipleInstances IgnoreNew `
            -Hidden `
            -StartWhenAvailable `
            -AllowStartIfOnBatteries `
            -DontStopIfGoingOnBatteries
        $principal = New-ScheduledTaskPrincipal `
            -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
        $taskRegistrationAttempted = $true
        Register-ScheduledTask `
            -TaskName $taskName `
            -Action $action `
            -Trigger @($startupTrigger, $repeatingTrigger) `
            -Settings $settings `
            -Principal $principal `
            -Force -ErrorAction Stop | Out-Null
        Enable-ScheduledTask -TaskName $taskName -ErrorAction Stop | Out-Null
    } catch {
        $failureRecord = $_
        $recoveryFailure = $null
        try {
            if ($filesystemSwitchStarted -and
                ($taskSwitchStarted -or $taskRegistrationAttempted)) {
                $taskToStop = Get-ProductionAutoDeployTask
                if ($taskToStop) {
                    Disable-ScheduledTask -TaskName $taskName -ErrorAction Stop | Out-Null
                    Stop-ProductionAutoDeployTask
                }
            }
            if ($newToolsPublished -and (Test-Path -LiteralPath $tools)) {
                Move-ProductionAutoDeployToolsDirectory -SourcePath $tools -DestinationPath $stage
                $newToolsPublished = $false
            }
            if ($oldToolsMoved -and (Test-Path -LiteralPath $backup)) {
                Move-ProductionAutoDeployToolsDirectory -SourcePath $backup -DestinationPath $tools
                $oldToolsMoved = $false
            }
            if ($taskRegistrationAttempted -and $previousTaskXml) {
                Register-ScheduledTask -TaskName $taskName -Xml $previousTaskXml `
                    -Force -ErrorAction Stop | Out-Null
            } elseif ($taskRegistrationAttempted -and -not $previousTaskXml) {
                $partiallyRegisteredTask = Get-ProductionAutoDeployTask
                if ($partiallyRegisteredTask) {
                    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false `
                        -ErrorAction Stop | Out-Null
                }
            }
            if ($taskSwitchStarted -and -not $taskRegistrationAttempted -and $existingTask) {
                if ($previousTaskEnabled) {
                    Enable-ScheduledTask -TaskName $taskName -ErrorAction Stop | Out-Null
                } else {
                    Disable-ScheduledTask -TaskName $taskName -ErrorAction Stop | Out-Null
                }
            }
        } catch {
            $recoveryFailure = $_
        }
        if ($recoveryFailure) {
            throw [AggregateException]::new(
                'Automatic deployment tool replacement failed and recovery did not complete.',
                [Exception[]]@($failureRecord.Exception,$recoveryFailure.Exception))
        }
        throw $failureRecord
    } finally {
        if (Test-Path -LiteralPath $stage -PathType Container) {
            try { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction Stop }
            catch { Write-Warning "Automatic deployment staging directory was retained at $stage." }
        }
    }

    if ($oldToolsMoved -and (Test-Path -LiteralPath $backup -PathType Container)) {
        try { Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction Stop }
        catch { Write-Warning "Previous automatic deployment tools were retained at $backup." }
    }
}

function Move-ProductionAutoDeployToolsDirectory {
    param(
        [Parameter(Mandatory)][string]$SourcePath,
        [Parameter(Mandatory)][string]$DestinationPath
    )

    [IO.Directory]::Move($SourcePath,$DestinationPath)
}

function Install-AutoDeployTask {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    $config = Read-ProductionConfig (
        Join-Path $script:FixedProductionRoot 'config\deploy.json')
    if ($WhatIf) {
        Write-Output 'Would register and start the ChristopherBellAutoDeploy startup task.'
        return
    }
    $guard = Enter-ProductionFixedRootDeploymentLock `
        -Config $config `
        -FixedRoot $script:FixedProductionRoot `
        -EnterLockAction {
            param($LockPath)
            Enter-DeploymentLock -LockPath $LockPath -WaitTimeoutSeconds 120
        }
    try {
        Update-ProductionAutoDeployToolsUnderHeldLock -Config $config
    } finally {
        $guard.Lock.Dispose()
    }
    Start-ScheduledTask -TaskName 'ChristopherBellAutoDeploy'
}

function Remove-AutoDeployTaskUnderHeldLock {
    $taskName = 'ChristopherBellAutoDeploy'
    $task = Get-ProductionAutoDeployTask
    if (-not $task) {
        Write-Output 'The ChristopherBellAutoDeploy task is not installed.'
        return
    }

    $wasEnabled = [string]$task.State -ne 'Disabled'
    $wasRunning = [string]$task.State -eq 'Running'
    $taskStateChangeAttempted = $false
    try {
        if ($wasEnabled) {
            $taskStateChangeAttempted = $true
            Disable-ScheduledTask -TaskName $taskName -ErrorAction Stop | Out-Null
            $task = Get-ProductionAutoDeployTask
            if (-not $task) {
                Write-Output 'Removed the ChristopherBellAutoDeploy task.'
                return
            }
            if ([string]$task.State -ne 'Disabled') {
                throw 'Automatic deployment task removal failed: task could not be disabled.'
            }
        }
        if ($wasRunning) {
            Stop-ProductionAutoDeployTask
        }

        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false `
            -ErrorAction Stop | Out-Null
        if (Get-ProductionAutoDeployTask) {
            throw 'Automatic deployment task removal failed: task remains registered.'
        }
        Write-Output 'Removed the ChristopherBellAutoDeploy task.'
    }
    catch {
        $primaryError = $_
        $recoveryErrors = [Collections.Generic.List[Exception]]::new()

        if ($taskStateChangeAttempted) {
            try {
                $remainingTask = Get-ProductionAutoDeployTask
                if ($remainingTask -and $wasEnabled -and
                    [string]$remainingTask.State -eq 'Disabled') {
                    Enable-ScheduledTask -TaskName $taskName `
                        -ErrorAction Stop | Out-Null
                    $restoredTask = Get-ProductionAutoDeployTask
                    if ($restoredTask -and
                        [string]$restoredTask.State -eq 'Disabled') {
                        throw 'Automatic deployment task removal failed: prior enabled state was not restored.'
                    }
                }
            }
            catch {
                $recoveryErrors.Add($_.Exception)
            }
        }

        if ($recoveryErrors.Count -gt 0) {
            $allErrors = [Collections.Generic.List[Exception]]::new()
            $allErrors.Add($primaryError.Exception)
            foreach ($recoveryError in $recoveryErrors) {
                $allErrors.Add($recoveryError)
            }
            throw [AggregateException]::new(
                'Automatic deployment task removal failed and its prior state could not be restored.',
                $allErrors.ToArray())
        }

        $PSCmdlet.ThrowTerminatingError($primaryError)
    }
}

function Remove-AutoDeployTask {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    if ($WhatIf) { Write-Output 'Would remove the ChristopherBellAutoDeploy task.'; return }

    $config = Read-ProductionConfig (
        Join-Path $script:FixedProductionRoot 'config\deploy.json')
    $guard = Enter-ProductionFixedRootDeploymentLock `
        -Config $config `
        -FixedRoot $script:FixedProductionRoot `
        -EnterLockAction {
            param($LockPath)
            Enter-DeploymentLock -LockPath $LockPath
        }
    try {
        Remove-AutoDeployTaskUnderHeldLock
    } finally {
        $guard.Lock.Dispose()
    }
}

Export-ModuleMember -Function New-AutoDeployState,Read-AutoDeployState,`
    Write-AutoDeployState,Get-RemoteMainSha,Get-ActiveReleaseSha,`
    Invoke-AutoDeployOnce,Start-AutoDeployLoop,Install-AutoDeployTask,`
    Update-ProductionAutoDeployToolsUnderHeldLock,Remove-AutoDeployTask,`
    Get-AutoDeployStatus
