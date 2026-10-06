Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:FixedProductionRoot = 'C:\ProgramData\christopherbell.dev'

$script:autoDeployStatusWarningEmitted = $false
$script:autoDeployGitHubWarningEmitted = $false
$script:autoDeployGitHubTokenExpiresAt = $null
$script:GitHubApiRoot = 'https://api.github.com'
$script:GitHubDeploymentEnvironment = 'Production'
$script:GitHubTokenFileName = 'github-deployments.token'
$script:CiWorkflowFile = 'ci.yml'
$script:GitHubDescriptionMaximumLength = 140

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
        ciSha=$null
        ciConclusion=$null
        ciCheckedAt=$null
        heldRemoteSha=$null
        opsOnlyAcknowledgedSha=$null
        githubTokenExpiresAt=$null
        processedOpsRequests=@()
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
            ciSha = $null
            ciConclusion = $null
            ciCheckedAt = $null
            heldRemoteSha = $null
            opsOnlyAcknowledgedSha = $null
            githubTokenExpiresAt = $null
            processedOpsRequests = @()
        }.GetEnumerator()) {
            if (-not $state.PSObject.Properties[$property.Key]) {
                $state | Add-Member -NotePropertyName $property.Key -NotePropertyValue $property.Value
            }
        }
        # A one-element JSON array reads back as a single object.
        $state.processedOpsRequests = @($state.processedOpsRequests | Where-Object { $null -ne $_ })
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

function Write-AutoDeployGitHubWarning {
    <# Warns once per poll; GitHub trouble degrades reporting but must never flood the task log. #>
    param([Parameter(Mandatory)][string]$Reason)
    if ($script:autoDeployGitHubWarningEmitted) { return }
    $script:autoDeployGitHubWarningEmitted = $true
    Write-Warning "GitHub deployment integration is degraded ($Reason); automatic deployment continues."
}

function Get-AutoDeployGitHubRepository {
    <# Returns the owner/name slug of the configured HTTPS GitHub remote. #>
    param([Parameter(Mandatory)]$Config)
    $arguments = Get-TrustedGitArguments $Config.repositoryPath @('remote','get-url',$Config.remote)
    $remoteUrl = ([string](Invoke-CheckedProcess 'git.exe' $arguments $Config.repositoryPath)).Trim()
    if ($remoteUrl -notmatch '^https://github\.com/(?<owner>[A-Za-z0-9-]+)/(?<name>[A-Za-z0-9._-]+?)(?:\.git)?/?$') {
        throw 'Automatic deployment remote is not an HTTPS GitHub repository URL.'
    }
    return "$($Matches.owner)/$($Matches.name)"
}

function Test-AutoDeployGitHubTokenShape {
    param([AllowNull()][string]$Token)
    return [bool]($Token -cmatch '^(?:github_pat_[A-Za-z0-9_]{20,255}|gh[opsu]_[A-Za-z0-9]{36,255})$')
}

function Get-AutoDeployGitHubTokenPath {
    param([Parameter(Mandatory)]$Config)
    return Join-Path $Config.programDataRoot "config\$script:GitHubTokenFileName"
}

function Read-AutoDeployGitHubToken {
    <# Returns the protected deployment token, or $null when GitHub reporting is not configured. #>
    param([Parameter(Mandatory)]$Config)
    $tokenPath = Get-AutoDeployGitHubTokenPath $Config
    if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) { return $null }
    Assert-ProtectedProductionPath -Path $tokenPath
    $token = Read-AutoDeployTokenFileText -Path $tokenPath
    if (-not (Test-AutoDeployGitHubTokenShape $token)) {
        throw 'The GitHub deployment token file does not contain a GitHub token.'
    }
    return $token
}

function Read-AutoDeployTokenFileText {
    <# Returns the trimmed file text; an empty file yields '' because Get-Content -Raw emits nothing for it. #>
    param([Parameter(Mandatory)][string]$Path)
    $fileText = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
    if ($null -eq $fileText) { return '' }
    return ([string]$fileText).Trim()
}

function Test-AutoDeployAdministrator {
    $principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-AutoDeployAdministrator {
    param([Parameter(Mandatory)][string]$Operation)
    if (-not (Test-AutoDeployAdministrator)) {
        throw "$Operation requires elevated PowerShell. Open PowerShell 7 with Run as administrator and retry."
    }
}

function Invoke-AutoDeployGitHubApi {
    <#
    Calls the GitHub REST API and returns the decoded response. A non-2xx response throws an
    exception whose Data['StatusCode'] carries the status; messages never include the token.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('Get','Post')][string]$Method = 'Get',
        [hashtable]$Body,
        [string]$Token
    )
    $headers = @{
        Accept = 'application/vnd.github+json'
        'X-GitHub-Api-Version' = '2022-11-28'
        'User-Agent' = 'christopherbell.dev-auto-deploy'
    }
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $request = @{
        Uri = "$script:GitHubApiRoot/$Path"
        Method = $Method
        Headers = $headers
        TimeoutSec = 15
    }
    if ($Body) {
        $request.ContentType = 'application/json'
        $request.Body = $Body | ConvertTo-Json -Depth 5 -Compress
    }
    $response = Invoke-ProductionWebRequest @request
    $statusCode = [int]$response.StatusCode
    if ($Token) { Save-AutoDeployGitHubTokenExpiration -Response $response }
    if ($statusCode -lt 200 -or $statusCode -ge 300) {
        $resource = ($Path -split '\?')[0]
        $failure = [InvalidOperationException]::new("GitHub API $Method $resource returned HTTP $statusCode.")
        $failure.Data['StatusCode'] = $statusCode
        throw $failure
    }
    if ([string]::IsNullOrWhiteSpace([string]$response.Content)) { return $null }
    return [string]$response.Content | ConvertFrom-Json -ErrorAction Stop
}

function ConvertFrom-AutoDeployTokenExpirationHeader {
    <#
    Parses GitHub's github-authentication-token-expiration header, such as
    "2026-11-04 15:00:00 UTC" or "2026-11-04 10:00:00 -0500", into a round-trip UTC string.
    Returns $null for an absent or unrecognized value.
    #>
    param([AllowNull()][AllowEmptyString()][string]$HeaderValue)
    if ($HeaderValue -cnotmatch '^(?<date>\d{4}-\d{2}-\d{2}) (?<time>\d{2}:\d{2}:\d{2}) (?<zone>UTC|[+-]\d{4})$') {
        return $null
    }
    $offset = if ($Matches.zone -eq 'UTC') { '+00:00' } else {
        $Matches.zone.Substring(0, 3) + ':' + $Matches.zone.Substring(3)
    }
    $expiresAt = [datetimeoffset]::Parse("$($Matches.date)T$($Matches.time)$offset",
        [Globalization.CultureInfo]::InvariantCulture)
    return $expiresAt.ToUniversalTime().ToString('o')
}

function Save-AutoDeployGitHubTokenExpiration {
    <# Remembers the token expiry GitHub reports on authenticated responses for this poll. #>
    param([Parameter(Mandatory)]$Response)
    $headersProperty = $Response.PSObject.Properties['Headers']
    if (-not $headersProperty -or $null -eq $headersProperty.Value) { return }
    $headerValue = @($headersProperty.Value['github-authentication-token-expiration']) |
        Select-Object -First 1
    $expiresAt = ConvertFrom-AutoDeployTokenExpirationHeader -HeaderValue ([string]$headerValue)
    if ($expiresAt) { $script:autoDeployGitHubTokenExpiresAt = $expiresAt }
}

function Invoke-AutoDeployGitHubReadApi {
    <#
    Reads with the installed token for its higher rate limit, falling back to anonymous access
    (the repository is public) when the token is unreadable or rejected.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Path
    )
    $token = $null
    try {
        $token = Read-AutoDeployGitHubToken $Config
    } catch {
        Write-AutoDeployGitHubWarning -Reason 'TOKEN_UNREADABLE'
    }
    if ($token) {
        try {
            return Invoke-AutoDeployGitHubApi -Path $Path -Token $token
        } catch {
            if ($_.Exception.Data['StatusCode'] -notin @(401, 403)) { throw }
            Write-AutoDeployGitHubWarning -Reason 'TOKEN_REJECTED'
        }
    }
    return Invoke-AutoDeployGitHubApi -Path $Path
}

function Get-AutoDeployCiConclusion {
    <#
    Returns SUCCESS, PENDING or FAILED for the newest CI run of a commit on the deployed branch.
    Push runs and manual (workflow_dispatch) runs count, so a run started by hand recovers a push
    whose event GitHub never delivered; pull request runs test a merge preview and never count.
    A commit without a qualifying run is PENDING.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Sha
    )
    if ($Sha -notmatch '^[0-9a-f]{40}$') { throw 'A CI conclusion requires a full commit SHA.' }
    $repository = Get-AutoDeployGitHubRepository $Config
    $branch = [uri]::EscapeDataString([string]$Config.branch)
    $runsPath = "repos/$repository/actions/workflows/$script:CiWorkflowFile/runs" +
        "?head_sha=$Sha&branch=$branch&per_page=10"
    $runPage = Invoke-AutoDeployGitHubReadApi -Config $Config -Path $runsPath
    $newestRun = @($runPage.workflow_runs) |
        Where-Object { [string]$_.event -in @('push', 'workflow_dispatch') } |
        Sort-Object -Property { [long]$_.run_number } -Descending |
        Select-Object -First 1
    if (-not $newestRun -or [string]$newestRun.status -ne 'completed') { return 'PENDING' }
    if ([string]$newestRun.conclusion -eq 'success') { return 'SUCCESS' }
    return 'FAILED'
}

function ConvertTo-AutoDeployUtcTimestamp {
    <# State JSON timestamps come back as DateTime values or round-trip strings; both must carry a zone. #>
    param([Parameter(Mandatory)][object]$Timestamp)
    if ($Timestamp -is [datetimeoffset]) { return $Timestamp }
    if ($Timestamp -is [datetime]) {
        if ($Timestamp.Kind -eq [DateTimeKind]::Unspecified) {
            throw 'Automatic deployment timestamp has no time zone.'
        }
        return [datetimeoffset]::new($Timestamp)
    }
    return [datetimeoffset]::Parse([string]$Timestamp, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind)
}

function Resolve-AutoDeployCiVerdict {
    <#
    Returns the CI verdict for a commit and records it in state. A FAILED verdict is reused until
    the failure backoff ends, and any verdict checked moments ago is reused within the same poll,
    so a failing commit costs a few anonymous API calls an hour instead of one a minute.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Sha,
        [Parameter(Mandatory)][datetime]$Now
    )
    if ($State.ciSha -eq $Sha -and $State.ciConclusion -and $State.ciCheckedAt) {
        $checkedAt = ConvertTo-AutoDeployUtcTimestamp -Timestamp $State.ciCheckedAt
        $reuseSeconds = if ($State.ciConclusion -eq 'FAILED') {
            [int]$Config.autoDeployFailureBackoffSeconds
        } else { 30 }
        if ($Now.ToUniversalTime() -lt $checkedAt.AddSeconds($reuseSeconds).UtcDateTime) {
            return [string]$State.ciConclusion
        }
    }
    $conclusion = Get-AutoDeployCiConclusion -Config $Config -Sha $Sha
    $State.ciSha = $Sha
    $State.ciConclusion = $conclusion
    $State.ciCheckedAt = $Now.ToUniversalTime().ToString('o')
    return $conclusion
}

function Publish-AutoDeployGitHubDeploymentStatus {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$Deployment,
        [Parameter(Mandatory)][ValidateSet('in_progress','success','failure')][string]$DeploymentState,
        [string]$Description
    )
    $statusBody = @{ state = $DeploymentState }
    if ($Description) {
        $statusBody.description = $Description.Substring(0,
            [math]::Min($Description.Length, $script:GitHubDescriptionMaximumLength))
    }
    if ($DeploymentState -eq 'success') { $statusBody.environment_url = [string]$Config.publicUrl }
    $token = Read-AutoDeployGitHubToken $Config
    Invoke-AutoDeployGitHubApi -Method Post -Token $token -Body $statusBody `
        -Path "repos/$($Deployment.Repository)/deployments/$($Deployment.Id)/statuses" | Out-Null
}

function Start-AutoDeployGitHubDeployment {
    <#
    Records a deployment attempt in the GitHub Production environment when a token is installed.
    Returns the deployment reference, or $null when reporting is off or GitHub refused it;
    reporting never changes the deployment itself.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Sha,
        [string]$Description = 'Automatic deployment of trusted main',
        [string]$KnownTokenExpiresAt
    )
    try {
        $token = Read-AutoDeployGitHubToken $Config
        if (-not $token) { return $null }
        $repository = Get-AutoDeployGitHubRepository $Config
        # The payload carries the token expiry so Production Watch can warn before it lapses.
        $tokenExpiresAt = if ($script:autoDeployGitHubTokenExpiresAt) {
            $script:autoDeployGitHubTokenExpiresAt
        } else { $KnownTokenExpiresAt }
        # required_contexts is empty because the CI gate has already proven this commit passed.
        $created = Invoke-AutoDeployGitHubApi -Method Post -Token $token -Path "repos/$repository/deployments" -Body @{
            ref = $Sha
            environment = $script:GitHubDeploymentEnvironment
            auto_merge = $false
            required_contexts = @()
            production_environment = $true
            description = $Description
            payload = @{ tokenExpiresAt = $tokenExpiresAt }
        }
        $deployment = [pscustomobject]@{ Repository = $repository; Id = [long]$created.id }
        Publish-AutoDeployGitHubDeploymentStatus -Config $Config -Deployment $deployment `
            -DeploymentState 'in_progress'
        return $deployment
    } catch {
        Write-AutoDeployGitHubWarning -Reason 'DEPLOYMENT_RECORD_FAILED'
        return $null
    }
}

function Complete-AutoDeployGitHubDeployment {
    <# Marks a recorded deployment as succeeded or failed; failures to report are only warned. #>
    param(
        [Parameter(Mandatory)]$Config,
        [AllowNull()]$Deployment,
        [Parameter(Mandatory)][bool]$Succeeded,
        [string]$FailureMessage
    )
    if (-not $Deployment) { return }
    try {
        if ($Succeeded) {
            Publish-AutoDeployGitHubDeploymentStatus -Config $Config -Deployment $Deployment `
                -DeploymentState 'success' -Description 'The new release is active.'
        } else {
            $safeDetail = Get-AutoDeploySafeFailureDetail -Message $FailureMessage
            $description = if ($safeDetail) { $safeDetail } else { 'Automatic deployment failed.' }
            Publish-AutoDeployGitHubDeploymentStatus -Config $Config -Deployment $Deployment `
                -DeploymentState 'failure' -Description $description
        }
    } catch {
        Write-AutoDeployGitHubWarning -Reason 'DEPLOYMENT_STATUS_FAILED'
    }
}

function Install-AutoDeployGitHubToken {
    <#
    Verifies a fine-grained GitHub token against this repository, then stores it in a protected
    file so automatic deployment can record Production deployments. The operator deletes the
    source file afterwards.
    #>
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [Parameter(Mandatory)][string]$SourcePath,
        # Read in the body, after the elevation check: a default-value expression would run during
        # parameter binding and fail on the protected file before any clear message could appear.
        $Config
    )
    Assert-AutoDeployAdministrator -Operation 'github-token-install'
    if ($null -eq $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionFixedRootBoundary -Config $Config -FixedRoot $script:FixedProductionRoot | Out-Null
    if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
        throw 'GitHubTokenPath must reference an existing file.'
    }
    $token = Read-AutoDeployTokenFileText -Path $SourcePath
    if (-not (Test-AutoDeployGitHubTokenShape $token)) {
        throw 'GitHubTokenPath does not contain a GitHub token.'
    }
    $repository = Get-AutoDeployGitHubRepository $Config
    # Verify before storing so a mistyped or under-scoped token never silently disables reporting.
    Invoke-AutoDeployGitHubApi -Token $token -Path "repos/$repository/deployments?per_page=1" | Out-Null

    $tokenPath = Get-AutoDeployGitHubTokenPath $Config
    if (-not $PSCmdlet.ShouldProcess($tokenPath, 'Store the protected GitHub deployment token')) { return }
    $temporaryPath = "$tokenPath.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        New-Item -ItemType File -Path $temporaryPath -Force | Out-Null
        # Restrict the file before it holds the secret.
        Protect-ProductionPath -Path $temporaryPath
        [IO.File]::WriteAllText($temporaryPath, $token, [Text.UTF8Encoding]::new($false))
        Move-AutoDeployFileAtomically -TemporaryPath $temporaryPath -DestinationPath $tokenPath
        Assert-ProtectedProductionPath -Path $tokenPath
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Output "Stored the GitHub deployment token for $repository. Delete $SourcePath now."
    if ($script:autoDeployGitHubTokenExpiresAt) {
        Write-Output "GitHub reports that this token expires at $script:autoDeployGitHubTokenExpiresAt. Production Watch warns 14 days before."
    } else {
        Write-Output 'GitHub reported no expiry for this token.'
    }
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
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateRange(1, 1048576)][int]$MaximumBytes = 8192
    )

    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.PSIsContainer -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Automatic deployment status file is not a normal file.'
    }
    if ($item.Length -gt $MaximumBytes) { throw 'Automatic deployment status file exceeds its size limit.' }
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
        'UP_TO_DATE' { 'The active release matches the trusted main branch (request-only changes are not deployed).' }
        'BACKING_OFF' { 'Retry is deferred for the recorded failed revision.' }
        'DEPLOYING' { 'Building and validating the latest trusted revision.' }
        'SUCCEEDED' { 'The new release is active.' }
        'DEPLOYMENT_FAILED' { 'Automatic deployment failed; details remain in protected diagnostics.' }
        'CHECK_FAILED' { 'The automatic deployment check failed before release validation.' }
        'BLOCKED' { 'Automatic deployment is blocked by a protected migration gate.' }
        'TOOLS_UPDATED' { 'Trusted deployment tools were refreshed; the next scheduled poll will continue.' }
        'AWAITING_CI' { 'The latest trusted revision is waiting for its CI build to pass.' }
        'CI_FAILED' { 'The latest trusted revision failed CI and will not be deployed.' }
        'HELD' { 'A requested rollback holds the latest trusted revision until main moves on.' }
        'OPS_REQUEST' { 'An operations request from trusted main is running.' }
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

function Get-AutoDeploySafeSmokeRouteLabel {
    param([string]$UriText)

    $uri = $null
    $candidate = $UriText
    if ($candidate.EndsWith('.')) {
        $candidate = $candidate.Substring(0, $candidate.Length - 1)
    }
    $parts = [regex]::Match(
        $candidate,
        '^[a-z][a-z0-9+.-]*://[^/?#]+(?<path>/[^?#]*)?(?:\?[^#]*)?(?:#.*)?$',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $parts.Success) { return $null }

    if (-not [uri]::TryCreate($candidate, [UriKind]::Absolute, [ref]$uri)) {
        return $null
    }

    $scope = switch ($uri.Host.ToLowerInvariant()) {
        { $_ -in @('127.0.0.1','localhost') } {
            if ($uri.Scheme -eq 'http' -and $uri.Port -in @(8080,8081)) { 'local' }
            break
        }
        { $_ -in @('christopherbell.dev','www.christopherbell.dev') } {
            if ($uri.Scheme -eq 'https' -and $uri.Port -eq 443) { 'public' }
            break
        }
    }
    if (-not $scope) { return $null }

    $rawPath = $parts.Groups['path'].Value
    if (-not $rawPath) { $rawPath = '/' }
    $route = switch -CaseSensitive ($rawPath) {
        '/' { 'home'; break }
        '/blog' { 'blog'; break }
        '/wfl' { 'wfl'; break }
        '/canes-box-tracker' { 'canes-box-tracker'; break }
        '/robots.txt' { 'robots'; break }
        '/sitemap.xml' { 'sitemap'; break }
        '/favicon.ico' { 'favicon'; break }
        '/actuator/health/liveness' { 'liveness'; break }
        '/actuator/health/readiness' { 'readiness'; break }
        '/.well-known/nodeinfo' { 'well-known-nodeinfo'; break }
        '/nodeinfo/2.1' { 'nodeinfo'; break }
    }
    if (-not $route) { return $null }
    return "$scope smoke route: $route"
}

function Get-AutoDeploySafeFailureDetail {
    param([string]$Message)

    if ([string]::IsNullOrWhiteSpace($Message)) { return $null }
    $detail = $Message -replace '[\r\n\t]+', ' '
    $detail = [regex]::Replace(
        $detail,
        "(?i)\b[a-z][a-z0-9+.-]*://[^\s<>""']+",
        [System.Text.RegularExpressions.MatchEvaluator]{
            param($match)
            $punctuation = if ($match.Value.EndsWith('.')) { '.' } else { '' }
            $route = Get-AutoDeploySafeSmokeRouteLabel -UriText $match.Value
            if ($route) { return "[$route]$punctuation" }
            return "[redacted]$punctuation"
        })
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
            'DEPLOYMENT_FAILED','CHECK_FAILED','BLOCKED','TOOLS_UPDATED','AWAITING_CI','CI_FAILED','HELD','OPS_REQUEST')]
        [string]$Outcome,
        [Parameter(Mandatory)]$State,
        [ValidateSet('NONE','REMOTE_CHECK','PROTECTED_PRECONDITION','DEPLOYMENT',
            'CANDIDATE_STARTUP','STATUS_STORE','CI_CHECK','CI_RESULT')]
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
    if ($pollerStatus.pollerReason -eq 'ACCESS_DENIED') {
        $pollerStatus = Get-AutoDeployPublishedPollerStatus -StatusRoot $StatusRoot -Fallback $pollerStatus
    }
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
            'DEPLOYMENT_FAILED','CHECK_FAILED','BLOCKED','TOOLS_UPDATED','AWAITING_CI','CI_FAILED','HELD','OPS_REQUEST')
        if ($record.schemaVersion -ne 1 -or $record.status -notin $allowedOutcomes -or
            $record.toolRefreshStatus -notin @('UNKNOWN','SUCCEEDED','FAILED') -or
            $record.failureCategory -notin @('NONE','REMOTE_CHECK','PROTECTED_PRECONDITION',
                'DEPLOYMENT','CANDIDATE_STARTUP','STATUS_STORE','CI_CHECK','CI_RESULT')) {
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
    if ($state.heldRemoteSha -and $state.heldRemoteSha -ne $remote) {
        # main moved past the rolled-away commit, so the rollback hold ends.
        $state.heldRemoteSha = $null
    }
    $isHeld = [bool]$state.heldRemoteSha
    $isAcknowledgedOpsOnly = $remote -ne $active -and $state.opsOnlyAcknowledgedSha -eq $remote
    if ($remote -eq $active -or $isHeld -or $isAcknowledgedOpsOnly) {
        if ($recoveryFailure) {
            Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYMENT_FAILED' `
                -FailureCategory 'CANDIDATE_STARTUP' -State $state `
                -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
            throw $recoveryFailure
        }
        if ($recoveryBackoff) { return }
        if ($remote -eq $active) {
            $state.successfulSha = $remote
            $state.error = $null
            $state.failedSha = $null
            $state.failedAt = $null
        }
        Complete-AutoDeployCurrentRelease -Config $Config -State $state -RemoteSha $remote `
            -ActiveSha $active -StatusRoot $StatusRoot -Now $now
        return
    }
    try {
        $ciConclusion = Resolve-AutoDeployCiVerdict -Config $Config -State $state -Sha $remote -Now $now
    } catch {
        Publish-AutoDeployStatusBestEffort -Outcome 'CHECK_FAILED' -FailureCategory 'CI_CHECK' `
            -State $state -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
        throw
    }
    if ($ciConclusion -ne 'SUCCESS') {
        # Only a CI-green commit may replace the active release. A failed recovery of the active
        # release still surfaces, exactly as it does when main is already active.
        if ($recoveryFailure) {
            Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYMENT_FAILED' `
                -FailureCategory 'CANDIDATE_STARTUP' -State $state `
                -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
            throw $recoveryFailure
        }
        Write-AutoDeployState $Config $state
        if ($recoveryBackoff) { return }
        if ($ciConclusion -eq 'FAILED') {
            Publish-AutoDeployStatusBestEffort -Outcome 'CI_FAILED' -FailureCategory 'CI_RESULT' `
                -State $state -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
        } else {
            Publish-AutoDeployStatusBestEffort -Outcome 'AWAITING_CI' -State $state `
                -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
        }
        return
    }
    if (Test-AutoDeployOpsOnlyChange -Config $Config -FromSha $active -ToSha $remote) {
        # A commit that only adds operations requests must not deploy: it would replace the
        # previous release with an identical build and defeat a requested rollback.
        if ($recoveryFailure) {
            Publish-AutoDeployStatusBestEffort -Outcome 'DEPLOYMENT_FAILED' `
                -FailureCategory 'CANDIDATE_STARTUP' -State $state `
                -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
            throw $recoveryFailure
        }
        if ($recoveryBackoff) {
            Write-AutoDeployState $Config $state
            return
        }
        $state.opsOnlyAcknowledgedSha = $remote
        Complete-AutoDeployCurrentRelease -Config $Config -State $state -RemoteSha $remote `
            -ActiveSha $active -StatusRoot $StatusRoot -Now $now
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
    $gitHubDeployment = Start-AutoDeployGitHubDeployment -Config $Config -Sha $remote
    $statusPublisher = Get-Command Publish-AutoDeployStatusBestEffort -ErrorAction Stop
    $heartbeatCallback = {
        & $statusPublisher -Outcome 'DEPLOYING' -State $state `
            -ActiveSha $active -StatusRoot $StatusRoot | Out-Null
    }.GetNewClosure()
    $deploymentFailure = $null
    try {
        Invoke-ProductionDeploy -Automatic `
            -HeartbeatCallback $heartbeatCallback `
            -HeartbeatIntervalSeconds 60
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
    Complete-AutoDeployGitHubDeployment -Config $Config -Deployment $gitHubDeployment `
        -Succeeded (-not $deploymentFailure) -FailureMessage ([string]$state.error)
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
    # These tools run as SYSTEM, so they follow the same CI gate as the website release.
    $ciConclusion = Resolve-AutoDeployCiVerdict -Config $Config -State $state -Sha $sha `
        -Now (Get-Date).ToUniversalTime()
    Write-AutoDeployState $Config $state
    if ($ciConclusion -ne 'SUCCESS') {
        if ($currentSha -notmatch $versionPattern) {
            throw "Automatic deployment tools cannot be installed from $sha until CI passes for it."
        }
        return [pscustomobject]@{ Sha=$currentSha; SourceSha=$state.toolSourceSha; Switched=$false }
    }
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
    $script:autoDeployGitHubWarningEmitted = $false
    $script:autoDeployGitHubTokenExpiresAt = $null
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
        try {
            Invoke-AutoDeployOnce -Config $config -StatusRoot $statusRoot
        } finally {
            Publish-AutoDeployDiagnosticsBestEffort -Config $config -StatusRoot $statusRoot
        }
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

#region Operations requests

$script:OpsRequestDirectory = 'ops/requests'
$script:OpsRequestActions = @('restart','backup','verify-startup','redeploy','rollback')
$script:OpsRequestProperties = @('id','action','reason','requestedAt','expectedActiveSha')
$script:OpsRequestMaximumAge = [timespan]::FromHours(24)
$script:OpsRequestMaximumClockSkew = [timespan]::FromMinutes(5)
$script:OpsRequestHistoryLimit = 200

function Complete-AutoDeployCurrentRelease {
    <#
    Finishes a poll whose main tip needs no deployment: the active release matches it, a rollback
    holds it, or it differs only by operations requests. Publishes the status, then runs the next
    pending request from that tip.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$RemoteSha,
        [AllowNull()][string]$ActiveSha,
        [string]$StatusRoot,
        [Parameter(Mandatory)][datetime]$Now
    )
    Write-AutoDeployState $Config $State
    $outcome = if ($State.heldRemoteSha -eq $RemoteSha) { 'HELD' } else { 'UP_TO_DATE' }
    Publish-AutoDeployStatusBestEffort -Outcome $outcome -State $State `
        -ActiveSha $ActiveSha -StatusRoot $StatusRoot | Out-Null
    Invoke-AutoDeployPendingOpsRequests -Config $Config -State $State -RemoteSha $RemoteSha `
        -ActiveSha $ActiveSha -StatusRoot $StatusRoot -Now $Now
}

function Assert-AutoDeployCommitAvailable {
    <# Fetches the configured branch when a commit the poller must inspect is not yet local. #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Sha
    )
    $probeArguments = Get-TrustedGitArguments $Config.repositoryPath @('cat-file','-e',"$Sha^{commit}")
    try {
        Invoke-CheckedProcess 'git.exe' $probeArguments $Config.repositoryPath | Out-Null
    } catch {
        Resolve-OriginMainRelease -Config $Config | Out-Null
        Invoke-CheckedProcess 'git.exe' $probeArguments $Config.repositoryPath | Out-Null
    }
}

function Test-AutoDeployOpsOnlyChange {
    <# True when every path that differs between two commits is under ops/requests/. #>
    param(
        [Parameter(Mandatory)]$Config,
        [AllowNull()][string]$FromSha,
        [Parameter(Mandatory)][string]$ToSha
    )
    if (-not $FromSha -or $FromSha -eq $ToSha) { return $false }
    foreach ($sha in $FromSha, $ToSha) { Assert-AutoDeployCommitAvailable -Config $Config -Sha $sha }
    $diffArguments = Get-TrustedGitArguments $Config.repositoryPath @('diff','--name-only',$FromSha,$ToSha)
    $changedPaths = @(([string](Invoke-CheckedProcess 'git.exe' $diffArguments $Config.repositoryPath)) -split '\r?\n' |
        Where-Object { $_ })
    if ($changedPaths.Count -eq 0) { return $false }
    $deployablePaths = @($changedPaths | Where-Object { -not $_.StartsWith("$script:OpsRequestDirectory/", [StringComparison]::Ordinal) })
    return $deployablePaths.Count -eq 0
}

function Get-AutoDeployOpsRequestFiles {
    <# Lists ops/requests/*.json at a commit and returns each file's id and raw content. #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Sha
    )
    Assert-AutoDeployCommitAvailable -Config $Config -Sha $Sha
    $listArguments = Get-TrustedGitArguments $Config.repositoryPath @(
        'ls-tree','--name-only',$Sha,"$script:OpsRequestDirectory/")
    $listing = [string](Invoke-CheckedProcess 'git.exe' $listArguments $Config.repositoryPath)
    foreach ($requestPath in $listing -split '\r?\n' | Where-Object { $_ -match '^ops/requests/[^/]+\.json$' }) {
        $showArguments = Get-TrustedGitArguments $Config.repositoryPath @('show',"$($Sha):$requestPath")
        [pscustomobject]@{
            Id = [IO.Path]::GetFileNameWithoutExtension($requestPath)
            Content = [string](Invoke-CheckedProcess 'git.exe' $showArguments $Config.repositoryPath)
        }
    }
}

function Test-AutoDeployOpsRequestDocument {
    <#
    Validates one request file. Returns Outcome READY, REJECTED (malformed) or EXPIRED (outside
    the freshness window when -Now is given; CI omits -Now and checks only the schema).
    #>
    param(
        [Parameter(Mandatory)][string]$FileId,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Content,
        [Nullable[datetime]]$Now
    )
    function New-OpsRequestVerdict([string]$Outcome, [string]$Detail, $Request, [string]$Action) {
        [pscustomobject]@{ Id = $FileId; Action = $Action; Outcome = $Outcome; Detail = $Detail; Request = $Request }
    }
    try {
        $document = $Content | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return New-OpsRequestVerdict 'REJECTED' 'The request is not valid JSON.' $null $null
    }
    if ($document -isnot [pscustomobject]) {
        return New-OpsRequestVerdict 'REJECTED' 'The request must be a JSON object.' $null $null
    }
    $propertyNames = @($document.PSObject.Properties.Name)
    $unknownProperties = @($propertyNames | Where-Object { $_ -cnotin $script:OpsRequestProperties })
    if ($unknownProperties.Count -gt 0) {
        return New-OpsRequestVerdict 'REJECTED' "Unknown request properties: $($unknownProperties -join ', ')." $null $null
    }
    $action = if ($propertyNames -contains 'action') { [string]$document.action } else { $null }
    if ($propertyNames -notcontains 'id' -or [string]$document.id -cne $FileId -or
        $FileId -cnotmatch '^[a-z0-9][a-z0-9-]{2,79}$') {
        return New-OpsRequestVerdict 'REJECTED' 'id must match the file name and use 3-80 lowercase letters, digits or hyphens.' $null $action
    }
    if ($action -cnotin $script:OpsRequestActions) {
        return New-OpsRequestVerdict 'REJECTED' "action must be one of $($script:OpsRequestActions -join ', ')." $null $action
    }
    $reason = if ($propertyNames -contains 'reason') { $document.reason } else { $null }
    if ($reason -isnot [string] -or $reason.Trim().Length -eq 0 -or $reason.Length -gt 200 -or $reason -match '[\r\n]') {
        return New-OpsRequestVerdict 'REJECTED' 'reason must be a single line of 1-200 characters.' $null $action
    }
    $requestedAt = $null
    try {
        if ($propertyNames -notcontains 'requestedAt') { throw 'missing' }
        $requestedAtValue = $document.requestedAt
        if ($requestedAtValue -is [string] -and $requestedAtValue -notmatch '(Z|[+-]\d{2}:\d{2})$') { throw 'no zone' }
        $requestedAt = ConvertTo-AutoDeployUtcTimestamp -Timestamp $requestedAtValue
    } catch {
        return New-OpsRequestVerdict 'REJECTED' 'requestedAt must be an ISO 8601 timestamp with a time zone.' $null $action
    }
    $expectedActiveSha = if ($propertyNames -contains 'expectedActiveSha') { [string]$document.expectedActiveSha } else { $null }
    if ($expectedActiveSha -and $expectedActiveSha -cnotmatch '^[0-9a-f]{40}$') {
        return New-OpsRequestVerdict 'REJECTED' 'expectedActiveSha must be a full lowercase commit SHA.' $null $action
    }
    if ($action -eq 'rollback' -and -not $expectedActiveSha) {
        return New-OpsRequestVerdict 'REJECTED' 'rollback requires expectedActiveSha, the release it replaces.' $null $action
    }
    $request = [pscustomobject]@{
        id = $FileId
        action = $action
        reason = $reason
        requestedAt = $requestedAt
        expectedActiveSha = $expectedActiveSha
    }
    if ($null -ne $Now) {
        $nowUtc = [datetimeoffset]::new(([datetime]$Now).ToUniversalTime())
        if ($requestedAt -gt $nowUtc.Add($script:OpsRequestMaximumClockSkew)) {
            return New-OpsRequestVerdict 'EXPIRED' 'requestedAt is in the future.' $request $action
        }
        if ($nowUtc - $requestedAt -gt $script:OpsRequestMaximumAge) {
            return New-OpsRequestVerdict 'EXPIRED' 'The request is older than 24 hours and was not run.' $request $action
        }
    }
    return New-OpsRequestVerdict 'READY' 'Valid request.' $request $action
}

function Add-AutoDeployOpsRequestResult {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Id,
        [AllowNull()][string]$Action,
        [Parameter(Mandatory)][ValidateSet('SUCCEEDED','FAILED','REJECTED','EXPIRED')][string]$Outcome,
        [Parameter(Mandatory)][string]$Detail,
        [Parameter(Mandatory)][string]$Sha
    )
    $result = [pscustomobject][ordered]@{
        id = $Id
        action = $Action
        outcome = $Outcome
        detail = ConvertTo-AutoDeployRedactedText -Text $Detail -MaximumLength 240
        sha = $Sha
        finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $State.processedOpsRequests = @(@($State.processedOpsRequests) + $result |
        Select-Object -Last $script:OpsRequestHistoryLimit)
}

function Invoke-AutoDeployOpsRequest {
    <# Runs one validated request through the existing guarded operation and returns its outcome. #>
    param(
        [Parameter(Mandatory)]$Request,
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$RemoteSha,
        [AllowNull()][string]$ActiveSha
    )
    switch ($Request.action) {
        'restart' {
            Restart-ProductionService -Verify | Out-Null
            return [pscustomobject]@{ Outcome = 'SUCCEEDED'; Detail = 'The website service restarted and passed its checks.' }
        }
        'backup' {
            $archivePath = New-ProductionBackup
            return [pscustomobject]@{ Outcome = 'SUCCEEDED'; Detail = "Verified backup $([IO.Path]::GetFileName([string]$archivePath))." }
        }
        'verify-startup' {
            Test-ProductionStartup | Out-Null
            return [pscustomobject]@{ Outcome = 'SUCCEEDED'; Detail = 'Services, scheduler task, local and public endpoints passed.' }
        }
        'redeploy' {
            $deployment = Start-AutoDeployGitHubDeployment -Config $Config -Sha $RemoteSha `
                -Description "Redeploy requested by $($Request.id)" -KnownTokenExpiresAt $State.githubTokenExpiresAt
            try {
                Invoke-ProductionDeploy -Automatic
            } catch {
                Complete-AutoDeployGitHubDeployment -Config $Config -Deployment $deployment `
                    -Succeeded $false -FailureMessage $_.Exception.Message
                throw
            }
            Complete-AutoDeployGitHubDeployment -Config $Config -Deployment $deployment -Succeeded $true
            $State.successfulSha = Get-ActiveReleaseSha $Config
            $State.opsOnlyAcknowledgedSha = $null
            return [pscustomobject]@{ Outcome = 'SUCCEEDED'; Detail = "Redeployed $($RemoteSha.Substring(0, 7))." }
        }
        'rollback' {
            if ($Request.expectedActiveSha -ne $ActiveSha) {
                return [pscustomobject]@{
                    Outcome = 'REJECTED'
                    Detail = 'expectedActiveSha no longer matches the active release; nothing was rolled back.'
                }
            }
            Invoke-ProductionRollback
            $restoredSha = Get-ActiveReleaseSha $Config
            if (-not $restoredSha -or $restoredSha -eq $ActiveSha) {
                throw 'Rollback finished without changing the active release.'
            }
            $State.heldRemoteSha = $RemoteSha
            $State.opsOnlyAcknowledgedSha = $null
            $deployment = Start-AutoDeployGitHubDeployment -Config $Config -Sha $restoredSha `
                -Description "Rollback requested by $($Request.id)" -KnownTokenExpiresAt $State.githubTokenExpiresAt
            Complete-AutoDeployGitHubDeployment -Config $Config -Deployment $deployment -Succeeded $true
            return [pscustomobject]@{
                Outcome = 'SUCCEEDED'
                Detail = "Rolled back to $($restoredSha.Substring(0, 7)); main $($RemoteSha.Substring(0, 7)) is held until main moves."
            }
        }
    }
}

function Invoke-AutoDeployPendingOpsRequests {
    <#
    Records malformed and expired requests from the main tip, then runs at most one valid request,
    oldest first. Each request id is handled once; a failed request is not retried.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$RemoteSha,
        [AllowNull()][string]$ActiveSha,
        [string]$StatusRoot,
        [Parameter(Mandatory)][datetime]$Now
    )
    try {
        $requestFiles = @(Get-AutoDeployOpsRequestFiles -Config $Config -Sha $RemoteSha)
    } catch {
        Write-Warning 'Operations requests could not be read from trusted main; they will be retried next poll.'
        return
    }
    $processedIds = @($State.processedOpsRequests | ForEach-Object { [string]$_.id })
    $pendingFiles = @($requestFiles | Where-Object { $_.Id -notin $processedIds })
    if ($pendingFiles.Count -eq 0) { return }
    # Requests run only from a CI-green tip, like any deployment.
    $ciConclusion = Resolve-AutoDeployCiVerdict -Config $Config -State $State -Sha $RemoteSha -Now $Now
    if ($ciConclusion -ne 'SUCCESS') {
        Write-AutoDeployState $Config $State
        return
    }
    $verdicts = @($pendingFiles | ForEach-Object {
        Test-AutoDeployOpsRequestDocument -FileId $_.Id -Content $_.Content -Now $Now
    })
    foreach ($verdict in $verdicts | Where-Object Outcome -in @('REJECTED','EXPIRED')) {
        Add-AutoDeployOpsRequestResult -State $State -Id $verdict.Id -Action $verdict.Action `
            -Outcome $verdict.Outcome -Detail $verdict.Detail -Sha $RemoteSha
    }
    $nextRequest = $verdicts | Where-Object Outcome -eq 'READY' |
        Sort-Object -Property { $_.Request.requestedAt }, Id | Select-Object -First 1
    if ($nextRequest) {
        Write-AutoDeployState $Config $State
        Publish-AutoDeployStatusBestEffort -Outcome 'OPS_REQUEST' -State $State `
            -ActiveSha $ActiveSha -StatusRoot $StatusRoot | Out-Null
        try {
            $result = Invoke-AutoDeployOpsRequest -Request $nextRequest.Request -Config $Config `
                -State $State -RemoteSha $RemoteSha -ActiveSha $ActiveSha
        } catch {
            $result = [pscustomobject]@{
                Outcome = 'FAILED'
                Detail = Get-AutoDeployFailureMessage -Exception $_.Exception
            }
        }
        Add-AutoDeployOpsRequestResult -State $State -Id $nextRequest.Id -Action $nextRequest.Action `
            -Outcome $result.Outcome -Detail $result.Detail -Sha $RemoteSha
    }
    Write-AutoDeployState $Config $State
    $currentActiveSha = Get-ActiveReleaseSha $Config
    $outcome = if ($State.heldRemoteSha -eq $RemoteSha) { 'HELD' } else { 'UP_TO_DATE' }
    Publish-AutoDeployStatusBestEffort -Outcome $outcome -State $State `
        -ActiveSha $currentActiveSha -StatusRoot $StatusRoot | Out-Null
}

#endregion

#region Diagnostics

$script:DiagnosticsFileName = 'diagnostics.json'
$script:DiagnosticsMaximumBytes = 524288
$script:DiagnosticsLogEntryLimit = 100
$script:DiagnosticsProblemEntryLimit = 50
$script:DiagnosticsProblemScanLineLimit = 10000
$script:DiagnosticsStackFrameLimit = 3
$script:DiagnosticsFreshnessSeconds = 180
$script:DiagnosticsServiceNames = @('ChristopherBellDev','MongoDB','cloudflared')

function ConvertTo-AutoDeployRedactedText {
    <#
    Masks credentials, tokens, connection-string passwords and email addresses, flattens the text
    to one line and bounds its length. Diagnostics are readable by every local user.
    #>
    param(
        [AllowNull()][AllowEmptyString()][string]$Text,
        [ValidateRange(16, 4000)][int]$MaximumLength = 400
    )
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    $redacted = $Text
    $redacted = $redacted -replace '\bgithub_pat_[A-Za-z0-9_]+', '[REDACTED_TOKEN]'
    $redacted = $redacted -replace '\bgh[opsur]_[A-Za-z0-9]{16,}', '[REDACTED_TOKEN]'
    $redacted = $redacted -replace '\bre_[A-Za-z0-9_]{16,}', '[REDACTED_TOKEN]'
    $redacted = $redacted -replace '(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+', 'Bearer [REDACTED]'
    $redacted = $redacted -replace '\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]'
    $redacted = $redacted -replace '(?i)\b(mongodb(?:\+srv)?://)[^/\s@]+@', '$1[REDACTED]@'
    $redacted = $redacted -replace '(?i)\b(password|passwd|pwd|secret|api[_-]?key|token)(\s*[=:]\s*)[^\s,;&"]+', '$1$2[REDACTED]'
    $redacted = $redacted -replace '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '[REDACTED_EMAIL]'
    $redacted = ($redacted -replace '[\x00-\x1F\x7F]+', ' ').Trim()
    if ($redacted.Length -gt $MaximumLength) {
        $redacted = $redacted.Substring(0, $MaximumLength - 3) + '...'
    }
    return $redacted
}

function ConvertTo-AutoDeployLogEntry {
    <#
    Converts one structured log line to the allowlisted, redacted diagnostics shape, or returns nothing
    for a line that is not a JSON object. errorStack keeps only the first few stack frames.
    #>
    param([AllowEmptyString()][string]$Line)
    try { $record = $Line | ConvertFrom-Json -ErrorAction Stop } catch { return }
    if ($record -isnot [pscustomobject]) { return }
    $logField = $record.PSObject.Properties['log']
    $errorField = $record.PSObject.Properties['error']
    $timestampField = $record.PSObject.Properties['@timestamp']
    $stackFrames = @(if ($errorField -and $errorField.Value.PSObject.Properties['stack_trace']) {
        ([string]$errorField.Value.stack_trace) -split '\r?\n' |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_.StartsWith('at ') } |
            Select-Object -First $script:DiagnosticsStackFrameLimit
    })
    [pscustomobject][ordered]@{
        timestamp = if ($timestampField) {
            $timestampValue = $timestampField.Value
            if ($timestampValue -is [datetime]) { $timestampValue.ToUniversalTime().ToString('o') } else { [string]$timestampValue }
        } else { $null }
        level = if ($logField -and $logField.Value.PSObject.Properties['level']) { [string]$logField.Value.level } else { $null }
        logger = if ($logField -and $logField.Value.PSObject.Properties['logger']) {
            ConvertTo-AutoDeployRedactedText -Text ([string]$logField.Value.logger) -MaximumLength 120
        } else { $null }
        requestId = if ($record.PSObject.Properties['requestId']) {
            ConvertTo-AutoDeployRedactedText -Text ([string]$record.requestId) -MaximumLength 64
        } else { $null }
        message = if ($record.PSObject.Properties['message']) {
            ConvertTo-AutoDeployRedactedText -Text ([string]$record.message)
        } else { '' }
        errorType = if ($errorField -and $errorField.Value.PSObject.Properties['type']) {
            ConvertTo-AutoDeployRedactedText -Text ([string]$errorField.Value.type) -MaximumLength 160
        } else { $null }
        errorMessage = if ($errorField -and $errorField.Value.PSObject.Properties['message']) {
            ConvertTo-AutoDeployRedactedText -Text ((([string]$errorField.Value.message) -split '\r?\n')[0])
        } else { $null }
        errorStack = if ($stackFrames.Count -gt 0) {
            ConvertTo-AutoDeployRedactedText -Text ($stackFrames -join ' | ') -MaximumLength 600
        } else { $null }
    }
}

function Read-AutoDeployRecentLogEntries {
    <# Returns the newest structured log entries with only allowlisted, redacted fields. #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateRange(1, 1000)][int]$MaximumEntries = $script:DiagnosticsLogEntryLimit
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    $entries = foreach ($line in Get-Content -LiteralPath $Path -Tail ($MaximumEntries * 4) -ErrorAction Stop) {
        ConvertTo-AutoDeployLogEntry -Line $line
    }
    return @($entries | Select-Object -Last $MaximumEntries)
}

function Read-AutoDeployRecentProblemEntries {
    <#
    Returns the newest WARN and ERROR entries from a much longer stretch of the log than the latest-entries
    window, so a problem stays visible after routine INFO lines push it out of recentLogEntries. Lines are
    filtered by text before any JSON parse, which keeps the scan cheap enough to run every poll.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateRange(1, 1000)][int]$MaximumEntries = $script:DiagnosticsProblemEntryLimit,
        [ValidateRange(1, 100000)][int]$ScannedLineLimit = $script:DiagnosticsProblemScanLineLimit
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    $entries = foreach ($line in Get-Content -LiteralPath $Path -Tail $ScannedLineLimit -ErrorAction Stop) {
        if ($line -notmatch '"level"\s*:\s*"(WARN|ERROR)"') { continue }
        $entry = ConvertTo-AutoDeployLogEntry -Line $line
        if ($entry -and $entry.level -in 'WARN', 'ERROR') { $entry }
    }
    return @($entries | Select-Object -Last $MaximumEntries)
}

function Get-AutoDeployServiceSnapshot {
    foreach ($serviceName in $script:DiagnosticsServiceNames) {
        $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
        [pscustomobject][ordered]@{
            name = $serviceName
            status = if ($service) { [string]$service.Status } else { 'NotInstalled' }
            startType = if ($service) { [string]$service.StartType } else { $null }
        }
    }
}

function Get-AutoDeployReleaseSnapshot {
    param([Parameter(Mandatory)]$Config)
    $releasesRoot = Join-Path $Config.programDataRoot 'releases'
    if (-not (Test-Path -LiteralPath $releasesRoot -PathType Container)) { return @() }
    $currentTarget = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    $previousTarget = Get-JunctionTarget (Join-Path $Config.programDataRoot 'previous')
    return @(Get-ChildItem -LiteralPath $releasesRoot -Directory -ErrorAction Stop |
        Where-Object Name -match '^[0-9a-f]{40}$' |
        Sort-Object LastWriteTimeUtc -Descending |
        ForEach-Object {
            [pscustomobject][ordered]@{
                sha = $_.Name
                builtAt = $_.LastWriteTimeUtc.ToString('o')
                current = $_.FullName -eq $currentTarget
                previous = $_.FullName -eq $previousTarget
            }
        })
}

function Publish-AutoDeployDiagnostics {
    <#
    Writes a sanitized, size-bounded diagnostics record that standard users can read, so operators
    and agents without administrator rights can see service, release, log and request state.
    #>
    param(
        [Parameter(Mandatory)]$Config,
        [string]$StatusRoot = (Get-AutoDeployStatusStoreRoot)
    )
    Assert-AutoDeployStatusDirectory -Path $StatusRoot
    $state = Read-AutoDeployState $Config
    if ($script:autoDeployGitHubTokenExpiresAt -and
        $state.githubTokenExpiresAt -ne $script:autoDeployGitHubTokenExpiresAt) {
        $state.githubTokenExpiresAt = $script:autoDeployGitHubTokenExpiresAt
        Write-AutoDeployState $Config $state
    }
    $schedulerEntry = Get-AutoDeployTaskSchedulerEntry
    $applicationLogPath = Join-Path $Config.programDataRoot 'logs\application.json.log'
    $logEntries = @(Read-AutoDeployRecentLogEntries -Path $applicationLogPath)
    $problemEntries = @(Read-AutoDeployRecentProblemEntries -Path $applicationLogPath)
    $record = [ordered]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        scheduler = [ordered]@{
            registered = [bool]$schedulerEntry.registered
            state = $schedulerEntry.state
            reason = [string]$schedulerEntry.reason
        }
        services = @(Get-AutoDeployServiceSnapshot)
        releases = @(Get-AutoDeployReleaseSnapshot -Config $Config)
        heldRemoteSha = $state.heldRemoteSha
        githubTokenExpiresAt = $state.githubTokenExpiresAt
        opsRequests = @(@($state.processedOpsRequests) | Select-Object -Last 20)
        recentLogEntries = $logEntries
        recentProblems = $problemEntries
    }
    $json = $record | ConvertTo-Json -Depth 6
    # Shed the oldest routine entries first, then the oldest problems, until the record fits;
    # everything else is small and bounded.
    foreach ($sheddableField in 'recentLogEntries', 'recentProblems') {
        while ([Text.Encoding]::UTF8.GetByteCount($json) -gt $script:DiagnosticsMaximumBytes -and
            $record[$sheddableField].Count -gt 0) {
            $record[$sheddableField] = @($record[$sheddableField] |
                Select-Object -Last ([math]::Floor($record[$sheddableField].Count / 2)))
            $json = $record | ConvertTo-Json -Depth 6
        }
    }
    $path = Join-Path $StatusRoot $script:DiagnosticsFileName
    if (Test-Path -LiteralPath $path) {
        Assert-AutoDeployStatusFile -Path $path -MaximumBytes $script:DiagnosticsMaximumBytes
    }
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        Set-Content -LiteralPath $temporary -Value $json -Encoding utf8 -ErrorAction Stop
        Assert-AutoDeployStatusFile -Path $temporary -MaximumBytes $script:DiagnosticsMaximumBytes
        Move-AutoDeployFileAtomically -TemporaryPath $temporary -DestinationPath $path
        Assert-AutoDeployStatusFile -Path $path -MaximumBytes $script:DiagnosticsMaximumBytes
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-AutoDeployPublishedPollerStatus {
    <#
    Standard users cannot query the SYSTEM task. Fresh diagnostics prove the poller ran within the
    last three minutes, so report the scheduler state it published instead of UNKNOWN.
    #>
    param(
        [Parameter(Mandatory)][string]$StatusRoot,
        [Parameter(Mandatory)]$Fallback
    )
    try {
        $diagnostics = Get-AutoDeployDiagnostics -StatusRoot $StatusRoot
    } catch {
        return $Fallback
    }
    if ($diagnostics.freshness -ne 'FRESH' -or -not $diagnostics.scheduler.registered) { return $Fallback }
    $publishedState = switch ([int]$diagnostics.scheduler.state) {
        1 { 'DISABLED' }
        2 { 'QUEUED' }
        3 { 'READY' }
        4 { 'RUNNING' }
        default { $null }
    }
    if (-not $publishedState) { return $Fallback }
    return [pscustomobject]@{
        pollerState = $publishedState
        pollerReason = 'REPORTED_BY_POLLER'
    }
}

function Publish-AutoDeployDiagnosticsBestEffort {
    param($Config, [string]$StatusRoot)
    if (-not $Config) { return }
    try {
        $arguments = @{ Config = $Config }
        if ($StatusRoot) { $arguments.StatusRoot = $StatusRoot }
        Publish-AutoDeployDiagnostics @arguments
    } catch {
        Write-Warning 'Automatic deployment diagnostics could not be published; operator diagnostics may be stale.'
    }
}

function Get-AutoDeployDiagnostics {
    <# Reads the published diagnostics record; standard users can run it. #>
    [CmdletBinding()]
    param([string]$StatusRoot = (Get-AutoDeployStatusStoreRoot))
    $path = Join-Path $StatusRoot $script:DiagnosticsFileName
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'No diagnostics have been published yet; the automatic deployment poller publishes them every minute.'
    }
    Assert-AutoDeployStatusFile -Path $path -MaximumBytes $script:DiagnosticsMaximumBytes
    $record = Get-Content -LiteralPath $path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    if ($record.schemaVersion -ne 1) { throw 'Diagnostics record has an unsupported schema version.' }
    $generatedAt = ConvertTo-AutoDeployUtcTimestamp -Timestamp $record.generatedAt
    $ageSeconds = ([datetimeoffset]::UtcNow - $generatedAt).TotalSeconds
    $freshness = if ($ageSeconds -le $script:DiagnosticsFreshnessSeconds) { 'FRESH' } else { 'STALE' }
    $record | Add-Member -NotePropertyName freshness -NotePropertyValue $freshness -Force
    return $record
}

#endregion

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
    Get-AutoDeployStatus,Read-AutoDeployGitHubToken,Install-AutoDeployGitHubToken,`
    Get-AutoDeployDiagnostics,Test-AutoDeployOpsRequestDocument
