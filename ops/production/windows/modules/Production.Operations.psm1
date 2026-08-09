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
const numberOrNull = (value) => {
  const number = typeof value === 'number'
      ? value
      : value !== null && typeof value === 'object' && typeof value.toNumber === 'function'
          ? value.toNumber()
          : NaN;
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
};
const safeMetadataInteger = (value, field) => {
  const number = numberOrNull(value);
  if (number === null) {
    throw new Error(`invalid numeric metadata for ${field}`);
  }
  return number;
};
const redacted = '[redacted]';
const redactMetadataLiterals = (value) => {
  if (Array.isArray(value)) {
    return value.map(redactMetadataLiterals);
  }
  if (value !== null && typeof value === 'object') {
    const result = {};
    for (const [key, nested] of Object.entries(value)) {
      result[key] = redactMetadataLiterals(nested);
    }
    return result;
  }
  return redacted;
};
const safeOptions = (options) => {
  const result = {};
  for (const key of ['capped', 'size', 'max', 'validator', 'validationLevel',
                     'validationAction', 'collation', 'timeseries',
                     'expireAfterSeconds']) {
    if (has(options, key)) {
      result[key] = key === 'validator'
          ? redactMetadataLiterals(options[key])
          : key === 'expireAfterSeconds'
              ? safeMetadataInteger(options[key], key)
              : options[key];
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
      ? redactMetadataLiterals(index.partialFilterExpression)
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
        count: info.type === 'timeseries' ? null : numberOrNull(stats.count),
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

function Test-ProductionMongoCollectionInventoryNumber {
    param([object]$Value)

    return $Value -is [byte] -or $Value -is [sbyte] -or
        $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64] -or
        $Value -is [single] -or $Value -is [double] -or
        $Value -is [decimal]
}

function Assert-ProductionMongoCollectionInventoryObject {
    param([object]$Value, [string]$Path)

    if ($null -eq $Value -or $Value -isnot [pscustomobject]) {
        throw "MongoDB collection inventory $Path is invalid."
    }
}

function Assert-ProductionMongoCollectionInventoryPropertySet {
    param(
        [object]$Value,
        [string]$Path,
        [string[]]$Allowed,
        [string[]]$Required
    )

    Assert-ProductionMongoCollectionInventoryObject -Value $Value -Path $Path
    $names = @($Value.PSObject.Properties | ForEach-Object Name)
    foreach ($name in $names) {
        if ($Allowed -notcontains $name) {
            throw "MongoDB collection inventory $Path contains an unknown property: $name"
        }
    }
    foreach ($name in $Required) {
        if ($names -notcontains $name) {
            throw "MongoDB collection inventory $Path is missing property: $name"
        }
    }
}

function Copy-ProductionMongoCollectionInventoryMetadataValue {
    param([object]$Value, [string]$Path)

    if ($null -eq $Value -or $Value -is [bool] -or $Value -is [string] -or
        (Test-ProductionMongoCollectionInventoryNumber $Value)) {
        return $Value
    }
    if ($Value -is [Array]) {
        $result = [Collections.Generic.List[object]]::new()
        for ($index = 0; $index -lt $Value.Count; $index++) {
            [void]$result.Add((Copy-ProductionMongoCollectionInventoryMetadataValue `
                -Value $Value[$index] -Path "$Path[$index]"))
        }
        Write-Output -NoEnumerate $result.ToArray()
        return
    }
    Assert-ProductionMongoCollectionInventoryObject -Value $Value -Path $Path
    $result = [ordered]@{}
    foreach ($property in @($Value.PSObject.Properties)) {
        $result[$property.Name] = Copy-ProductionMongoCollectionInventoryMetadataValue `
            -Value $property.Value -Path "$Path.$($property.Name)"
    }
    return [pscustomobject]$result
}

function Protect-ProductionMongoCollectionInventoryMetadataLiterals {
    param([object]$Value, [string]$Path)

    if ($Value -is [Array]) {
        $result = [Collections.Generic.List[object]]::new()
        for ($index = 0; $index -lt $Value.Count; $index++) {
            [void]$result.Add((Protect-ProductionMongoCollectionInventoryMetadataLiterals `
                -Value $Value[$index] -Path "$Path[$index]"))
        }
        Write-Output -NoEnumerate $result.ToArray()
        return
    }
    if ($null -ne $Value -and $Value -is [pscustomobject]) {
        $result = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties)) {
            $result[$property.Name] = Protect-ProductionMongoCollectionInventoryMetadataLiterals `
                -Value $property.Value -Path "$Path.$($property.Name)"
        }
        return [pscustomobject]$result
    }
    if ($null -ne $Value -and $Value -isnot [bool] -and $Value -isnot [string] -and
        -not (Test-ProductionMongoCollectionInventoryNumber $Value)) {
        throw "MongoDB collection inventory $Path is invalid."
    }
    return '[redacted]'
}

function Convert-ProductionMongoCollectionInventoryNumber {
    param([object]$Value, [string]$Path, [bool]$AllowNull)

    if ($null -eq $Value) {
        if ($AllowNull) {
            return $null
        }
        throw "MongoDB collection inventory $Path is invalid."
    }
    if (-not (Test-ProductionMongoCollectionInventoryNumber $Value) -or
        [double]$Value -lt 0 -or [double]::IsInfinity([double]$Value) -or
        [double]::IsNaN([double]$Value)) {
        throw "MongoDB collection inventory $Path is invalid."
    }
    return $Value
}

function Convert-ProductionMongoCollectionInventoryTimeSeriesOptions {
    param([object]$Value)

    Assert-ProductionMongoCollectionInventoryPropertySet -Value $Value -Path 'options.timeseries' `
        -Allowed @('timeField','metaField','granularity','bucketMaxSpanSeconds','bucketRoundingSeconds') `
        -Required @('timeField')
    if ($Value.timeField -isnot [string] -or [string]::IsNullOrWhiteSpace($Value.timeField)) {
        throw 'MongoDB collection inventory options.timeseries.timeField is invalid.'
    }
    $result = [ordered]@{ timeField = $Value.timeField }
    $names = @($Value.PSObject.Properties | ForEach-Object Name)
    if ($names -contains 'metaField') {
        if ($Value.metaField -isnot [string] -or [string]::IsNullOrWhiteSpace($Value.metaField) -or
            $Value.metaField -eq $Value.timeField) {
            throw 'MongoDB collection inventory options.timeseries.metaField is invalid.'
        }
        $result['metaField'] = $Value.metaField
    }
    if ($names -contains 'granularity') {
        if ($Value.granularity -isnot [string] -or
            $Value.granularity -notin @('seconds','minutes','hours')) {
            throw 'MongoDB collection inventory options.timeseries.granularity is invalid.'
        }
        $result['granularity'] = $Value.granularity
    }
    foreach ($option in 'bucketMaxSpanSeconds','bucketRoundingSeconds') {
        if ($names -contains $option) {
            $number = Convert-ProductionMongoCollectionInventoryNumber `
                $Value.PSObject.Properties[$option].Value "options.timeseries.$option" $false
            if ([double]$number -lt 1 -or [double]$number -gt 31536000) {
                throw "MongoDB collection inventory options.timeseries.$option is invalid."
            }
            $result[$option] = $number
        }
    }
    if ($names -contains 'bucketRoundingSeconds' -and
        $names -contains 'bucketMaxSpanSeconds' -and
        [double]$result.bucketRoundingSeconds -ne [double]$result.bucketMaxSpanSeconds) {
        throw 'MongoDB collection inventory time-series bucket intervals must match.'
    }
    return [pscustomobject]$result
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
    Assert-ProductionMongoCollectionInventoryPropertySet -Value $inventory -Path 'root' `
        -Allowed @('complete','database','generatedAt','collections') `
        -Required @('complete','database','generatedAt','collections')
    if ($inventory.complete -isnot [bool]) {
        throw 'MongoDB collection inventory complete is invalid.'
    }
    if ($inventory.complete -ne $true) {
        throw 'MongoDB collection inventory is not complete.'
    }
    if ($inventory.database -isnot [string] -or
        $inventory.database -ne 'christopherbell') {
        throw 'MongoDB collection inventory must target christopherbell.'
    }
    $generatedAt = [DateTimeOffset]::MinValue
    if ($inventory.generatedAt -is [DateTimeOffset]) {
        $generatedAt = [DateTimeOffset]$inventory.generatedAt
    } elseif ($inventory.generatedAt -is [DateTime]) {
        $generatedAt = [DateTimeOffset]$inventory.generatedAt
    } elseif ($inventory.generatedAt -is [string] -and
        [DateTimeOffset]::TryParse(
            $inventory.generatedAt,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AssumeUniversal,
            [ref]$generatedAt)) {
    } else {
        throw 'MongoDB collection inventory generatedAt is invalid.'
    }
    if ($inventory.collections -isnot [Array]) {
        throw 'MongoDB collection inventory collections are invalid.'
    }
    $names = [Collections.Generic.List[string]]::new()
    $uniqueNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $canonicalCollections = [Collections.Generic.List[object]]::new()
    foreach ($collection in $inventory.collections) {
        Assert-ProductionMongoCollectionInventoryPropertySet -Value $collection -Path 'collection' `
            -Allowed @('name','type','options','count','sizeBytes','storageSizeBytes',
                'totalIndexSizeBytes','indexes') `
            -Required @('name','type','options','count','sizeBytes','storageSizeBytes',
                'totalIndexSizeBytes','indexes')
        if ($collection.name -isnot [string] -or
            [string]::IsNullOrWhiteSpace($collection.name) -or
            $collection.type -isnot [string] -or
            $collection.type -notin @('collection','view','timeseries')) {
            throw 'MongoDB collection inventory contains an invalid collection.'
        }
        if ($collection.name -like 'system.*') {
            throw 'MongoDB collection inventory must exclude system collections.'
        }
        Assert-ProductionMongoCollectionInventoryPropertySet -Value $collection.options -Path 'options' `
            -Allowed @('capped','size','max','validator','validationLevel','validationAction','collation',
                'timeseries','expireAfterSeconds') `
            -Required @()
        $optionNames = @($collection.options.PSObject.Properties | ForEach-Object Name)
        if ($collection.type -eq 'timeseries' -and $optionNames -notcontains 'timeseries') {
            throw 'MongoDB collection inventory time-series options are missing.'
        }
        if ($collection.type -ne 'timeseries' -and
            ($optionNames -contains 'timeseries' -or $optionNames -contains 'expireAfterSeconds')) {
            throw 'MongoDB collection inventory time-series options are invalid for this collection type.'
        }
        $canonicalOptions = [ordered]@{}
        foreach ($option in 'capped','size','max','validator','validationLevel','validationAction','collation',
            'timeseries','expireAfterSeconds') {
            if ($optionNames -contains $option) {
                $value = $collection.options.PSObject.Properties[$option].Value
                switch ($option) {
                    'capped' {
                        if ($value -isnot [bool]) { throw 'MongoDB collection inventory options are invalid.' }
                    }
                    'size' { $value = Convert-ProductionMongoCollectionInventoryNumber $value 'options.size' $false }
                    'max' { $value = Convert-ProductionMongoCollectionInventoryNumber $value 'options.max' $false }
                    'validator' {
                        Assert-ProductionMongoCollectionInventoryObject -Value $value -Path 'options.validator'
                        $value = Protect-ProductionMongoCollectionInventoryMetadataLiterals `
                            $value 'options.validator'
                    }
                    'validationLevel' {
                        if ($value -isnot [string]) { throw 'MongoDB collection inventory options are invalid.' }
                    }
                    'validationAction' {
                        if ($value -isnot [string]) { throw 'MongoDB collection inventory options are invalid.' }
                    }
                    'collation' {
                        Assert-ProductionMongoCollectionInventoryObject -Value $value -Path 'options.collation'
                        $value = Copy-ProductionMongoCollectionInventoryMetadataValue $value 'options.collation'
                    }
                    'timeseries' {
                        $value = Convert-ProductionMongoCollectionInventoryTimeSeriesOptions $value
                    }
                    'expireAfterSeconds' {
                        $value = Convert-ProductionMongoCollectionInventoryNumber `
                            $value 'options.expireAfterSeconds' $false
                    }
                }
                $canonicalOptions[$option] = $value
            }
        }
        $allowNullStatistics = $collection.type -eq 'view'
        $allowNullCount = $collection.type -in @('view','timeseries')
        $count = Convert-ProductionMongoCollectionInventoryNumber $collection.count 'collection.count' $allowNullCount
        $sizeBytes = Convert-ProductionMongoCollectionInventoryNumber $collection.sizeBytes 'collection.sizeBytes' $allowNullStatistics
        $storageSizeBytes = Convert-ProductionMongoCollectionInventoryNumber $collection.storageSizeBytes 'collection.storageSizeBytes' $allowNullStatistics
        $totalIndexSizeBytes = Convert-ProductionMongoCollectionInventoryNumber $collection.totalIndexSizeBytes 'collection.totalIndexSizeBytes' $allowNullStatistics
        if ($collection.type -eq 'view' -and
            ($null -ne $count -or $null -ne $sizeBytes -or $null -ne $storageSizeBytes -or
                $null -ne $totalIndexSizeBytes)) {
            throw 'MongoDB collection inventory views must have null statistics and no indexes.'
        }
        if ($collection.type -eq 'timeseries' -and $null -ne $count) {
            throw 'MongoDB collection inventory time-series collections must have a null count.'
        }
        if ($collection.indexes -isnot [Array]) {
            throw 'MongoDB collection inventory indexes are invalid.'
        }
        if ($collection.type -eq 'view' -and $collection.indexes.Count -ne 0) {
            throw 'MongoDB collection inventory views must have null statistics and no indexes.'
        }
        $name = $collection.name
        if (-not $uniqueNames.Add($name)) {
            throw 'MongoDB collection inventory names must be unique.'
        }
        [void]$names.Add($name)
        $indexNames = [Collections.Generic.List[string]]::new()
        $uniqueIndexNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $canonicalIndexes = [Collections.Generic.List[object]]::new()
        foreach ($index in $collection.indexes) {
            Assert-ProductionMongoCollectionInventoryPropertySet -Value $index -Path 'index' `
                -Allowed @('name','key','unique','sparse','expireAfterSeconds','partialFilterExpression') `
                -Required @('name','key','unique','sparse','expireAfterSeconds','partialFilterExpression')
            if ($index.name -isnot [string] -or [string]::IsNullOrWhiteSpace($index.name) -or
                $index.unique -isnot [bool] -or $index.sparse -isnot [bool]) {
                throw 'MongoDB collection inventory contains an invalid index.'
            }
            Assert-ProductionMongoCollectionInventoryObject -Value $index.key -Path 'index.key'
            if (@($index.key.PSObject.Properties).Count -eq 0) {
                throw 'MongoDB collection inventory contains an invalid index.'
            }
            $expireAfterSeconds = Convert-ProductionMongoCollectionInventoryNumber `
                $index.expireAfterSeconds 'index.expireAfterSeconds' $true
            $partialFilterExpression = $null
            if ($null -ne $index.partialFilterExpression) {
                Assert-ProductionMongoCollectionInventoryObject -Value $index.partialFilterExpression `
                    -Path 'index.partialFilterExpression'
                $partialFilterExpression = Copy-ProductionMongoCollectionInventoryMetadataValue `
                    $index.partialFilterExpression 'index.partialFilterExpression'
                $partialFilterExpression = Protect-ProductionMongoCollectionInventoryMetadataLiterals `
                    $partialFilterExpression 'index.partialFilterExpression'
            }
            if (-not $uniqueIndexNames.Add($index.name)) {
                throw 'MongoDB collection inventory index names must be unique.'
            }
            [void]$indexNames.Add($index.name)
            [void]$canonicalIndexes.Add([pscustomobject][ordered]@{
                name = $index.name
                key = Copy-ProductionMongoCollectionInventoryMetadataValue $index.key 'index.key'
                unique = $index.unique
                sparse = $index.sparse
                expireAfterSeconds = $expireAfterSeconds
                partialFilterExpression = $partialFilterExpression
            })
        }
        [string[]]$sortedIndexNames = $indexNames.ToArray()
        [Array]::Sort($sortedIndexNames, [StringComparer]::Ordinal)
        if ([string]::Join([char]0, $indexNames.ToArray()) -cne
            [string]::Join([char]0, $sortedIndexNames)) {
            throw 'MongoDB collection inventory index names must be sorted.'
        }
        [void]$canonicalCollections.Add([pscustomobject][ordered]@{
            name = $name
            type = $collection.type
            options = [pscustomobject]$canonicalOptions
            count = $count
            sizeBytes = $sizeBytes
            storageSizeBytes = $storageSizeBytes
            totalIndexSizeBytes = $totalIndexSizeBytes
            indexes = $canonicalIndexes.ToArray()
        })
    }
    [string[]]$sortedNames = $names.ToArray()
    [Array]::Sort($sortedNames, [StringComparer]::Ordinal)
    if ([string]::Join([char]0, $names.ToArray()) -cne
        [string]::Join([char]0, $sortedNames)) {
        throw 'MongoDB collection inventory names must be sorted.'
    }
    return [pscustomobject][ordered]@{
        complete = $true
        database = 'christopherbell'
        generatedAt = $generatedAt.UtcDateTime.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        collections = $canonicalCollections.ToArray()
    }
}

function Get-ProductionMongoCollectionInventory {
    $config = Read-ProductionConfig
    $json = Invoke-CheckedProcess `
        -FilePath $config.mongoShellExe `
        -ArgumentList @(
            '--quiet'
            '--norc'
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
