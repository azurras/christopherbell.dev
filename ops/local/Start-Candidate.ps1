#Requires -Version 7
<#
.SYNOPSIS
Starts the built candidate against a disposable, isolated MongoDB and records its state.

.DESCRIPTION
Starts mongod on an OS-selected loopback port with a fresh data directory, then starts
website\build\libs\website.jar with exactly profile test, database test, a fresh JWT secret and
a short JDK socket directory. Waits for readiness and writes a JSON state file that
Stop-Candidate.ps1 uses to stop and clean up only what this script created. The candidate's
settings never leak into the caller's environment. Build the jar first with
.\ops\local\Invoke-Gradle.ps1 :website:bootJar.

.EXAMPLE
.\ops\local\Start-Candidate.ps1 -StateFile $env:TEMP\candidate.json
#>
param(
    [Parameter(Mandatory)][string]$StateFile,
    [string]$Checkout = (Join-Path $PSScriptRoot '..\..'),
    [string]$MongodPath,
    [switch]$EnableFederationDiscovery,
    [int]$ReadinessTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CandidateRuntime.psm1') -Force

$checkoutPath = (Resolve-Path -LiteralPath $Checkout).Path
$candidateJar = Join-Path $checkoutPath 'website\build\libs\website.jar'
if (-not (Test-Path -LiteralPath $candidateJar -PathType Leaf)) {
    throw "Build the candidate first: $candidateJar is missing."
}
$mongodExe = Resolve-MongodExecutable -RequestedPath $MongodPath
$mongoshExe = (Get-Command mongosh.exe -CommandType Application -ErrorAction Stop).Source
$javaExe = (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source

$testMongoRoot = New-OwnedTemporaryDirectory -Parent ([IO.Path]::GetTempPath()) -Prefix 'christopherbell-test-mongo-'
$javaSocketRoot = New-OwnedTemporaryDirectory -Parent (Join-Path $env:SystemDrive 'Temp') -Prefix 'cbell-jdk-'
$databasePath = Join-Path $testMongoRoot 'db'
New-Item -ItemType Directory -Path $databasePath -Force | Out-Null
$mongoPort = Get-FreeLoopbackPort
$candidatePort = Get-FreeLoopbackPort -Excluding $mongoPort
$state = [ordered]@{
    baseUrl = "http://127.0.0.1:$candidatePort"
    candidatePid = $null
    candidatePort = $candidatePort
    mongoPid = $null
    mongoPort = $mongoPort
    testMongoRoot = $testMongoRoot
    javaSocketRoot = $javaSocketRoot
    candidateLog = Join-Path $testMongoRoot 'candidate.out.log'
    candidateErrorLog = Join-Path $testMongoRoot 'candidate.err.log'
    databaseName = $null
    ready = $false
}

function Save-CandidateState {
    $state | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding utf8
}

try {
    $mongoProcess = Start-Process -FilePath $mongodExe -WindowStyle Hidden -PassThru -ArgumentList @(
        '--dbpath', "`"$databasePath`"", '--port', [string]$mongoPort, '--bind_ip', '127.0.0.1', '--quiet',
        '--logpath', "`"$(Join-Path $testMongoRoot 'mongod.log')`"")
    $state.mongoPid = $mongoProcess.Id
    Save-CandidateState

    $isMongoReady = $false
    for ($attempt = 0; $attempt -lt 60 -and -not $isMongoReady; $attempt++) {
        & $mongoshExe '--quiet' '--norc' "mongodb://127.0.0.1:$mongoPort/admin" `
            '--eval' 'quit(db.runCommand({ping:1}).ok===1?0:1)' 2>$null | Out-Null
        $isMongoReady = $LASTEXITCODE -eq 0
        if (-not $isMongoReady) {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $isMongoReady) {
        throw 'The disposable MongoDB did not become ready.'
    }
    $mongoListener = Get-NetTCPConnection -State Listen -LocalPort $mongoPort | Select-Object -First 1
    if ($mongoListener.OwningProcess -ne $mongoProcess.Id) {
        throw 'The disposable MongoDB listener is not owned by the launched process.'
    }

    $candidateEnvironment = @{
        SPRING_PROFILES_ACTIVE = 'test'
        SPRING_MONGODB_URI = "mongodb://127.0.0.1:$mongoPort/test"
        APP_JWT_SECRET = New-CandidateSecret
        JAVA_TOOL_OPTIONS = Get-JdkSocketToolOptions -ExistingOptions '' -SocketDirectory $javaSocketRoot
        APP_FEDERATION_DISCOVERYENABLED = $null
        APP_FEDERATION_KEYENCRYPTIONSECRET = $null
    }
    if ($EnableFederationDiscovery) {
        $candidateEnvironment.APP_FEDERATION_DISCOVERYENABLED = 'true'
        $candidateEnvironment.APP_FEDERATION_KEYENCRYPTIONSECRET = New-CandidateSecret
    }
    $candidate = Invoke-WithEnvironment -Variables $candidateEnvironment -Action {
        Start-Process -FilePath $javaExe -WorkingDirectory $checkoutPath -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $state.candidateLog -RedirectStandardError $state.candidateErrorLog `
            -ArgumentList @('-jar', "`"$candidateJar`"", '--server.address=127.0.0.1', "--server.port=$candidatePort")
    }
    $state.candidatePid = $candidate.Id
    Save-CandidateState

    $deadline = (Get-Date).AddSeconds($ReadinessTimeoutSeconds)
    while (-not $state.ready -and -not $candidate.HasExited -and (Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$($state.baseUrl)/actuator/health/readiness" -TimeoutSec 2
            $state.ready = $health.status -eq 'UP'
        } catch [System.Net.Http.HttpRequestException] {
            # Not listening yet, or answering 503 while starting.
        } catch [System.Threading.Tasks.TaskCanceledException], [System.TimeoutException] {
            # Listening but slower than the two-second probe while starting.
        }
        if (-not $state.ready) {
            Start-Sleep -Seconds 1
        }
    }
    $state.databaseName = (& $mongoshExe '--quiet' '--norc' "mongodb://127.0.0.1:$mongoPort/test" '--eval' 'db.getName()')
} finally {
    Save-CandidateState
}

Get-Content -LiteralPath $StateFile
if (-not $state.ready) {
    Get-Content -LiteralPath $state.candidateLog -Tail 40 -ErrorAction SilentlyContinue
    throw "The candidate did not become ready; stop it with .\ops\local\Stop-Candidate.ps1 -StateFile $StateFile."
}
