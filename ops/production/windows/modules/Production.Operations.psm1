Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
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
        $currentPath = Join-Path $config.programDataRoot 'current'
        $previousPath = Join-Path $config.programDataRoot 'previous'
        $current = Get-JunctionTarget $currentPath
        $previous = Get-JunctionTarget $previousPath
        if (-not $current -or -not $previous) {
            throw 'Both current and previous releases are required.'
        }
        Assert-ReleasePath $config $current | Out-Null
        Assert-ReleasePath $config $previous | Out-Null
        if ($WhatIf) {
            Write-Output "Would roll back from $current to $previous"
            return
        }

        Stop-ProductionWebsiteService -ProductionPort $config.productionPort
        try {
            Set-AtomicJunction $config $currentPath $previous
            Set-AtomicJunction $config $previousPath $current
            Start-Service ChristopherBellDev
            Test-ProductionEndpoints $config $config.productionPort
        } catch {
            $rollbackFailure = $_.Exception
            try {
                Stop-ProductionWebsiteService -ProductionPort $config.productionPort
                $junctionRestoreFailures = [System.Collections.Generic.List[System.Exception]]::new()
                try {
                    Set-AtomicJunction $config $currentPath $current
                } catch {
                    [void]$junctionRestoreFailures.Add($_.Exception)
                }
                try {
                    Set-AtomicJunction $config $previousPath $previous
                } catch {
                    [void]$junctionRestoreFailures.Add($_.Exception)
                }
                if ($junctionRestoreFailures.Count -eq 1) {
                    throw [System.InvalidOperationException]::new(
                        'Failed to restore original release junctions.',
                        $junctionRestoreFailures[0])
                }
                if ($junctionRestoreFailures.Count -gt 1) {
                    throw [System.AggregateException]::new(
                        'Failed to restore original release junctions.',
                        [System.Exception[]]$junctionRestoreFailures.ToArray())
                }
                Start-Service ChristopherBellDev
                Test-ProductionEndpoints $config $config.productionPort
            } catch {
                throw [System.AggregateException]::new(
                    'Production rollback and release restoration both failed.',
                    [System.Exception[]]@($rollbackFailure, $_.Exception))
            }
            throw $rollbackFailure
        }
    } finally {
        $lock.Dispose()
    }
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

function Assert-AutoDeployTaskContract {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Task, [Parameter(Mandatory)]$Config)
    if ([int]$Config.autoDeployPollSeconds -ne 60) {
        throw 'Automatic deployment polling must run every 60 seconds.'
    }
    if ([string]$Task.State -eq 'Disabled' -or -not $Task.Settings.Enabled) {
        throw 'ChristopherBellAutoDeploy must be enabled.'
    }
    if ([string]$Task.Principal.UserId -ne 'SYSTEM' -or
        [string]$Task.Principal.LogonType -ne 'ServiceAccount' -or
        [string]$Task.Principal.RunLevel -ne 'Highest') {
        throw 'ChristopherBellAutoDeploy must run as SYSTEM with ServiceAccount logon and Highest privileges.'
    }
    $triggers = @($Task.Triggers)
    $startupTrigger = @($triggers | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_TaskBootTrigger' })
    $repeatingTrigger = @($triggers | Where-Object {
        $_.CimClass.CimClassName -eq 'MSFT_TaskTimeTrigger' -and
        [string]$_.Repetition.Interval -eq 'PT1M'
    })
    if ($triggers.Count -ne 2 -or $startupTrigger.Count -ne 1 -or $repeatingTrigger.Count -ne 1) {
        throw 'ChristopherBellAutoDeploy must have startup and one-minute repeating triggers.'
    }
    if (-not $startupTrigger[0].Enabled) {
        throw 'ChristopherBellAutoDeploy must have an enabled startup trigger.'
    }
    if (-not $repeatingTrigger[0].Enabled) {
        throw 'ChristopherBellAutoDeploy must have an enabled one-minute repeating trigger.'
    }
    $actions = @($Task.Actions)
    if ($actions.Count -ne 1) { throw 'ChristopherBellAutoDeploy must have exactly one action.' }
    $expectedPowerShell = Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'
    if (-not [string]::Equals([string]$actions[0].Execute, $expectedPowerShell, [StringComparison]::OrdinalIgnoreCase)) {
        throw "ChristopherBellAutoDeploy must use the PowerShell 7 executable at $expectedPowerShell."
    }
    $expectedArguments = "-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden " +
        "-ExecutionPolicy Bypass -File `"$($Config.programDataRoot)\tools\prod.ps1`" auto-deploy"
    if ([string]$actions[0].Arguments -ne $expectedArguments) {
        throw 'ChristopherBellAutoDeploy must run the installed production auto-deploy command hidden and noninteractive.'
    }
    if (-not $Task.Settings.Hidden -or -not $Task.Settings.StartWhenAvailable -or
        $Task.Settings.DisallowStartIfOnBatteries -or $Task.Settings.StopIfGoingOnBatteries) {
        throw 'ChristopherBellAutoDeploy must remain hidden and available without interactive power-state prompts.'
    }
    if ([string]$Task.Settings.ExecutionTimeLimit -ne 'PT2H') {
        throw 'ChristopherBellAutoDeploy must bound each deployment check to two hours.'
    }
    if ([int]$Task.Settings.RestartCount -lt 3 -or [string]$Task.Settings.RestartInterval -ne 'PT1M') {
        throw 'ChristopherBellAutoDeploy must restart at least three times at one-minute intervals.'
    }
    if ([string]$Task.Settings.MultipleInstances -ne 'IgnoreNew') {
        throw 'ChristopherBellAutoDeploy must ignore overlapping task starts.'
    }
}

function Get-ProductionMongoCollectionInventoryScript {
    @'
const target = db.getSiblingDB('christopherbell');
const has = (value, key) => Object.prototype.hasOwnProperty.call(value || {}, key);
const numberOrNull = (value) => typeof value === 'number' ? value : null;
const safeOptions = (options) => {
  const result = {};
  for (const key of ['capped', 'size', 'max', 'validator', 'validationLevel',
                     'validationAction', 'collation']) {
    if (has(options, key)) {
      result[key] = options[key];
    }
  }
  return result;
};
const safeIndex = (index) => ({
  name: index.name,
  key: index.key,
  unique: index.unique === true,
  sparse: index.sparse === true,
  expireAfterSeconds: has(index, 'expireAfterSeconds') ? index.expireAfterSeconds : null,
  partialFilterExpression: has(index, 'partialFilterExpression')
      ? index.partialFilterExpression
      : null
});
const collections = target.getCollectionInfos()
    .filter((info) => !info.name.startsWith('system.'))
    .sort((left, right) => left.name === right.name ? 0 : left.name < right.name ? -1 : 1)
    .map((info) => {
      const stats = info.type === 'view'
          ? { ok: 1, count: null, size: null, storageSize: null, totalIndexSize: null }
          : target.runCommand({ collStats: info.name });
      if (stats.ok !== 1) {
        throw new Error(`collStats failed for ${info.name}`);
      }
      const indexes = info.type === 'view'
          ? []
          : target.getCollection(info.name).getIndexes()
              .map(safeIndex)
              .sort((left, right) => left.name === right.name ? 0 : left.name < right.name ? -1 : 1);
      return {
        name: info.name,
        type: info.type,
        options: safeOptions(info.options),
        count: numberOrNull(stats.count),
        sizeBytes: numberOrNull(stats.size),
        storageSizeBytes: numberOrNull(stats.storageSize),
        totalIndexSizeBytes: numberOrNull(stats.totalIndexSize),
        indexes
      };
    });
print(JSON.stringify({
  complete: true,
  database: target.getName(),
  generatedAt: new Date().toISOString(),
  collections
}));
'@
}

function ConvertFrom-ProductionMongoCollectionInventory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Json)

    try {
        $inventory = $Json | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw [IO.InvalidDataException]::new(
            'MongoDB collection inventory did not return valid JSON.',
            $_.Exception)
    }
    if ($inventory.PSObject.Properties.Name -notcontains 'complete' -or
        $inventory.complete -ne $true) {
        throw 'MongoDB collection inventory is not complete.'
    }
    if ([string]$inventory.database -ne 'christopherbell') {
        throw 'MongoDB collection inventory must target christopherbell.'
    }
    $generatedAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse(
        [string]$inventory.generatedAt,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal,
        [ref]$generatedAt)) {
        throw 'MongoDB collection inventory generatedAt is invalid.'
    }
    if ($inventory.PSObject.Properties.Name -notcontains 'collections') {
        throw 'MongoDB collection inventory collections are missing.'
    }
    $names = [Collections.Generic.List[string]]::new()
    $uniqueNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($collection in @($inventory.collections)) {
        if ([string]::IsNullOrWhiteSpace([string]$collection.name) -or
            [string]$collection.type -notin @('collection','view')) {
            throw 'MongoDB collection inventory contains an invalid collection.'
        }
        if ([string]$collection.name -like 'system.*') {
            throw 'MongoDB collection inventory must exclude system collections.'
        }
        foreach ($property in 'options','count','sizeBytes','storageSizeBytes',
            'totalIndexSizeBytes','indexes') {
            if ($collection.PSObject.Properties.Name -notcontains $property) {
                throw "MongoDB collection inventory is missing collection property: $property"
            }
        }
        $name = [string]$collection.name
        if (-not $uniqueNames.Add($name)) {
            throw 'MongoDB collection inventory names must be unique.'
        }
        [void]$names.Add($name)
        foreach ($index in @($collection.indexes)) {
            $indexProperties = @(
                'name','key','unique','sparse','expireAfterSeconds','partialFilterExpression')
            if (@($indexProperties | Where-Object {
                    $index.PSObject.Properties.Name -notcontains $_
                }).Count -gt 0 -or
                [string]::IsNullOrWhiteSpace([string]$index.name) -or $null -eq $index.key) {
                throw 'MongoDB collection inventory contains an invalid index.'
            }
        }
    }
    [string[]]$sortedNames = $names.ToArray()
    [Array]::Sort($sortedNames, [StringComparer]::Ordinal)
    if ([string]::Join([char]0, $names.ToArray()) -cne
        [string]::Join([char]0, $sortedNames)) {
        throw 'MongoDB collection inventory names must be sorted.'
    }
    return $inventory
}

function Get-ProductionMongoCollectionInventory {
    $config = Read-ProductionConfig
    $json = Invoke-CheckedProcess `
        -FilePath $config.mongoShellExe `
        -ArgumentList @(
            '--quiet'
            'mongodb://127.0.0.1:27017/admin'
            '--eval'
            (Get-ProductionMongoCollectionInventoryScript)
        ) `
        -WorkingDirectory $config.repositoryPath
    ConvertFrom-ProductionMongoCollectionInventory -Json $json
}

function Test-ProductionStartup {
    $config = Read-ProductionConfig
    foreach ($name in 'MongoDB','ChristopherBellDev','cloudflared') {
        $service = Get-Service $name -ErrorAction Stop
        if ([string]$service.Status -ne 'Running') { throw "$name must be Running." }
        if ([string]$service.StartType -ne 'Automatic') { throw "$name must use Automatic startup." }
    }
    if ($config.PSObject.Properties.Name -notcontains 'sensorLibrariesEnabled') {
        throw 'deploy.json must declare sensorLibrariesEnabled.'
    }
    $cpuTemperature = if ([bool]$config.sensorLibrariesEnabled) {
        Assert-ProductionSensorReady -Root $config.programDataRoot
    } else { $null }
    $task = Get-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction Stop
    Assert-AutoDeployTaskContract -Task $task -Config $config
    Test-ProductionEndpoints $config $config.productionPort
    $publicRouteChecks = Test-ProductionPublicEndpoints -Config $config
    [pscustomobject]@{
        Services = 'RunningAutomatic'
        AutoDeployTask = $task.State
        NativeEndpoint = 200
        PublicEndpoint = 200
        PublicRouteChecks = $publicRouteChecks
        SensorLibrariesEnabled = [bool]$config.sensorLibrariesEnabled
        CpuTemperatureCelsius = $cpuTemperature
    }
}

Export-ModuleMember -Function Get-ProductionStatus,Invoke-ProductionRollback,Watch-ProductionLogs,Restart-ProductionService,Get-ProductionReleases,Assert-AutoDeployTaskContract,Get-ProductionMongoCollectionInventory,Test-ProductionStartup
