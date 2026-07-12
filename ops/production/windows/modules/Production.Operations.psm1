Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Production.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Production.Deploy.psm1') -Force

function Get-ProductionStatus {
    $config = Read-ProductionConfig
    $website = Get-Service ChristopherBellDev -ErrorAction SilentlyContinue
    $mongo = Get-Service MongoDB -ErrorAction SilentlyContinue
    $cloudflared = Get-Service cloudflared -ErrorAction SilentlyContinue
    [pscustomobject]@{
        WebsiteService = if ($website) { $website.Status } else { 'NotInstalled' }
        MongoService = if ($mongo) { $mongo.Status } else { 'NotInstalled' }
        CloudflaredService = if ($cloudflared) { $cloudflared.Status } else { 'NotInstalled' }
        CurrentRelease = Get-JunctionTarget (Join-Path $config.programDataRoot 'current')
        PreviousRelease = Get-JunctionTarget (Join-Path $config.programDataRoot 'previous')
        ProductionPortPid = (Get-NetTCPConnection -LocalPort $config.productionPort -State Listen -ErrorAction SilentlyContinue).OwningProcess
    }
}

function Invoke-ProductionRollback {
    [CmdletBinding()]
    param([switch]$WhatIf)
    $config = Read-ProductionConfig
    $lock = Enter-DeploymentLock (Join-Path $config.programDataRoot 'locks\deploy.lock')
    try {
        $current = Get-JunctionTarget (Join-Path $config.programDataRoot 'current')
        $previous = Get-JunctionTarget (Join-Path $config.programDataRoot 'previous')
        if (-not $current -or -not $previous) { throw 'Both current and previous releases are required.' }
        Assert-ReleasePath $config $current | Out-Null
        Assert-ReleasePath $config $previous | Out-Null
        if ($WhatIf) { Write-Output "Would roll back from $current to $previous"; return }
        Stop-Service ChristopherBellDev
        Set-AtomicJunction $config (Join-Path $config.programDataRoot 'current') $previous
        Set-AtomicJunction $config (Join-Path $config.programDataRoot 'previous') $current
        try {
            Start-Service ChristopherBellDev
            Test-ProductionEndpoints $config $config.productionPort
        } catch {
            Stop-Service ChristopherBellDev -ErrorAction SilentlyContinue
            Set-AtomicJunction $config (Join-Path $config.programDataRoot 'current') $current
            Set-AtomicJunction $config (Join-Path $config.programDataRoot 'previous') $previous
            Start-Service ChristopherBellDev
            Test-ProductionEndpoints $config $config.productionPort
            throw
        }
    } finally { $lock.Dispose() }
}

function Get-NativeMongoDumpArguments {
    param([Parameter(Mandatory)][string]$Archive)
    @('--uri=mongodb://127.0.0.1:27017','--db=christopherbell',"--archive=$Archive",'--gzip')
}

function Get-NativeMongoRestoreDryRunArguments {
    param([Parameter(Mandatory)][string]$Archive)
    @('--uri=mongodb://127.0.0.1:27017',"--archive=$Archive",'--gzip','--dryRun')
}

function New-ProductionBackup {
    $config = Read-ProductionConfig
    New-Item -ItemType Directory -Force $config.backupRoot | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $archive = Join-Path $config.backupRoot "christopherbell-native-$stamp.archive.gz"
    Invoke-CheckedProcess (Join-Path $config.mongoToolsPath 'mongodump.exe') (Get-NativeMongoDumpArguments $archive) $config.repositoryPath | Out-Null
    if (-not (Test-Path $archive) -or (Get-Item $archive).Length -eq 0) { throw 'mongodump failed or created an empty archive.' }
    Invoke-CheckedProcess (Join-Path $config.mongoToolsPath 'mongorestore.exe') (Get-NativeMongoRestoreDryRunArguments $archive) $config.repositoryPath | Out-Null
    [ordered]@{ archive=$archive; sha256=(Get-FileHash $archive -Algorithm SHA256).Hash; createdAt=(Get-Date).ToUniversalTime().ToString('o') } |
        ConvertTo-Json | Set-Content "$archive.sha256.json" -Encoding utf8
    return $archive
}

function Watch-ProductionLogs {
    $config = Read-ProductionConfig
    $log = Get-ChildItem (Join-Path $config.programDataRoot 'logs') -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $log) { throw 'No production log file exists.' }
    Get-Content -LiteralPath $log.FullName -Tail 100 -Wait
}

function Restart-ProductionService {
    [CmdletBinding()]
    param([switch]$Verify)
    $config = Read-ProductionConfig
    Restart-Service ChristopherBellDev
    if ($Verify) { Test-ProductionEndpoints $config $config.productionPort }
}

function Get-ProductionReleases {
    $config = Read-ProductionConfig
    $current = Get-JunctionTarget (Join-Path $config.programDataRoot 'current')
    $previous = Get-JunctionTarget (Join-Path $config.programDataRoot 'previous')
    Get-ChildItem (Join-Path $config.programDataRoot 'releases') -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | ForEach-Object {
            [pscustomobject]@{ Sha=$_.Name; Path=$_.FullName; Current=($_.FullName -eq $current); Previous=($_.FullName -eq $previous); BuiltAt=$_.LastWriteTimeUtc }
        }
}

function Test-ProductionStartup {
    $config = Read-ProductionConfig
    foreach ($name in 'MongoDB','ChristopherBellDev','cloudflared') {
        $service = Get-Service $name -ErrorAction Stop
        if ([string]$service.Status -ne 'Running') { throw "$name must be Running." }
        if ([string]$service.StartType -ne 'Automatic') { throw "$name must use Automatic startup." }
    }
    $task = Get-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction Stop
    if ([string]$task.State -eq 'Disabled') { throw 'ChristopherBellAutoDeploy must be enabled.' }
    Test-ProductionEndpoints $config $config.productionPort
    Wait-HttpStatus -Uri $config.publicUrl -ExpectedStatus 200 -Timeout ([timespan]::FromSeconds(30)) | Out-Null
    [pscustomobject]@{
        Services = 'RunningAutomatic'
        AutoDeployTask = $task.State
        NativeEndpoint = 200
        PublicEndpoint = 200
    }
}

Export-ModuleMember -Function Get-ProductionStatus,Invoke-ProductionRollback,Get-NativeMongoDumpArguments,Get-NativeMongoRestoreDryRunArguments,New-ProductionBackup,Watch-ProductionLogs,Restart-ProductionService,Get-ProductionReleases,Test-ProductionStartup
