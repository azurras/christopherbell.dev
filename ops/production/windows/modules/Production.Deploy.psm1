Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Resolve-OriginMainRelease {
    param($Config)
    $fetchArguments = Get-TrustedGitArguments $Config.repositoryPath @('fetch','--prune',$Config.remote,$Config.branch)
    Invoke-CheckedProcess -FilePath 'git.exe' -ArgumentList $fetchArguments -WorkingDirectory $Config.repositoryPath | Out-Null
    $resolveArguments = Get-TrustedGitArguments $Config.repositoryPath @('rev-parse',"$($Config.remote)/$($Config.branch)")
    $sha = (Invoke-CheckedProcess -FilePath 'git.exe' -ArgumentList $resolveArguments -WorkingDirectory $Config.repositoryPath).Trim()
    if ($sha -notmatch '^[0-9a-f]{40}$') { throw 'Fetched origin/main did not resolve to a full Git SHA.' }
    return $sha
}

function New-ReleaseFromOriginMain {
    param($Config, [Parameter(Mandatory)][string]$Sha)
    $worktree = Join-Path $Config.programDataRoot "worktrees\$Sha"
    $release = Join-Path $Config.programDataRoot "releases\$Sha"
    $staging = "$release.staging"
    if (Test-Path -LiteralPath $release -PathType Container) { return $release }
    New-Item -ItemType Directory -Force (Split-Path -Parent $worktree),(Split-Path -Parent $release) | Out-Null
    try {
        $addArguments = Get-TrustedGitArguments $Config.repositoryPath @('worktree','add','--detach',$worktree,$Sha)
        Invoke-CheckedProcess 'git.exe' $addArguments $Config.repositoryPath | Out-Null
        $environment = @{ GRADLE_USER_HOME=(Join-Path $Config.programDataRoot 'gradle-home'); NODE_EXE=$Config.nodeExe }
        Invoke-CheckedProcess (Join-Path $worktree 'gradlew.bat') @('--no-daemon',':website:build') $worktree $environment | Out-Null
        $jars = @(Get-ChildItem (Join-Path $worktree 'website\build\libs') -Filter '*.jar' | Where-Object Name -NotLike '*-plain.jar')
        if ($jars.Count -ne 1) { throw "Expected one executable boot JAR, found $($jars.Count)." }
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
        New-Item -ItemType Directory -Force $staging | Out-Null
        Copy-Item -LiteralPath $jars[0].FullName -Destination (Join-Path $staging 'app.jar')
        [ordered]@{ sha=$Sha; source="$($Config.remote)/$($Config.branch)"; builtAt=(Get-Date).ToUniversalTime().ToString('o') } |
            ConvertTo-Json | Set-Content (Join-Path $staging 'release.json') -Encoding utf8
        Move-Item -LiteralPath $staging -Destination $release
        return $release
    } finally {
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $worktree) {
            try {
                $removeArguments = Get-TrustedGitArguments $Config.repositoryPath @('worktree','remove','--force',$worktree)
                Invoke-CheckedProcess 'git.exe' $removeArguments $Config.repositoryPath | Out-Null
            } catch { }
        }
        try {
            $pruneArguments = Get-TrustedGitArguments $Config.repositoryPath @('worktree','prune')
            Invoke-CheckedProcess 'git.exe' $pruneArguments $Config.repositoryPath | Out-Null
        } catch { }
    }
}

function Start-ProductionJar {
    param($Config, [Parameter(Mandatory)][string]$Release, [int]$Port, [string]$Profiles, [hashtable]$AdditionalEnvironment = @{})
    $release = Assert-ReleasePath $Config $Release
    $jar = Join-Path $release 'app.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing release JAR: $jar" }
    $environment = Read-ProductionEnvironment (Join-Path $Config.programDataRoot 'config\app.env')
    foreach ($entry in $AdditionalEnvironment.GetEnumerator()) { $environment[$entry.Key] = [string]$entry.Value }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Config.javaExe
    $start.WorkingDirectory = $release
    $start.UseShellExecute = $false
    foreach ($argument in @(
        '-Xrs',
        '--enable-native-access=ALL-UNNAMED',
        '-jar',
        $jar,
        "--spring.profiles.active=$Profiles",
        "--server.port=$Port"
    )) { [void]$start.ArgumentList.Add($argument) }
    foreach ($entry in $environment.GetEnumerator()) { $start.Environment[$entry.Key] = [string]$entry.Value }
    return [Diagnostics.Process]::Start($start)
}

function Test-ProductionEndpoints {
    param($Config, [int]$Port)
    Wait-HttpStatus -Uri "http://127.0.0.1:$Port/" -ExpectedStatus 200 -Timeout ([timespan]::FromSeconds(90)) | Out-Null
    $body = @{ email=$Config.smokeAccountEmail; password='deployment-smoke-intentionally-invalid' } | ConvertTo-Json
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/accounts/2024-12-15/login" -Method Post -ContentType 'application/json' -Body $body -SkipHttpErrorCheck -TimeoutSec 15
    if ([int]$response.StatusCode -ne 401) { throw "Smoke login expected HTTP 401, received $($response.StatusCode)." }
    if ([string]$response.Content -match 'RESOURCE_NOT_FOUND') { throw 'Smoke account was not found in the configured production database.' }
}

function Test-CandidateRelease {
    param($Config, [Parameter(Mandatory)][string]$Release, [string]$Database)
    $additionalEnvironment = @{
        COMMAND_CENTER_SENSOR_LIBRARIES_ENABLED = 'false'
    }
    if (-not [string]::IsNullOrWhiteSpace($Database)) {
        $additionalEnvironment.SPRING_MONGODB_DATABASE = $Database
    }
    $process = Start-ProductionJar -Config $Config -Release $Release -Port $Config.candidatePort -Profiles 'prod,deploy-smoke' -AdditionalEnvironment $additionalEnvironment
    try {
        Test-ProductionEndpoints -Config $Config -Port $Config.candidatePort
    } finally {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        $process.WaitForExit(10000) | Out-Null
    }
}

function Set-ProductionWebsiteRecoveryPolicy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Suspended','Normal')]
        [string]$Policy
    )

    $actions = if ($Policy -eq 'Normal') {
        'restart/10000/restart/30000'
    } else {
        ''
    }
    $phase = if ($Policy -eq 'Normal') { 'restore' } else { 'suspend' }
    try {
        Invoke-CheckedProcess -FilePath 'sc.exe' -ArgumentList @(
            'failure',
            'ChristopherBellDev',
            'reset=',
            '3600',
            'actions=',
            $actions
        ) | Out-Null
    } catch {
        throw [System.InvalidOperationException]::new(
            "Failed to $phase website service recovery.",
            $_.Exception)
    }
}

function Assert-ProductionWebsiteStopped {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateRange(1,65535)]
        [int]$ProductionPort,
        [ValidateRange(1,300)]
        [int]$ServiceTimeoutSeconds = 30,
        [ValidateRange(1,60000)]
        [int]$PortTimeoutMilliseconds = 10000
    )

    try {
        $service = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
        $service.WaitForStatus(
            [System.ServiceProcess.ServiceControllerStatus]::Stopped,
            [timespan]::FromSeconds($ServiceTimeoutSeconds))
        $service.Refresh()
    } catch {
        throw [System.InvalidOperationException]::new(
            "ChristopherBellDev did not reach Stopped within $ServiceTimeoutSeconds seconds.",
            $_.Exception)
    }
    if ([string]$service.Status -ne 'Stopped') {
        throw "ChristopherBellDev did not reach Stopped within $ServiceTimeoutSeconds seconds."
    }

    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        try {
            $listeners = @(
                Get-NetTCPConnection -State Listen -ErrorAction Stop |
                    Where-Object LocalPort -eq $ProductionPort
            )
        } catch {
            throw [System.InvalidOperationException]::new(
                "Failed to inspect production port $ProductionPort.",
                $_.Exception)
        }
        if ($listeners.Count -eq 0) { return }
        if ($watch.ElapsedMilliseconds -ge $PortTimeoutMilliseconds) { break }
        $remaining = $PortTimeoutMilliseconds - [int]$watch.ElapsedMilliseconds
        Start-Sleep -Milliseconds ([Math]::Max(1, [Math]::Min(250, $remaining)))
    } while ($true)

    throw "Production port $ProductionPort remained open after ChristopherBellDev stopped."
}

function Stop-ProductionWebsiteService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateRange(1,65535)]
        [int]$ProductionPort,
        [ValidateRange(1,300)]
        [int]$ServiceTimeoutSeconds = 30,
        [ValidateRange(1,60000)]
        [int]$PortTimeoutMilliseconds = 10000
    )

    Set-ProductionWebsiteRecoveryPolicy -Policy Suspended
    $operationFailure = $null
    $restoreFailure = $null
    try {
        $stopFailure = $null
        try {
            Stop-Service -Name 'ChristopherBellDev' -ErrorAction Stop
        } catch {
            $stopFailure = $_.Exception
        }

        try {
            Assert-ProductionWebsiteStopped `
                -ProductionPort $ProductionPort `
                -ServiceTimeoutSeconds $ServiceTimeoutSeconds `
                -PortTimeoutMilliseconds $PortTimeoutMilliseconds
        } catch {
            $stateFailure = $_.Exception
            $operationFailure = if ($stopFailure) {
                [System.AggregateException]::new(
                    'Website service stop request and postcondition verification failed.',
                    [System.Exception[]]@($stopFailure, $stateFailure))
            } else {
                $stateFailure
            }
        }
    } finally {
        try {
            Set-ProductionWebsiteRecoveryPolicy -Policy Normal
        } catch {
            $restoreFailure = $_.Exception
        }
    }

    if ($restoreFailure) {
        if ($operationFailure) {
            throw [System.AggregateException]::new(
                'Website service stop and recovery restoration both failed.',
                [System.Exception[]]@($operationFailure, $restoreFailure))
        }
        throw [System.InvalidOperationException]::new(
            'Failed to restore website service recovery after the planned stop.',
            $restoreFailure)
    }
    if ($operationFailure) { throw $operationFailure }
}

function Switch-ProductionRelease {
    param($Config, [Parameter(Mandatory)][string]$Release)
    $release = Assert-ReleasePath $Config $Release
    $currentPath = Join-Path $Config.programDataRoot 'current'
    $previousPath = Join-Path $Config.programDataRoot 'previous'
    $old = Get-JunctionTarget $currentPath
    Stop-ProductionWebsiteService -ProductionPort $Config.productionPort
    try {
        if ($old) { Set-AtomicJunction $Config $previousPath $old }
        Set-AtomicJunction $Config $currentPath $release
        Start-Service ChristopherBellDev
        Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
    } catch {
        $deploymentFailure = $_.Exception
        if ($old) {
            try {
                Stop-ProductionWebsiteService -ProductionPort $Config.productionPort
                Set-AtomicJunction $Config $currentPath $old
                Start-Service ChristopherBellDev
                Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
            } catch {
                throw [System.AggregateException]::new(
                    'Production deployment and automatic rollback both failed.',
                    [System.Exception[]]@($deploymentFailure, $_.Exception))
            }
        }
        throw $deploymentFailure
    }
}

function Remove-ExpiredReleases {
    param($Config)
    $protected = @(
        Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
        Get-JunctionTarget (Join-Path $Config.programDataRoot 'previous')
    ) | Where-Object { $_ }
    $releases = @(Get-ChildItem (Join-Path $Config.programDataRoot 'releases') -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending)
    $kept = 0
    foreach ($release in $releases) {
        if ($protected -contains $release.FullName -or $kept -lt [int]$Config.releaseRetention) { $kept++; continue }
        Assert-ReleasePath $Config $release.FullName | Out-Null
        Remove-Item -LiteralPath $release.FullName -Recurse -Force
    }
}

function Invoke-ProductionDeploy {
    [CmdletBinding()]
    param([switch]$WhatIf)
    $config = Read-ProductionConfig
    $lock = Enter-DeploymentLock (Join-Path $config.programDataRoot 'locks\deploy.lock')
    try {
        $sha = Resolve-OriginMainRelease $config
        if ($WhatIf) { Write-Output "Would deploy $($config.remote)/$($config.branch) at $sha"; return }
        $release = New-ReleaseFromOriginMain $config $sha
        Test-CandidateRelease $config $release
        Switch-ProductionRelease $config $release
        Remove-ExpiredReleases $config
    } finally { $lock.Dispose() }
}

Export-ModuleMember -Function Invoke-ProductionDeploy,Resolve-OriginMainRelease,New-ReleaseFromOriginMain,Start-ProductionJar,Test-ProductionEndpoints,Test-CandidateRelease,Stop-ProductionWebsiteService,Switch-ProductionRelease,Remove-ExpiredReleases
