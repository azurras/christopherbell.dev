Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ProductionSmokePaths = @(
    '/',
    '/blog',
    '/wfl',
    '/canes-box-tracker',
    '/robots.txt',
    '/sitemap.xml',
    '/favicon.ico',
    '/actuator/health/liveness',
    '/actuator/health/readiness',
    '/.well-known/nodeinfo',
    '/nodeinfo/2.1'
)
$script:CandidateDatabasePattern = '^cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24}$'
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
        $environment = @{
            GRADLE_USER_HOME = Join-Path $Config.programDataRoot 'gradle-home'
            NODE_EXE = $Config.nodeExe
            CHRISTOPHERBELL_PRODUCTION_DEPLOYMENT = '1'
        }
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
    $releaseCommit = Split-Path -Leaf $release
    if ($releaseCommit -notmatch '^[0-9a-f]{40}$') {
        throw 'Production release directory must use a full Git SHA.'
    }
    $environment.GIT_COMMIT = $releaseCommit
    $arguments = @(
        '-Xrs',
        '--enable-native-access=ALL-UNNAMED',
        '-jar',
        $jar,
        "--spring.profiles.active=$Profiles",
        "--server.port=$Port"
    )
    $start = New-ProductionProcessStartInfo `
        -FilePath $Config.javaExe `
        -ArgumentList $arguments `
        -WorkingDirectory $release `
        -Environment $environment
    return [Diagnostics.Process]::Start($start)
}

function Test-ProductionEndpoints {
    param($Config, [int]$Port)
    for ($index = 0; $index -lt $script:ProductionSmokePaths.Count; $index++) {
        $path = $script:ProductionSmokePaths[$index]
        $timeout = if ($index -eq 0) {
            [timespan]::FromMinutes(3)
        } else {
            [timespan]::FromSeconds(30)
        }
        Wait-HttpStatus `
            -Uri "http://127.0.0.1:$Port$path" `
            -ExpectedStatus 200 `
            -Timeout $timeout | Out-Null
    }
    $body = @{ email=$Config.smokeAccountEmail; password='deployment-smoke-intentionally-invalid' } | ConvertTo-Json
    $response = Invoke-ProductionWebRequest `
        -Uri "http://127.0.0.1:$Port/api/accounts/2024-12-15/login" `
        -Method Post `
        -ContentType 'application/json' `
        -Body $body `
        -TimeoutSec 15
    if ([int]$response.StatusCode -ne 401) { throw "Smoke login expected HTTP 401, received $($response.StatusCode)." }
    if ([string]$response.Content -match 'RESOURCE_NOT_FOUND') { throw 'Smoke account was not found in the configured production database.' }
}

function Test-ProductionPublicEndpoints {
    param($Config)
    $checkCount = 0
    foreach ($publicUrl in @($Config.publicUrls)) {
        $baseUri = [uri]$publicUrl
        foreach ($path in $script:ProductionSmokePaths) {
            $uri = [uri]::new($baseUri, $path.TrimStart('/'))
            Wait-HttpStatus `
                -Uri $uri `
                -ExpectedStatus 200 `
                -Timeout ([timespan]::FromSeconds(30)) | Out-Null
            $checkCount++
        }
    }
    return $checkCount
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

function Assert-CandidateDatabaseName {
    param([Parameter(Mandatory)][string]$Database)
    if ($Database -notmatch $script:CandidateDatabasePattern) {
        throw 'Invalid candidate database name.'
    }
    return $Database
}

function New-CandidateDatabaseName {
    param([Parameter(Mandatory)][string]$Sha)
    if ($Sha -notmatch '^[0-9a-f]{40}$') {
        throw 'Candidate database release must use a full Git SHA.'
    }
    $nonce = [guid]::NewGuid().ToString('N').Substring(0, 24)
    return Assert-CandidateDatabaseName "cbell_candidate_$($Sha.Substring(0, 12))_$nonce"
}

function Restore-CandidateDatabaseFromBackup {
    param(
        $Config,
        [Parameter(Mandatory)][string]$Archive,
        [Parameter(Mandatory)][string]$Database
    )
    $database = Assert-CandidateDatabaseName $Database
    $arguments = @(
        '--uri=mongodb://127.0.0.1:27017'
        "--archive=$Archive"
        '--gzip'
        '--drop'
        '--nsFrom=christopherbell.*'
        "--nsTo=$database.*"
    )
    Invoke-CheckedProcess `
        -FilePath (Join-Path $Config.mongoToolsPath 'mongorestore.exe') `
        -ArgumentList $arguments `
        -WorkingDirectory $Config.repositoryPath | Out-Null
}

function Remove-CandidateDatabase {
    param($Config, [Parameter(Mandatory)][string]$Database)
    $database = Assert-CandidateDatabaseName $Database
    $cleanupScript = @'
const candidateDatabase = process.env.CBELL_CANDIDATE_DATABASE;
if (!/^cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24}$/.test(candidateDatabase)) {
  throw new Error('Invalid candidate database name.');
}
const result = db.getSiblingDB(candidateDatabase).dropDatabase();
if (!result.ok) {
  throw new Error('Candidate database cleanup failed.');
}
'@
    Invoke-CheckedProcess `
        -FilePath $Config.mongoShellExe `
        -ArgumentList @(
            '--quiet'
            'mongodb://127.0.0.1:27017/admin'
            '--eval'
            $cleanupScript
        ) `
        -WorkingDirectory $Config.repositoryPath `
        -Environment @{ CBELL_CANDIDATE_DATABASE = $database } | Out-Null
}

function Invoke-CandidateReleaseValidation {
    param(
        $Config,
        [Parameter(Mandatory)][string]$Release,
        [Parameter(Mandatory)][string]$Sha
    )
    $database = New-CandidateDatabaseName -Sha $Sha
    $validationFailure = $null
    $cleanupFailure = $null
    try {
        $archive = New-ProductionBackup
        Restore-CandidateDatabaseFromBackup `
            -Config $Config -Archive $archive -Database $database
        Test-CandidateRelease -Config $Config -Release $Release -Database $database
    } catch {
        $validationFailure = $_.Exception
    } finally {
        try {
            Remove-CandidateDatabase -Config $Config -Database $database
        } catch {
            $cleanupFailure = $_.Exception
        }
    }

    if ($validationFailure -and $cleanupFailure) {
        throw [System.AggregateException]::new(
            'Candidate validation and exact database cleanup both failed.',
            [System.Exception[]]@($validationFailure, $cleanupFailure))
    }
    if ($cleanupFailure) { throw $cleanupFailure }
    if ($validationFailure) { throw $validationFailure }
}

function Invoke-BoundedCheckedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [ValidateRange(1,60000)]
        [int]$TimeoutMilliseconds = 5000
    )

    $start = New-ProductionProcessStartInfo `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -RedirectStandardOutput `
        -RedirectStandardError

    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($start)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            $timeoutFailure = [System.TimeoutException]::new(
                "$([IO.Path]::GetFileName($FilePath)) did not exit within $TimeoutMilliseconds milliseconds.")
            try {
                $process.Kill($true)
            } catch {
                throw [System.AggregateException]::new(
                    'A checked process timed out and could not be terminated.',
                    [System.Exception[]]@($timeoutFailure, $_.Exception))
            }
            [void]$process.WaitForExit(1000)
            throw $timeoutFailure
        }

        $stdout = $stdoutTask.GetAwaiter().GetResult()
        [void]$stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "$([IO.Path]::GetFileName($FilePath)) exited with code $($process.ExitCode)."
        }
        return $stdout
    } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
}

function Assert-ProductionWebsiteRecoveryPolicy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Suspended','Normal')]
        [string]$Policy,
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$QueryOutput
    )

    $resetLabelMatches = [regex]::Matches(
        $QueryOutput,
        '(?im)^[ \t]*RESET_PERIOD[ \t]*\(in seconds\)[ \t]*:')
    $resetMatches = [regex]::Matches(
        $QueryOutput,
        '(?im)^[ \t]*RESET_PERIOD[ \t]*\(in seconds\)[ \t]*:[ \t]*(?<Seconds>\d+)[ \t]*\r?$')
    $hasSingleResetField = $resetLabelMatches.Count -eq 1 -and $resetMatches.Count -eq 1
    $resetPeriodSeconds = if ($hasSingleResetField) {
        [int]$resetMatches[0].Groups['Seconds'].Value
    } else {
        $null
    }
    $failureActionFieldMatches = [regex]::Matches(
        $QueryOutput,
        '(?im)^[ \t]*FAILURE_ACTIONS[ \t]*:[ \t]*(?<Value>[^\r\n]*)\r?$')
    $failureActionFieldCount = $failureActionFieldMatches.Count
    $actionMatches = [regex]::Matches(
        $QueryOutput,
        '(?im)^[ \t]*(?:(?<Label>FAILURE_ACTIONS)[ \t]*:[ \t]*)?(?<Action>RESTART|REBOOT|RUN COMMAND)[ \t]*--[ \t]*Delay[ \t]*=[ \t]*(?<Delay>\d+)[ \t]*milliseconds\.[ \t]*\r?$')
    $actualActions = @(
        $actionMatches | ForEach-Object {
            "$($_.Groups['Action'].Value):$($_.Groups['Delay'].Value)"
        }
    )
    $delayLineCount = [regex]::Matches(
        $QueryOutput,
        '(?im)--[ \t]*Delay[ \t]*=').Count

    $expectedResetPeriodSeconds = if ($Policy -eq 'Suspended') { 0 } else { 3600 }
    $matchesExpectedPolicy = if ($Policy -eq 'Suspended') {
        $hasSingleResetField -and
            $failureActionFieldCount -eq 0 -and
            $resetPeriodSeconds -eq $expectedResetPeriodSeconds -and
            $delayLineCount -eq 0 -and
            $actualActions.Count -eq 0
    } else {
        $hasSingleResetField -and
            $failureActionFieldCount -eq 1 -and
            $resetPeriodSeconds -eq $expectedResetPeriodSeconds -and
            $delayLineCount -eq 2 -and
            $actualActions.Count -eq 2 -and
            $actionMatches[0].Groups['Label'].Success -and
            -not $actionMatches[1].Groups['Label'].Success -and
            $actualActions[0] -eq 'RESTART:10000' -and
            $actualActions[1] -eq 'RESTART:30000'
    }
    if (-not $matchesExpectedPolicy) {
        $actualReset = if ($null -eq $resetPeriodSeconds) {
            'unavailable'
        } else {
            [string]$resetPeriodSeconds
        }
        $actualActionSummary = if ($actualActions.Count -eq 0) {
            'none'
        } else {
            $actualActions -join ', '
        }
        $expectedActions = if ($Policy -eq 'Suspended') {
            'none'
        } else {
            'RESTART:10000, RESTART:30000'
        }
        throw [System.InvalidOperationException]::new(
            "$Policy recovery policy verification failed. Expected reset period $expectedResetPeriodSeconds seconds and actions $expectedActions; received reset period $actualReset and actions $actualActionSummary.")
    }
}

function Set-ProductionWebsiteRecoveryPolicy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Suspended','Normal')]
        [string]$Policy,
        [ValidateRange(1,60000)]
        [int]$RecoveryCommandTimeoutMilliseconds = 5000
    )

    $actions = if ($Policy -eq 'Normal') {
        'restart/10000/restart/30000'
    } else {
        ''
    }
    $resetPeriodSeconds = if ($Policy -eq 'Normal') { 3600 } else { 0 }
    $phase = if ($Policy -eq 'Normal') { 'restore' } else { 'suspend' }
    try {
        Invoke-BoundedCheckedProcess -FilePath 'sc.exe' -ArgumentList @(
            'failure',
            'ChristopherBellDev',
            'reset=',
            [string]$resetPeriodSeconds,
            'actions=',
            $actions
        ) -TimeoutMilliseconds $RecoveryCommandTimeoutMilliseconds | Out-Null
    } catch {
        throw [System.InvalidOperationException]::new(
            "Failed to $phase website service recovery during mutation: $($_.Exception.Message)",
            $_.Exception)
    }

    $verificationPhase = if ($Policy -eq 'Normal') { 'restored' } else { 'suspended' }
    try {
        $queryOutput = Invoke-BoundedCheckedProcess `
            -FilePath 'sc.exe' `
            -ArgumentList @('qfailure','ChristopherBellDev') `
            -TimeoutMilliseconds $RecoveryCommandTimeoutMilliseconds
    } catch {
        throw [System.InvalidOperationException]::new(
            "Failed to verify $verificationPhase website service recovery: $($_.Exception.Message)",
            $_.Exception)
    }
    Assert-ProductionWebsiteRecoveryPolicy -Policy $Policy -QueryOutput $queryOutput
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
        [int]$PortTimeoutMilliseconds = 10000,
        [ValidateRange(1,60000)]
        [int]$RecoveryCommandTimeoutMilliseconds = 5000
    )

    $operationFailure = $null
    $restoreFailure = $null
    $suspensionAttempted = $false
    try {
        $suspensionAttempted = $true
        try {
            Set-ProductionWebsiteRecoveryPolicy `
                -Policy Suspended `
                -RecoveryCommandTimeoutMilliseconds $RecoveryCommandTimeoutMilliseconds
        } catch {
            $operationFailure = $_.Exception
        }

        if ($null -eq $operationFailure) {
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
        }
    } finally {
        if ($suspensionAttempted) {
            try {
                Set-ProductionWebsiteRecoveryPolicy `
                    -Policy Normal `
                    -RecoveryCommandTimeoutMilliseconds $RecoveryCommandTimeoutMilliseconds
            } catch {
                $restoreFailure = $_.Exception
            }
        }
    }

    if ($restoreFailure) {
        if ($operationFailure) {
            throw [System.AggregateException]::new(
                'Website service stop and recovery restoration both failed.',
                [System.Exception[]]@($operationFailure, $restoreFailure))
        }
        throw $restoreFailure
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
    $liveMigrationStarted = $false
    try {
        if ($old) { Set-AtomicJunction $Config $previousPath $old }
        Set-AtomicJunction $Config $currentPath $release
        $liveMigrationStarted = $true
        Start-Service ChristopherBellDev
        Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
        Test-ProductionPublicEndpoints -Config $Config | Out-Null
    } catch {
        $deploymentFailure = $_.Exception
        if ($liveMigrationStarted) {
            $stopFailure = $null
            try {
                Stop-ProductionWebsiteService -ProductionPort $Config.productionPort
            } catch {
                $stopFailure = $_.Exception
            }
            if ($stopFailure) {
                throw [System.AggregateException]::new(
                    'Forward-only production migration/cutover failed after the live migration boundary and the website stop postcondition could not be confirmed. Do not restart the prior binary; repair forward with operator intervention.',
                    [System.Exception[]]@($deploymentFailure, $stopFailure))
            }
            throw [System.InvalidOperationException]::new(
                'Forward-only production migration/cutover failed after the live migration boundary. The website is stopped and unready. Repair the live data or deploy a compatible forward release; do not restart the prior binary.',
                $deploymentFailure)
        }
        if ($old) {
            try {
                Stop-ProductionWebsiteService -ProductionPort $Config.productionPort
                Set-AtomicJunction $Config $currentPath $old
                Start-Service ChristopherBellDev
                Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
                Test-ProductionPublicEndpoints -Config $Config | Out-Null
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

function Switch-ProductionReleaseAfterMusicReconciliation {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Release,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Sha,
        [Parameter(Mandatory)]$Direction
    )
    $release = Assert-ReleasePath $Config $Release
    $currentPath = Join-Path $Config.programDataRoot 'current'
    $previousPath = Join-Path $Config.programDataRoot 'previous'
    $old = Get-JunctionTarget $currentPath
    if (-not $old -or (Split-Path -Leaf $old) -cne [string]$Direction.legacyRelease) {
        throw 'The active release does not match the legacy Music schema-direction marker.'
    }

    Stop-ProductionWebsiteService -ProductionPort $Config.productionPort
    try {
        Invoke-ProductionMusicRuntimeReconciliationNoLock -Config $Config | Out-Null
        Set-AtomicJunction $Config $previousPath $old
        Set-AtomicJunction $Config $currentPath $release
        Write-ProductionMusicSchemaDirection `
            -Config $Config `
            -State TARGET_ACTIVE `
            -TargetRelease $Sha `
            -LegacyRelease ([string]$Direction.legacyRelease)
        Start-Service ChristopherBellDev
        Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
        Test-ProductionPublicEndpoints -Config $Config | Out-Null
    } catch {
        $deploymentFailure = $_.Exception
        try {
            Stop-ProductionWebsiteService -ProductionPort $Config.productionPort
        } catch {
            throw [System.AggregateException]::new(
                'Music runtime reconciliation deployment failed and the writer stop postcondition also failed.',
                [System.Exception[]]@($deploymentFailure, $_.Exception))
        }
        throw [System.InvalidOperationException]::new(
            'Music runtime reconciliation deployment failed; the writer remains stopped.',
            $deploymentFailure)
    }
}

function Invoke-ProductionDeploy {
    [CmdletBinding()]
    param(
        [switch]$WhatIf,
        [switch]$MusicSchemaCutover
    )
    $config = Read-ProductionConfig
    $lock = Enter-DeploymentLock (Join-Path $config.programDataRoot 'locks\deploy.lock')
    try {
        $direction = Read-ProductionMusicSchemaDirection -Config $config
        if (-not $direction -and -not $MusicSchemaCutover -and -not $WhatIf) {
            throw ('Normal deploy is blocked because the Music schema-direction marker is absent. ' +
                'Use the protected first-cutover path with -MusicSchemaCutover.')
        }
        if ($direction -and
            [string]$direction.state -eq 'TARGET_CUTOVER_IN_PROGRESS') {
            throw ('Deploy is blocked because the first Music schema cutover is incomplete. ' +
                'Keep the writer stopped and complete bounded recovery under deploy.lock.')
        }
        if ($MusicSchemaCutover -and $direction) {
            throw 'First Music schema cutover requires an absent schema-direction marker.'
        }
        $legacyRelease = if ($MusicSchemaCutover) {
            Get-JunctionTarget (Join-Path $config.programDataRoot 'current')
        } else { $null }
        if ($MusicSchemaCutover) {
            if (-not $legacyRelease) {
                throw 'First Music schema cutover requires an active legacy release.'
            }
            Assert-ReleasePath $config $legacyRelease | Out-Null
            if ((Split-Path -Leaf $legacyRelease) -notmatch '^[0-9a-f]{40}$') {
                throw 'First Music schema cutover legacy release identity is invalid.'
            }
        }
        $sha = Resolve-OriginMainRelease $config
        if ($WhatIf) { Write-Output "Would deploy $($config.remote)/$($config.branch) at $sha"; return }
        $release = New-ReleaseFromOriginMain $config $sha
        Invoke-CandidateReleaseValidation -Config $config -Release $release -Sha $sha
        if ($direction -and [string]$direction.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
            Switch-ProductionReleaseAfterMusicReconciliation `
                -Config $config `
                -Release $release `
                -Sha $sha `
                -Direction $direction
        } else {
            if ($MusicSchemaCutover) {
                $legacySha = Split-Path -Leaf $legacyRelease
                Write-ProductionMusicSchemaDirection `
                    -Config $config `
                    -State TARGET_CUTOVER_IN_PROGRESS `
                    -TargetRelease $sha `
                    -LegacyRelease $legacySha
                try {
                    Switch-ProductionRelease $config $release
                    Write-ProductionMusicSchemaDirection `
                        -Config $config `
                        -State TARGET_ACTIVE `
                        -TargetRelease $sha `
                        -LegacyRelease $legacySha
                } catch {
                    $cutoverFailure = $_.Exception
                    try {
                        Stop-ProductionWebsiteService -ProductionPort $config.productionPort
                    } catch {
                        throw [System.AggregateException]::new(
                            'First Music schema cutover failed and the writer stop postcondition also failed.',
                            [System.Exception[]]@($cutoverFailure, $_.Exception))
                    }
                    throw [System.InvalidOperationException]::new(
                        'First Music schema cutover failed; the writer remains stopped and direction is pending.',
                        $cutoverFailure)
                }
            } elseif ($direction -and [string]$direction.state -eq 'TARGET_ACTIVE') {
                Switch-ProductionRelease $config $release
                Write-ProductionMusicSchemaDirection `
                    -Config $config `
                    -State TARGET_ACTIVE `
                    -TargetRelease $sha `
                    -LegacyRelease ([string]$direction.legacyRelease)
            } else {
                Switch-ProductionRelease $config $release
            }
        }
        Remove-ExpiredReleases $config
    } finally { $lock.Dispose() }
}

function Confirm-ProductionMusicTargetActive {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')]
        [string]$TargetRelease,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')]
        [string]$LegacyRelease,
        [Parameter(Mandatory)][switch]$MigrationVerified
    )
    if (-not $MigrationVerified) {
        throw 'Target schema-direction initialization requires explicit verified migration confirmation.'
    }
    $config = Read-ProductionConfig
    $lock = Enter-DeploymentLock (Join-Path $config.programDataRoot 'locks\deploy.lock')
    try {
        $existing = Read-ProductionMusicSchemaDirection -Config $config
        if (-not $existing) {
            throw 'Target schema direction cannot be confirmed without a protected marker.'
        }
        if ([string]$existing.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
            throw 'Target schema direction cannot be confirmed while legacy reconciliation is required.'
        }
        if ([string]$existing.targetRelease -cne $TargetRelease -or
            [string]$existing.legacyRelease -cne $LegacyRelease) {
            throw 'Target schema-direction confirmation does not match the protected marker.'
        }
        $current = Get-JunctionTarget (Join-Path $config.programDataRoot 'current')
        $previous = Get-JunctionTarget (Join-Path $config.programDataRoot 'previous')
        if (-not $current -or -not $previous -or
            (Split-Path -Leaf $current) -cne $TargetRelease -or
            (Split-Path -Leaf $previous) -cne $LegacyRelease) {
            throw 'Target schema-direction release identity does not match active junctions.'
        }
        $resuming = [string]$existing.state -eq 'TARGET_CUTOVER_IN_PROGRESS'
        try {
            if ($resuming) { Start-Service ChristopherBellDev }
            Test-ProductionEndpoints -Config $config -Port $config.productionPort
            Test-ProductionPublicEndpoints -Config $config | Out-Null
            Write-ProductionMusicSchemaDirection `
                -Config $config `
                -State TARGET_ACTIVE `
                -TargetRelease $TargetRelease `
                -LegacyRelease $LegacyRelease
        } catch {
            $confirmationFailure = $_.Exception
            if ($resuming) {
                try {
                    Stop-ProductionWebsiteService -ProductionPort $config.productionPort
                } catch {
                    throw [System.AggregateException]::new(
                        'Pending Music cutover confirmation failed and the writer stop postcondition also failed.',
                        [System.Exception[]]@($confirmationFailure, $_.Exception))
                }
                throw [System.InvalidOperationException]::new(
                    'Pending Music cutover confirmation failed; the writer remains stopped.',
                    $confirmationFailure)
            }
            throw $confirmationFailure
        }
    } finally {
        $lock.Dispose()
    }
}

Export-ModuleMember -Function Invoke-ProductionDeploy,Resolve-OriginMainRelease,New-ReleaseFromOriginMain,Start-ProductionJar,Test-ProductionEndpoints,Test-ProductionPublicEndpoints,Test-CandidateRelease,Stop-ProductionWebsiteService,Switch-ProductionRelease,Switch-ProductionReleaseAfterMusicReconciliation,Remove-ExpiredReleases,Confirm-ProductionMusicTargetActive
