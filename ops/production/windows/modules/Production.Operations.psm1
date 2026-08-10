Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:FixedProductionRoot = 'C:\ProgramData\christopherbell.dev'

function Enter-OperationsFixedRootDeploymentLock {
    param([Parameter(Mandatory)]$Config)

    Enter-ProductionFixedRootDeploymentLock `
        -Config $Config `
        -FixedRoot $script:FixedProductionRoot `
        -EnterLockAction {
            param($LockPath)
            Enter-DeploymentLock -LockPath $LockPath
        }
}

function Invoke-MusicReverseCopyUnderHeldLock {
    param([Parameter(Mandatory)]$Config)
    $module = Get-Module Production.MusicRuntime -ErrorAction Stop
    & $module { param($Value) Invoke-ProductionMusicRuntimeReverseCopyNoLock -Config $Value } $Config
}

function Grant-CoordinatedProductionWriterStart {
    param($Config, [string]$MarkerState, [string]$Release, [string]$Purpose)
    $module = Get-Module Production.WriterStart -ErrorAction Stop
    & $module {
        param($Value, $State, $Sha, $Reason)
        Grant-ProductionWriterStartAuthorization `
            -Config $Value -MarkerState $State -Release $Sha -Purpose $Reason
    } $Config $MarkerState $Release $Purpose
}

function Revoke-CoordinatedProductionWriterStart {
    param($Config, [Parameter(Mandatory)]$Authorization)
    $module = Get-Module Production.WriterStart -ErrorAction Stop
    & $module {
        param($Value, $Token)
        Revoke-ProductionWriterStartAuthorization -Config $Value -Authorization $Token
    } $Config $Authorization
}

function Ensure-CoordinatedProductionWriterStartGuard {
    param([Parameter(Mandatory)]$Config)
    $module = Get-Module Production.Deploy -ErrorAction Stop
    & $module {
        param($Value)
        Ensure-ProductionWriterStartGuardUnderHeldLock -Config $Value | Out-Null
    } $Config
}

function Restore-CoordinatedProductionWebsiteRecoveryPolicy {
    $module = Get-Module Production.Deploy -ErrorAction Stop
    & $module { Set-ProductionWebsiteRecoveryPolicy -Policy Normal }
}
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
    $config = Read-ProductionConfig (
        Join-Path $script:FixedProductionRoot 'config\deploy.json')
    $guard = Enter-OperationsFixedRootDeploymentLock -Config $config
    $lock = $guard.Lock
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
        $direction = Read-ProductionMusicSchemaDirection -Config $config
        if ($direction -and
            [string]$direction.state -eq 'TARGET_CUTOVER_IN_PROGRESS') {
            throw ('Production rollback is blocked because the first Music schema cutover is incomplete. ' +
                'Keep the writer stopped and complete bounded recovery under deploy.lock.')
        }
        if ($direction -and
            [string]$direction.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
            throw ('Production rollback is blocked because the legacy Music schema is active. ' +
                'Deploy a target-schema release to run locked reconciliation.')
        }
        if ($WhatIf) {
            Write-Output "Would roll back from $current to $previous"
            return
        }

        if (-not $direction -and
            (Get-ProductionMusicMigrationActivationNoLock -Config $config)) {
            throw ('Music runtime migration is active but the schema-direction marker is absent; ' +
                'rollback is blocked. Restore or initialize the protected marker before retrying.')
        }

        if ($direction -and [string]$direction.state -eq 'TARGET_ACTIVE') {
            $targetSha = Split-Path -Leaf $current
            $previousSha = Split-Path -Leaf $previous
            if ($targetSha -cne [string]$direction.targetRelease) {
                throw 'Music runtime schema-direction release identity does not match the active release.'
            }
            if ($previousSha -ceq [string]$direction.legacyRelease) {
                throw ('Generic rollback cannot start the retained legacy Music writer or reverse-copy state. ' +
                    'Use prod.cmd music-runtime-rollback -ConfirmMusicRuntimeRollback.')
            }
            Ensure-CoordinatedProductionWriterStartGuard -Config $config
            try {
                Set-AtomicJunction $config $currentPath $previous
                Set-AtomicJunction $config $previousPath $current
                $authorization = Grant-CoordinatedProductionWriterStart -Config $config `
                    -MarkerState TARGET_ACTIVE `
                    -Release $previousSha `
                    -Purpose TARGET_DEPLOY
                $startFailure = $null
                try {
                    Start-Service ChristopherBellDev
                    Test-ProductionEndpoints $config $config.productionPort
                    Test-ProductionPublicEndpoints -Config $config | Out-Null
                } catch {
                    $startFailure = $_.Exception
                } finally {
                    if ($authorization) {
                        try {
                            Revoke-CoordinatedProductionWriterStart `
                                -Config $config -Authorization $authorization
                        } catch {
                            if ($startFailure) {
                                throw [System.AggregateException]::new(
                                    'Target rollback start and authorization cleanup both failed.',
                                    [System.Exception[]]@($startFailure, $_.Exception))
                            }
                            throw
                        }
                    }
                }
                if ($startFailure) { throw $startFailure }
                Write-ProductionMusicSchemaDirection `
                    -Config $config `
                    -State TARGET_ACTIVE `
                    -TargetRelease $previousSha `
                    -LegacyRelease ([string]$direction.legacyRelease)
                Restore-CoordinatedProductionWebsiteRecoveryPolicy
                return
            } catch {
                $failure = $_.Exception
                try {
                    Stop-ProductionWebsiteService -ProductionPort $config.productionPort `
                        -KeepRecoverySuspended
                } catch {
                    throw [System.AggregateException]::new(
                        'Migration-aware rollback failed and the writer stop postcondition also failed.',
                        [System.Exception[]]@($failure, $_.Exception))
                }
                throw [System.InvalidOperationException]::new(
                    'Target-schema binary rollback failed; the writer remains stopped.',
                    $failure)
            }
        }

        Stop-ProductionWebsiteService -ProductionPort $config.productionPort -KeepRecoverySuspended
        $ordinaryRollbackHealthy = $false
        try {
            Set-AtomicJunction $config $currentPath $previous
            Set-AtomicJunction $config $previousPath $current
            Start-Service ChristopherBellDev
            Test-ProductionEndpoints $config $config.productionPort
            $ordinaryRollbackHealthy = $true
            Restore-CoordinatedProductionWebsiteRecoveryPolicy
        } catch {
            $rollbackFailure = $_.Exception
            $stopFailure = $null
            try {
                Stop-ProductionWebsiteService -ProductionPort $config.productionPort `
                    -KeepRecoverySuspended
            } catch {
                $stopFailure = $_.Exception
            }
            if ($ordinaryRollbackHealthy) {
                if ($stopFailure) {
                    throw [System.AggregateException]::new(
                        'Ordinary rollback recovery restoration and fail-closed stop both failed.',
                        [System.Exception[]]@($rollbackFailure, $stopFailure))
                }
                throw [System.InvalidOperationException]::new(
                    'Ordinary rollback recovery restoration failed; the writer remains stopped.',
                    $rollbackFailure)
            }
            if ($stopFailure) {
                throw [System.AggregateException]::new(
                    'Production rollback and fail-closed stop both failed.',
                    [System.Exception[]]@($rollbackFailure, $stopFailure))
            }
            try {
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
                Restore-CoordinatedProductionWebsiteRecoveryPolicy
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
    $config = Read-ProductionConfig (
        Join-Path $script:FixedProductionRoot 'config\deploy.json')
    $guard = Enter-OperationsFixedRootDeploymentLock -Config $config
    $lock = $guard.Lock
    try {
        $direction = Read-ProductionMusicSchemaDirection -Config $config
        if ($direction) {
            if ([string]$direction.state -eq 'TARGET_CUTOVER_IN_PROGRESS') {
                throw ('Manual restart is blocked because the first Music schema cutover is incomplete. ' +
                    'Complete bounded recovery under deploy.lock.')
            }
            if ([string]$direction.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
                throw ('Manual restart is blocked while legacy Music runtime state requires reconciliation. ' +
                    'Use the protected interactive deploy to reconcile before a target writer starts.')
            }
            $current = Get-JunctionTarget (Join-Path $config.programDataRoot 'current')
            $activeSha = if ($current) { Split-Path -Leaf $current } else { '' }
            $expectedSha = if ([string]$direction.state -eq 'TARGET_ACTIVE') {
                [string]$direction.targetRelease
            } else {
                [string]$direction.legacyRelease
            }
            if ($activeSha -cne $expectedSha) {
                throw ('The active binary contradicts the Music runtime schema-direction marker; ' +
                    'manual restart is blocked. Use protected deploy or rollback orchestration.')
            }
        }
        Restart-Service ChristopherBellDev
        if ($Verify) { Test-ProductionEndpoints $config $config.productionPort }
    } finally {
        $lock.Dispose()
    }
}

function Invoke-ProductionMigrationAwareRollback {
    [CmdletBinding()]
    param([switch]$Confirm, [switch]$WhatIf)
    if ($WhatIf) {
        return [pscustomobject][ordered]@{
            operation = 'target-to-legacy-migration-aware-rollback'
            mutates = $false
            requiresExplicitConfirmation = $true
            singleDeploymentLock = $true
        }
    }
    if (-not $Confirm) {
        throw 'Migration-aware Music rollback requires explicit confirmation.'
    }
    $config = Read-ProductionConfig (
        Join-Path $script:FixedProductionRoot 'config\deploy.json')
    $guard = Enter-OperationsFixedRootDeploymentLock -Config $config
    $lock = $guard.Lock
    $copy = $null
    try {
        $direction = Read-ProductionMusicSchemaDirection -Config $config
        if (-not $direction -or [string]$direction.state -ne 'TARGET_ACTIVE') {
            throw 'Migration-aware Music rollback requires exact TARGET_ACTIVE schema direction.'
        }
        $currentPath = Join-Path $config.programDataRoot 'current'
        $previousPath = Join-Path $config.programDataRoot 'previous'
        $current = Get-JunctionTarget $currentPath
        $legacy = Join-Path $config.programDataRoot "releases\$($direction.legacyRelease)"
        Assert-ReleasePath $config $legacy | Out-Null
        if (-not $current -or
            (Split-Path -Leaf $current) -cne [string]$direction.targetRelease) {
            throw 'The active target release does not match the Music schema-direction marker.'
        }
        Ensure-CoordinatedProductionWriterStartGuard -Config $config
        try {
            $copy = Invoke-MusicReverseCopyUnderHeldLock -Config $config
            Set-AtomicJunction $config $previousPath $current
            Set-AtomicJunction $config $currentPath $legacy
            $authorization = Grant-CoordinatedProductionWriterStart -Config $config `
                -MarkerState TARGET_ACTIVE `
                -Release ([string]$direction.legacyRelease) `
                -Purpose LEGACY_ROLLBACK
            $startFailure = $null
            try {
                Start-Service ChristopherBellDev
                Test-ProductionEndpoints $config $config.productionPort
                Test-ProductionPublicEndpoints -Config $config | Out-Null
            } catch {
                $startFailure = $_.Exception
            } finally {
                if ($authorization) {
                    try {
                        Revoke-CoordinatedProductionWriterStart `
                            -Config $config -Authorization $authorization
                    } catch {
                        if ($startFailure) {
                            throw [System.AggregateException]::new(
                                'Migration-aware rollback start and authorization cleanup both failed.',
                                [System.Exception[]]@($startFailure, $_.Exception))
                        }
                        throw
                    }
                }
            }
            if ($startFailure) { throw $startFailure }
            Write-ProductionMusicSchemaDirection `
                -Config $config `
                -State LEGACY_ACTIVE_RECONCILIATION_REQUIRED `
                -TargetRelease ([string]$direction.targetRelease) `
                -LegacyRelease ([string]$direction.legacyRelease)
            Restore-CoordinatedProductionWebsiteRecoveryPolicy
            return $copy
        } catch {
            $failure = $_.Exception
            if ($copy -and $copy.backup) {
                $failure = [System.InvalidOperationException]::new(
                    "Migration-aware Music rollback failed; retained backup: $($copy.backup)",
                    $failure)
            }
            try {
                Stop-ProductionWebsiteService -ProductionPort $config.productionPort `
                    -KeepRecoverySuspended
            } catch {
                throw [System.AggregateException]::new(
                    'Migration-aware Music rollback failed and the writer stop postcondition also failed.',
                    [System.Exception[]]@($failure, $_.Exception))
            }
            throw [System.InvalidOperationException]::new(
                'Migration-aware Music rollback failed; the writer remains stopped.', $failure)
        }
    } finally {
        $lock.Dispose()
    }
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

function Convert-ProductionMongoCollectionInventoryInteger {
    param([object]$Value, [string]$Path, [bool]$AllowNull)

    $number = Convert-ProductionMongoCollectionInventoryNumber $Value $Path $AllowNull
    if ($null -eq $number) {
        return $null
    }
    if ($number -is [single] -or $number -is [double]) {
        $floatingPointNumber = [double]$number
        if ([Math]::Truncate($floatingPointNumber) -ne $floatingPointNumber -or
            $floatingPointNumber -gt [double]9007199254740991) {
            throw "MongoDB collection inventory $Path is invalid."
        }
        return $number
    }
    try {
        $exactNumber = [decimal]$number
    } catch {
        throw "MongoDB collection inventory $Path is invalid."
    }
    if ([decimal]::Truncate($exactNumber) -ne $exactNumber -or
        $exactNumber -gt [decimal]9007199254740991) {
        throw "MongoDB collection inventory $Path is invalid."
    }
    return $number
}

function Convert-ProductionMongoCollectionInventoryCollation {
    param([object]$Value)

    Assert-ProductionMongoCollectionInventoryObject -Value $Value -Path 'options.collation'
    $result = [ordered]@{}
    foreach ($property in @($Value.PSObject.Properties)) {
        $path = "options.collation.$($property.Name)"
        if ($property.Name -ceq 'strength') {
            $strength = Convert-ProductionMongoCollectionInventoryInteger `
                $property.Value $path $false
            if ([double]$strength -lt 1 -or [double]$strength -gt 5) {
                throw "MongoDB collection inventory $path is invalid."
            }
            $result[$property.Name] = $strength
        } else {
            $result[$property.Name] = Copy-ProductionMongoCollectionInventoryMetadataValue `
                $property.Value $path
        }
    }
    return [pscustomobject]$result
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
            $number = Convert-ProductionMongoCollectionInventoryInteger `
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
                    'size' { $value = Convert-ProductionMongoCollectionInventoryInteger $value 'options.size' $false }
                    'max' { $value = Convert-ProductionMongoCollectionInventoryInteger $value 'options.max' $false }
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
                        $value = Convert-ProductionMongoCollectionInventoryCollation $value
                    }
                    'timeseries' {
                        $value = Convert-ProductionMongoCollectionInventoryTimeSeriesOptions $value
                    }
                    'expireAfterSeconds' {
                        $value = Convert-ProductionMongoCollectionInventoryInteger `
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
            $expireAfterSeconds = Convert-ProductionMongoCollectionInventoryInteger `
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

Export-ModuleMember -Function Get-ProductionStatus,Invoke-ProductionRollback,Invoke-ProductionMigrationAwareRollback,Watch-ProductionLogs,Restart-ProductionService,Get-ProductionReleases,Assert-AutoDeployTaskContract,Get-ProductionMongoCollectionInventory,Test-ProductionStartup
