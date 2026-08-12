[CmdletBinding()]
param(
    [string]$MongodExe = 'C:\Program Files\MongoDB\Server\8.3\bin\mongod.exe',
    [string]$MongoshExe = 'mongosh.exe',
    [string]$MongoToolsPath = 'C:\Program Files\MongoDB\Tools\100\bin'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-DisposableMongoPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

if (-not (Test-Path -LiteralPath $MongodExe -PathType Leaf)) {
    throw 'Disposable Mongo requires an explicit mongod executable.'
}
foreach ($tool in 'mongodump.exe','mongorestore.exe') {
    if (-not (Test-Path -LiteralPath (Join-Path $MongoToolsPath $tool) -PathType Leaf)) {
        throw "Disposable Mongo requires $tool."
    }
}
$deployModule = Import-Module `
    (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') `
    -Force `
    -PassThru
$temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$ownedName = 'cbell-domain-task7-' + [guid]::NewGuid().ToString('N')
$ownedRoot = Join-Path $temporaryParent $ownedName
$databasePath = Join-Path $ownedRoot 'db'
$ownerPath = Join-Path $ownedRoot 'owner.json'
$process = $null
$owner = $null
New-Item -ItemType Directory -Path $databasePath -Force | Out-Null
try {
    $port = New-DisposableMongoPort
    if ($port -in 27017,8080,8081 -or $port -lt 1000) {
        throw 'Disposable Mongo selected a forbidden production port.'
    }
    & $deployModule { param($Port) Assert-ProductionCandidatePortUnused -Port $Port } $port
    $process = Start-Process `
        -FilePath $MongodExe `
        -ArgumentList @(
            '--dbpath',$databasePath,'--port',[string]$port,
            '--bind_ip','127.0.0.1','--quiet',
            '--logpath',(Join-Path $ownedRoot 'mongod.log')) `
        -PassThru `
        -WindowStyle Hidden
    $process.Refresh()
    $owner = [pscustomobject][ordered]@{
        version = 1
        pid = [int]$process.Id
        startTimeUtcTicks = [long]$process.StartTime.ToUniversalTime().Ticks
        port = [int]$port
        root = $ownedRoot
    }
    $owner | ConvertTo-Json | Set-Content -LiteralPath $ownerPath -Encoding utf8
    $candidateIdentity = & $deployModule {
        param($Process)
        Get-ProductionCandidateProcessIdentity -Process $Process
    } $process

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & $MongoshExe '--quiet' '--norc' "mongodb://127.0.0.1:$port/admin" `
            '--eval' 'quit(db.runCommand({ping:1}).ok===1?0:1)' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Disposable Mongo did not become ready.' }
    & $deployModule {
        param($Port,$Identity)
        Assert-ProductionCandidateProcessOwnsListener -Port $Port -Identity $Identity
    } $port $candidateIdentity

    $env:DOMAIN_COLLECTION_MIGRATION_TEST_URI =
        "mongodb://127.0.0.1:$port/admin"
    $env:DOMAIN_COLLECTION_MIGRATION_TEST_MONGO_TOOLS = $MongoToolsPath
    $result = Invoke-Pester `
        -Path (Join-Path $PSScriptRoot 'Production.DomainCollections.Tests.ps1') `
        -Output Normal `
        -PassThru
    if ($result.FailedCount -ne 0 -or $result.SkippedCount -ne 0) {
        throw 'Disposable domain collection Mongo tests did not all pass.'
    }
    $current = Get-Process -Id $owner.pid -ErrorAction Stop
    if ($current.StartTime.ToUniversalTime().Ticks -ne $owner.startTimeUtcTicks) {
        throw 'Disposable Mongo process identity changed during the test.'
    }
} finally {
    Remove-Item Env:DOMAIN_COLLECTION_MIGRATION_TEST_URI -ErrorAction SilentlyContinue
    Remove-Item Env:DOMAIN_COLLECTION_MIGRATION_TEST_MONGO_TOOLS -ErrorAction SilentlyContinue
    if ($owner) {
        $current = Get-Process -Id $owner.pid -ErrorAction SilentlyContinue
        if ($current -and
            $current.StartTime.ToUniversalTime().Ticks -eq $owner.startTimeUtcTicks) {
            Stop-Process -Id $owner.pid -Force
            $current.WaitForExit(10000) | Out-Null
        }
    }
    $resolved = [IO.Path]::GetFullPath($ownedRoot)
    if (-not [string]::Equals(
            [IO.Path]::GetFullPath((Split-Path -Parent $resolved)),
            $temporaryParent,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -cnotmatch
            '^cbell-domain-task7-[0-9a-f]{32}$') {
        throw 'Disposable Mongo cleanup root identity is invalid.'
    }
    if (Test-Path -LiteralPath $resolved) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    if (Test-Path -LiteralPath $resolved) {
        throw 'Disposable Mongo cleanup left residue.'
    }
}
