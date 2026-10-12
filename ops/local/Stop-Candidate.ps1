#Requires -Version 7
<#
.SYNOPSIS
Stops a candidate started by Start-Candidate.ps1 and removes only what that script created.

.DESCRIPTION
Stops the recorded candidate and MongoDB process trees, reports whether their ports still have
listeners, optionally copies the candidate logs, then removes the disposable MongoDB and JDK
socket directories after proving each is one this tooling generated.

.EXAMPLE
.\ops\local\Stop-Candidate.ps1 -StateFile $env:TEMP\candidate.json -LogDestination $env:TEMP\evidence
#>
param(
    [Parameter(Mandatory)][string]$StateFile,
    [string]$LogDestination
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CandidateRuntime.psm1') -Force

$state = Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
foreach ($ownedPid in @($state.candidatePid, $state.mongoPid) | Where-Object { $_ }) {
    if (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue) {
        taskkill /PID $ownedPid /T /F | Out-Null
    }
}
Start-Sleep -Seconds 2
foreach ($port in @($state.candidatePort, $state.mongoPort)) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    "port $port listeners: $($listeners.Count)"
}

if ($LogDestination) {
    New-Item -ItemType Directory -Path $LogDestination -Force | Out-Null
    foreach ($log in @($state.candidateLog, $state.candidateErrorLog)) {
        if (Test-Path -LiteralPath $log) {
            Copy-Item -LiteralPath $log -Destination $LogDestination
        }
    }
}

$ownedDirectories = @(
    @{ Path = $state.testMongoRoot; Parent = [IO.Path]::GetTempPath(); Prefix = 'christopherbell-test-mongo-' },
    @{ Path = $state.javaSocketRoot; Parent = (Join-Path $env:SystemDrive 'Temp'); Prefix = 'cbell-jdk-' })
foreach ($directory in $ownedDirectories) {
    if (-not (Test-OwnedTemporaryDirectory @directory)) {
        "kept $($directory.Path): not a directory this tooling created"
        continue
    }
    if (Test-Path -LiteralPath $directory.Path) {
        Remove-Item -LiteralPath $directory.Path -Recurse -Force
    }
    "removed $($directory.Path)"
}
