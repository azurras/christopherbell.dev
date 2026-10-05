<#
.SYNOPSIS
Checks christopherbell.dev from outside its host and keeps one GitHub alert issue in step with the result.

.DESCRIPTION
Probes the public readiness, home, blog and build-info routes with bounded retries, reads the
latest Production deployment status, and compares the live commit with main's CI-green head. An
unhealthy verdict opens or comments on the single open `production-alert` issue; a healthy
verdict closes it. Dot-source the script to load its functions without running the watch.
#>
[CmdletBinding()]
param(
    [string]$Repository,
    [string]$SiteUrl = 'https://www.christopherbell.dev',
    [string]$RunUrl,
    [ValidateRange(1, 1440)][int]$DeployLagThresholdMinutes = 45,
    [ValidateRange(1, 5)][int]$ProbeAttempts = 3,
    [ValidateRange(0, 60)][int]$ProbeRetryDelaySeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:AlertLabel = 'production-alert'
$script:AlertIssueTitle = 'Production alert: christopherbell.dev'
$script:ReleaseVersionPattern = '^0\.0\.0-dev\.(?<commit>[0-9a-f]{40})$'

function New-WatchCheck {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Passed,
        [Parameter(Mandatory)][string]$Detail
    )
    return [pscustomobject]@{ Name = $Name; Passed = $Passed; Detail = $Detail }
}

function Invoke-SiteProbe {
    <# Returns the last response for a route, retrying failures so one dropped request is not an outage. #>
    param(
        [Parameter(Mandatory)][uri]$RouteUri,
        [Parameter(Mandatory)][int]$Attempts,
        [Parameter(Mandatory)][int]$RetryDelaySeconds
    )
    $lastFailure = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $RouteUri -Method Get -TimeoutSec 15 `
                -SkipHttpErrorCheck -UseBasicParsing
            if ([int]$response.StatusCode -eq 200) { return $response }
            $lastFailure = "HTTP $([int]$response.StatusCode)"
        } catch {
            $lastFailure = $_.Exception.Message
        }
        if ($attempt -lt $Attempts) { Start-Sleep -Seconds $RetryDelaySeconds }
    }
    throw "GET $RouteUri failed after $Attempts attempts: $lastFailure"
}

function ConvertTo-ResponseText {
    <# Invoke-WebRequest returns bytes for vendor media types such as actuator JSON. #>
    param([AllowNull()][object]$Content)
    if ($Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($Content) }
    return [string]$Content
}

function Test-SiteRoute {
    param(
        [Parameter(Mandatory)][string]$SiteUrl,
        [Parameter(Mandatory)][string]$RoutePath,
        [Parameter(Mandatory)][int]$Attempts,
        [Parameter(Mandatory)][int]$RetryDelaySeconds
    )
    $routeUri = [uri]::new([uri]$SiteUrl, $RoutePath)
    try {
        $response = Invoke-SiteProbe -RouteUri $routeUri -Attempts $Attempts -RetryDelaySeconds $RetryDelaySeconds
        return [pscustomobject]@{
            Check = New-WatchCheck -Name "GET $RoutePath" -Passed $true -Detail 'HTTP 200'
            Content = ConvertTo-ResponseText $response.Content
        }
    } catch {
        return [pscustomobject]@{
            Check = New-WatchCheck -Name "GET $RoutePath" -Passed $false -Detail $_.Exception.Message
            Content = $null
        }
    }
}

function Get-LiveCommitFromBuildInfo {
    <# Returns the 40-character commit encoded in /actuator/info build.version, or $null. #>
    param([AllowNull()][string]$BuildInfoContent)
    if ([string]::IsNullOrWhiteSpace($BuildInfoContent)) { return $null }
    try {
        $buildInfo = $BuildInfoContent | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
    $buildProperty = $buildInfo.PSObject.Properties['build']
    if (-not $buildProperty -or -not $buildProperty.Value.PSObject.Properties['version']) { return $null }
    $releaseVersion = [string]$buildProperty.Value.version
    if ($releaseVersion -cmatch $script:ReleaseVersionPattern) { return $Matches.commit }
    return $null
}

function Invoke-GitHubApi {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Token
    )
    $headers = @{
        Accept = 'application/vnd.github+json'
        Authorization = "Bearer $Token"
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    return Invoke-RestMethod -Uri "https://api.github.com/$Path" -Headers $headers -Method Get -TimeoutSec 30
}

function Test-LatestProductionDeployment {
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$Token
    )
    $deployments = @(Invoke-GitHubApi -Path "repos/$Repository/deployments?environment=Production&per_page=1" -Token $Token)
    if ($deployments.Count -eq 0) {
        return New-WatchCheck -Name 'Latest Production deployment' -Passed $true `
            -Detail 'No Production deployments are recorded yet.'
    }
    $deployment = $deployments[0]
    $shortCommit = ([string]$deployment.sha).Substring(0, 7)
    $statuses = @(Invoke-GitHubApi -Path "repos/$Repository/deployments/$($deployment.id)/statuses?per_page=1" -Token $Token)
    if ($statuses.Count -eq 0) {
        return New-WatchCheck -Name 'Latest Production deployment' -Passed $true `
            -Detail "Deployment of $shortCommit has no status yet."
    }
    $latestState = [string]$statuses[0].state
    $passed = $latestState -notin @('failure', 'error')
    return New-WatchCheck -Name 'Latest Production deployment' -Passed $passed `
        -Detail ("Deployment of $shortCommit is $latestState" + $(if ($statuses[0].description) { ": $($statuses[0].description)" } else { '.' }))
}

function Test-DeploymentTokenExpiry {
    <#
    Reads the token expiry the production poller records in each deployment's payload and fails
    when it is within the warning window, so the token is renewed before reporting stops.
    #>
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][datetimeoffset]$Now,
        [ValidateRange(1, 90)][int]$WarningDays = 14
    )
    # Flatten: a JSON array can arrive as one array object, which hides each deployment's properties.
    $deployments = @(@(Invoke-GitHubApi -Path "repos/$Repository/deployments?environment=Production&per_page=1" -Token $Token) |
        ForEach-Object { $_ })
    $payload = if ($deployments.Count -gt 0 -and $deployments[0].PSObject.Properties['payload']) {
        $deployments[0].payload
    } else { $null }
    $expiresValue = if ($payload -and $payload.PSObject.Properties['tokenExpiresAt']) { $payload.tokenExpiresAt } else { $null }
    if (-not $expiresValue) {
        return New-WatchCheck -Name 'Deployment token expiry' -Passed $true -Detail 'No token expiry has been recorded.'
    }
    $expiresAt = ConvertTo-UtcTimestamp -Timestamp $expiresValue
    $expiryDate = $expiresAt.UtcDateTime.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
    $daysRemaining = [math]::Floor(($expiresAt - $Now).TotalDays)
    if ($daysRemaining -lt 0) {
        return New-WatchCheck -Name 'Deployment token expiry' -Passed $false `
            -Detail ("The production deployment token expired on $expiryDate; deployment records stopped. " +
                'Install a new one with prod.cmd github-token-install.')
    }
    if ($daysRemaining -le $WarningDays) {
        return New-WatchCheck -Name 'Deployment token expiry' -Passed $false `
            -Detail "The production deployment token expires in $daysRemaining days ($expiryDate); renew it with prod.cmd github-token-install."
    }
    return New-WatchCheck -Name 'Deployment token expiry' -Passed $true `
        -Detail "The production deployment token expires in $daysRemaining days ($expiryDate)."
}

function Test-OpsRequestOnlyDifference {
    <# True when main differs from the live commit only under ops/requests/, which never deploys. #>
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][string]$LiveCommit,
        [Parameter(Mandatory)][string]$MainCommit
    )
    try {
        $comparison = Invoke-GitHubApi -Path "repos/$Repository/compare/$LiveCommit...$MainCommit" -Token $Token
    } catch {
        return $false
    }
    $changedPaths = @(@($comparison.files) | ForEach-Object { [string]$_.filename })
    if ([string]$comparison.status -ne 'ahead' -or $changedPaths.Count -eq 0) { return $false }
    return -not ($changedPaths | Where-Object { -not $_.StartsWith('ops/requests/', [StringComparison]::Ordinal) })
}

function ConvertTo-UtcTimestamp {
    <# Invoke-RestMethod turns ISO timestamps into DateTime values; strings stay round-trip parsed. #>
    param([Parameter(Mandatory)][object]$Timestamp)
    if ($Timestamp -is [datetime]) {
        if ($Timestamp.Kind -eq [DateTimeKind]::Unspecified) {
            throw 'GitHub timestamp has no time zone.'
        }
        return [datetimeoffset]::new($Timestamp.ToUniversalTime())
    }
    return [datetimeoffset]::Parse([string]$Timestamp, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind)
}

function Test-DeploymentLag {
    <#
    Fails when main's head is not live and either passed CI more than the threshold ago (a stalled
    deploy) or still has no finished CI run that long after its commit (a missed or stuck run).
    A failed run is the auto-deploy gate refusing a red commit, which CI already reports.
    Push and manual runs count, as they do for the auto-deploy gate.
    #>
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$Token,
        [AllowNull()][string]$LiveCommit,
        [Parameter(Mandatory)][int]$ThresholdMinutes,
        [Parameter(Mandatory)][datetimeoffset]$Now
    )
    $mainHead = Invoke-GitHubApi -Path "repos/$Repository/commits/main" -Token $Token
    $mainCommit = [string]$mainHead.sha
    $runPage = Invoke-GitHubApi -Token $Token `
        -Path "repos/$Repository/actions/workflows/ci.yml/runs?head_sha=$mainCommit&branch=main&per_page=10"
    $newestRun = @($runPage.workflow_runs) |
        Where-Object { [string]$_.event -in @('push', 'workflow_dispatch') } |
        Sort-Object -Property { [long]$_.run_number } -Descending |
        Select-Object -First 1
    $shortMainCommit = $mainCommit.Substring(0, 7)
    if ($LiveCommit -eq $mainCommit) {
        return New-WatchCheck -Name 'Deployment lag' -Passed $true -Detail "main $shortMainCommit is live."
    }
    if ($LiveCommit -and (Test-OpsRequestOnlyDifference -Repository $Repository -Token $Token `
            -LiveCommit $LiveCommit -MainCommit $mainCommit)) {
        return New-WatchCheck -Name 'Deployment lag' -Passed $true `
            -Detail "main $shortMainCommit differs from live $($LiveCommit.Substring(0, 7)) only by operations requests, which are not deployed."
    }
    if (-not $newestRun -or [string]$newestRun.status -ne 'completed') {
        $committedAt = ConvertTo-UtcTimestamp -Timestamp $mainHead.commit.committer.date
        $minutesSinceCommit = [math]::Floor(($Now - $committedAt).TotalMinutes)
        if ($minutesSinceCommit -le $ThresholdMinutes) {
            return New-WatchCheck -Name 'Deployment lag' -Passed $true `
                -Detail "main $shortMainCommit has not passed CI yet; auto-deploy waits for it."
        }
        return New-WatchCheck -Name 'Deployment lag' -Passed $false `
            -Detail ("main $shortMainCommit has had no passing CI run for $minutesSinceCommit minutes; " +
                'auto-deploy is waiting for one. Run CI Build on main from the Actions tab.')
    }
    if ([string]$newestRun.conclusion -ne 'success') {
        return New-WatchCheck -Name 'Deployment lag' -Passed $true `
            -Detail "main $shortMainCommit failed CI; auto-deploy refuses it."
    }
    $greenAt = ConvertTo-UtcTimestamp -Timestamp $newestRun.updated_at
    $minutesSinceGreen = [math]::Floor(($Now - $greenAt).TotalMinutes)
    $liveDescription = if ($LiveCommit) { $LiveCommit.Substring(0, 7) } else { 'an unknown commit' }
    if ($minutesSinceGreen -le $ThresholdMinutes) {
        return New-WatchCheck -Name 'Deployment lag' -Passed $true `
            -Detail "main $shortMainCommit passed CI $minutesSinceGreen minutes ago; $liveDescription is live while it deploys."
    }
    return New-WatchCheck -Name 'Deployment lag' -Passed $false `
        -Detail "main $shortMainCommit passed CI $minutesSinceGreen minutes ago but $liveDescription is still live."
}

function Get-ProductionWatchVerdict {
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$SiteUrl,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][int]$DeployLagThresholdMinutes,
        [Parameter(Mandatory)][int]$ProbeAttempts,
        [Parameter(Mandatory)][int]$ProbeRetryDelaySeconds,
        [datetimeoffset]$Now = [datetimeoffset]::UtcNow
    )
    $checks = [Collections.Generic.List[object]]::new()
    foreach ($routePath in @('/actuator/health/readiness', '/', '/blog')) {
        $checks.Add((Test-SiteRoute -SiteUrl $SiteUrl -RoutePath $routePath `
            -Attempts $ProbeAttempts -RetryDelaySeconds $ProbeRetryDelaySeconds).Check)
    }
    $buildInfoProbe = Test-SiteRoute -SiteUrl $SiteUrl -RoutePath '/actuator/info' `
        -Attempts $ProbeAttempts -RetryDelaySeconds $ProbeRetryDelaySeconds
    $liveCommit = Get-LiveCommitFromBuildInfo -BuildInfoContent $buildInfoProbe.Content
    if ($buildInfoProbe.Check.Passed -and -not $liveCommit) {
        $checks.Add((New-WatchCheck -Name 'GET /actuator/info' -Passed $false `
            -Detail 'build.version does not identify a release commit.'))
    } else {
        $checks.Add($buildInfoProbe.Check)
    }
    $checks.Add((Test-LatestProductionDeployment -Repository $Repository -Token $Token))
    $checks.Add((Test-DeploymentTokenExpiry -Repository $Repository -Token $Token -Now $Now))
    $checks.Add((Test-DeploymentLag -Repository $Repository -Token $Token -LiveCommit $liveCommit `
        -ThresholdMinutes $DeployLagThresholdMinutes -Now $Now))
    return [pscustomobject]@{
        Healthy = -not ($checks | Where-Object { -not $_.Passed })
        LiveCommit = $liveCommit
        Checks = $checks.ToArray()
    }
}

function Format-WatchReport {
    param(
        [Parameter(Mandatory)]$Verdict,
        [string]$RunUrl
    )
    $reportLines = [Collections.Generic.List[string]]::new()
    $reportLines.Add($(if ($Verdict.Healthy) { 'Production is healthy.' } else { 'Production is unhealthy.' }))
    $reportLines.Add('')
    $reportLines.Add('| Check | Result | Detail |')
    $reportLines.Add('|---|---|---|')
    foreach ($check in $Verdict.Checks) {
        $result = if ($check.Passed) { 'Passed' } else { '**Failed**' }
        $reportLines.Add(('| {0} | {1} | {2} |' -f $check.Name, $result, ($check.Detail -replace '\|', '\|')))
    }
    if ($RunUrl) {
        $reportLines.Add('')
        $reportLines.Add("Checked by [Production Watch]($RunUrl).")
    }
    return $reportLines -join "`n"
}

function Invoke-GitHubCli {
    <# Runs gh and fails on a nonzero exit code, returning its standard output. #>
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw "gh $($Arguments[0]) $($Arguments[1]) failed with exit code $LASTEXITCODE." }
    return $output
}

function Update-ProductionAlertIssue {
    <# Keeps at most one open alert issue: open or comment when unhealthy, close when healthy. #>
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)]$Verdict,
        [Parameter(Mandatory)][string]$Report
    )
    $openIssuesJson = Invoke-GitHubCli @('issue', 'list', '--repo', $Repository, '--label', $script:AlertLabel,
        '--state', 'open', '--json', 'number', '--limit', '1')
    $openIssues = @(($openIssuesJson -join "`n") | ConvertFrom-Json)
    $openIssueNumber = if ($openIssues.Count -gt 0) { [string]$openIssues[0].number } else { $null }

    if ($Verdict.Healthy) {
        if ($openIssueNumber) {
            Invoke-GitHubCli @('issue', 'comment', $openIssueNumber, '--repo', $Repository,
                '--body', "Recovered.`n`n$Report") | Out-Null
            Invoke-GitHubCli @('issue', 'close', $openIssueNumber, '--repo', $Repository) | Out-Null
            return 'Closed'
        }
        return 'None'
    }
    if ($openIssueNumber) {
        Invoke-GitHubCli @('issue', 'comment', $openIssueNumber, '--repo', $Repository, '--body', $Report) | Out-Null
        return 'Commented'
    }
    Invoke-GitHubCli @('label', 'create', $script:AlertLabel, '--repo', $Repository, '--color', 'B60205',
        '--description', 'Opened and closed by Production Watch', '--force') | Out-Null
    Invoke-GitHubCli @('issue', 'create', '--repo', $Repository, '--title', $script:AlertIssueTitle,
        '--label', $script:AlertLabel, '--body', $Report) | Out-Null
    return 'Opened'
}

function Invoke-ProductionWatch {
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$SiteUrl,
        [string]$RunUrl,
        [Parameter(Mandatory)][int]$DeployLagThresholdMinutes,
        [Parameter(Mandatory)][int]$ProbeAttempts,
        [Parameter(Mandatory)][int]$ProbeRetryDelaySeconds
    )
    $token = $env:GITHUB_TOKEN
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'GITHUB_TOKEN must be set for the GitHub API checks.' }
    $verdict = Get-ProductionWatchVerdict -Repository $Repository -SiteUrl $SiteUrl -Token $token `
        -DeployLagThresholdMinutes $DeployLagThresholdMinutes -ProbeAttempts $ProbeAttempts `
        -ProbeRetryDelaySeconds $ProbeRetryDelaySeconds
    $report = Format-WatchReport -Verdict $verdict -RunUrl $RunUrl
    if ($env:GITHUB_STEP_SUMMARY) {
        "## Production Watch`n`n$report" | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Encoding utf8
    } else {
        Write-Output $report
    }
    $issueAction = Update-ProductionAlertIssue -Repository $Repository -Verdict $verdict -Report $report
    Write-Output "Alert issue action: $issueAction"
}

if ($MyInvocation.InvocationName -ne '.') {
    if ([string]::IsNullOrWhiteSpace($Repository) -or $Repository -notmatch '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$') {
        throw 'Repository must be an owner/name slug.'
    }
    Invoke-ProductionWatch -Repository $Repository -SiteUrl $SiteUrl -RunUrl $RunUrl `
        -DeployLagThresholdMinutes $DeployLagThresholdMinutes -ProbeAttempts $ProbeAttempts `
        -ProbeRetryDelaySeconds $ProbeRetryDelaySeconds
}
