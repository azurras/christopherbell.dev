Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ProductionMusicRuntimeRollbackScript {
    @'
const target = db.getSiblingDB('christopherbell');
const destination = target.getCollection('music_runtime_state');
const queueSource = target.getCollection('music_queue_state');
const radioSource = target.getCollection('music_radio_state');
const has = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const exactKeys = (value, required, optional) => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const permitted = required.concat(optional);
  if (!required.every((key) => has(value, key)) ||
      actual.some((key) => !permitted.includes(key))) return false;
  return true;
};
const encodedNumber = (value) => EJSON.serialize(value, {relaxed: false});
const integralText = (value) => {
  const encoded = encodedNumber(value);
  if (encoded && typeof encoded.$numberInt === 'string') return encoded.$numberInt;
  if (encoded && typeof encoded.$numberLong === 'string') return encoded.$numberLong;
  return null;
};
const isNonNegativeIntegral = (value) => {
  const text = integralText(value);
  return text !== null && /^(0|[1-9][0-9]*)$/.test(text);
};
const isPositiveIntegral = (value) => {
  const text = integralText(value);
  return text !== null && /^[1-9][0-9]*$/.test(text);
};
const exactCount = (value) => {
  const text = integralText(value);
  if (text === null || !/^(0|[1-9][0-9]*)$/.test(text)) {
    throw new Error('music runtime rollback received invalid numeric metadata');
  }
  return text;
};
const doubleValue = (value) => {
  const encoded = encodedNumber(value);
  if (!encoded || typeof encoded.$numberDouble !== 'string') return null;
  const number = Number(encoded.$numberDouble);
  return Number.isFinite(number) ? number : null;
};
const validText = (value, maximum) => typeof value === 'string' &&
    value.trim().length > 0 && value.length <= maximum;
const validDate = (value) => value instanceof Date && Number.isFinite(value.getTime());
const validVersion = (value) => !has(value, 'version') ||
    isNonNegativeIntegral(value.version);
const validClass = (value, expected) => !has(value, '_class') ||
    value._class === expected;
const validEntry = (entry) => exactKeys(
    entry,
    ['id', 'trackId', 'observedToken', 'enqueuedByAccountId', 'enqueuedAt'],
    []) &&
    validText(entry.id, 100) && validText(entry.trackId, 128) &&
    validText(entry.observedToken, 128) && validText(entry.enqueuedByAccountId, 128) &&
    validDate(entry.enqueuedAt);
const validEntries = (entries) => {
  if (!Array.isArray(entries) || entries.length > 1000) return false;
  const identities = new Set();
  for (const entry of entries) {
    if (!validEntry(entry) || identities.has(entry.id)) return false;
    identities.add(entry.id);
  }
  return true;
};
const validRadioValues = (value) => {
  const duration = doubleValue(value.durationSeconds);
  if (!isPositiveIntegral(value.stationSequence) ||
      !validText(value.trackId, 128) || !validText(value.observedToken, 128) ||
      !validDate(value.startedAt) || duration === null || duration <= 0 || duration > 86400 ||
      !['RADIO', 'QUEUE'].includes(value.source)) return false;
  if (value.source === 'QUEUE') {
    return has(value, 'queueEntryId') && validText(value.queueEntryId, 100);
  }
  return !has(value, 'queueEntryId');
};
const validRadioFields = (value) => exactKeys(
    value,
    ['stationSequence', 'trackId', 'observedToken', 'startedAt',
     'durationSeconds', 'source'],
    ['queueEntryId']) && validRadioValues(value);
const documents = destination.find({ _id: { $in: ['queue', 'radio'] } }).toArray();
if (documents.length !== 2 || exactCount(destination.countDocuments({})) !== '2') {
  throw new Error('music runtime destination must contain exactly two documents');
}
const queue = documents.find((value) => value._id === 'queue');
const radio = documents.find((value) => value._id === 'radio');
if (!queue || !exactKeys(queue, ['_id', 'kind', 'queue'], ['version', '_class']) ||
    queue.kind !== 'QUEUE' ||
    !exactKeys(queue.queue, ['entries'], []) || !validEntries(queue.queue.entries) ||
    !validVersion(queue) ||
    !validClass(queue, 'dev.christopherbell.music.radio.MusicRuntimeStateDocument')) {
  throw new Error('music runtime queue document is invalid');
}
if (!radio || !exactKeys(radio, ['_id', 'kind', 'radio'], ['version', '_class']) ||
    radio.kind !== 'RADIO' || !validRadioFields(radio.radio) || !validVersion(radio) ||
    !validClass(radio, 'dev.christopherbell.music.radio.MusicRuntimeStateDocument')) {
  throw new Error('music runtime radio document is invalid');
}
if (exactCount(queueSource.countDocuments({})) !== '1' ||
    exactCount(queueSource.countDocuments({ _id: 'global' })) !== '1' ||
    exactCount(radioSource.countDocuments({})) !== '1' ||
    exactCount(radioSource.countDocuments({ _id: 'global' })) !== '1') {
  throw new Error('legacy music runtime sources are not exact rollback targets');
}
const existingQueue = queueSource.findOne({ _id: 'global' });
const existingRadio = radioSource.findOne({ _id: 'global' });
if (!exactKeys(existingQueue, ['_id', 'entries'], ['version', '_class']) ||
    existingQueue._id !== 'global' || !validEntries(existingQueue.entries) ||
    !validVersion(existingQueue) ||
    !validClass(existingQueue, 'dev.christopherbell.music.radio.MusicQueueState')) {
  throw new Error('legacy music queue document is invalid');
}
if (!exactKeys(
      existingRadio,
      ['_id', 'stationSequence', 'trackId', 'observedToken', 'startedAt',
       'durationSeconds', 'source'],
      ['queueEntryId', 'version', '_class']) ||
    existingRadio._id !== 'global' || !validRadioValues(existingRadio) ||
    !validVersion(existingRadio) ||
    !validClass(existingRadio, 'dev.christopherbell.music.radio.MusicRadioState')) {
  throw new Error('legacy music radio document is invalid');
}
const queueLegacy = { _id: 'global', entries: queue.queue.entries };
if (has(queue, 'version')) queueLegacy.version = queue.version;
if (has(existingQueue, '_class')) queueLegacy._class = existingQueue._class;
const radioLegacy = {
  _id: 'global',
  stationSequence: radio.radio.stationSequence,
  trackId: radio.radio.trackId,
  observedToken: radio.radio.observedToken,
  startedAt: radio.radio.startedAt,
  durationSeconds: radio.radio.durationSeconds,
  source: radio.radio.source
};
if (has(radio.radio, 'queueEntryId')) radioLegacy.queueEntryId = radio.radio.queueEntryId;
if (has(radio, 'version')) radioLegacy.version = radio.version;
if (has(existingRadio, '_class')) radioLegacy._class = existingRadio._class;
const queueResult = queueSource.replaceOne({ _id: 'global' }, queueLegacy);
if (exactCount(queueResult.matchedCount) !== '1') {
  throw new Error('legacy queue replacement failed');
}
const radioResult = radioSource.replaceOne({ _id: 'global' }, radioLegacy);
if (exactCount(radioResult.matchedCount) !== '1') {
  throw new Error('legacy radio replacement failed');
}
const canonical = (value) => EJSON.stringify(value, {relaxed: false});
const queueReadback = queueSource.findOne({ _id: 'global' });
const radioReadback = radioSource.findOne({ _id: 'global' });
if (exactCount(queueSource.countDocuments({})) !== '1' ||
    exactCount(radioSource.countDocuments({})) !== '1' ||
    canonical(queueReadback) !== canonical(queueLegacy) ||
    canonical(radioReadback) !== canonical(radioLegacy)) {
  throw new Error('legacy music runtime post-copy equivalence failed');
}
print(JSON.stringify({
  complete: true,
  database: target.getName(),
  destinationCount: 2,
  restoredCollections: ['music_queue_state', 'music_radio_state']
}));
'@
}

function New-ProductionMusicRuntimeRollbackFailure {
    param(
        [Parameter(Mandatory)][string]$Message,
        [Parameter(Mandatory)][System.Exception]$Cause
    )

    return [InvalidOperationException]::new($Message, $Cause)
}

function Assert-ProductionMusicRuntimeBackup {
    param(
        [Parameter(Mandatory)][string]$Archive,
        [Parameter(Mandatory)][datetime]$OperationStartedAt
    )

    try {
        if (-not (Test-Path -LiteralPath $Archive -PathType Leaf) -or
            (Get-Item -LiteralPath $Archive -ErrorAction Stop).Length -le 0) {
            throw 'Backup archive is missing or empty.'
        }
        $sidecarPath = "$Archive.sha256.json"
        if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)) {
            throw 'Backup checksum sidecar is missing.'
        }
        $sidecar = Get-Content -LiteralPath $sidecarPath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $propertyNames = @($sidecar.PSObject.Properties.Name)
        if ($propertyNames.Count -ne 3 -or
            $propertyNames -notcontains 'archive' -or
            $propertyNames -notcontains 'sha256' -or
            $propertyNames -notcontains 'createdAt' -or
            $sidecar.archive -isnot [string] -or
            $sidecar.sha256 -isnot [string] -or
            ($sidecar.createdAt -isnot [string] -and
                $sidecar.createdAt -isnot [datetime] -and
                $sidecar.createdAt -isnot [DateTimeOffset])) {
            throw 'Backup checksum sidecar has an invalid shape.'
        }
        $expectedArchive = [IO.Path]::GetFullPath($Archive)
        $recordedArchive = [IO.Path]::GetFullPath([string]$sidecar.archive)
        if (-not $expectedArchive.Equals(
                $recordedArchive,
                [StringComparison]::OrdinalIgnoreCase) -or
            $sidecar.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
            throw 'Backup checksum sidecar does not identify the archive.'
        }
        $createdAt = if ($sidecar.createdAt -is [datetime] -or
            $sidecar.createdAt -is [DateTimeOffset]) {
            [DateTimeOffset]$sidecar.createdAt
        } else {
            $parsedCreatedAt = [DateTimeOffset]::MinValue
            if (-not [DateTimeOffset]::TryParse(
                    [string]$sidecar.createdAt,
                    [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::RoundtripKind,
                    [ref]$parsedCreatedAt)) {
                throw 'Backup checksum sidecar has an invalid timestamp.'
            }
            $parsedCreatedAt
        }
        $startedAtUtc = $OperationStartedAt.ToUniversalTime()
        $nowUtc = (Get-Date).ToUniversalTime()
        if ($createdAt.UtcDateTime -lt $startedAtUtc.AddSeconds(-5) -or
            $createdAt.UtcDateTime -gt $nowUtc.AddMinutes(1)) {
            throw 'Backup checksum sidecar is not fresh.'
        }
        $actualHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256 -ErrorAction Stop).Hash
        if (-not $actualHash.Equals([string]$sidecar.sha256, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Backup checksum does not match the archive.'
        }
    } catch {
        throw (New-ProductionMusicRuntimeRollbackFailure `
            -Message "Music runtime rollback backup verification failed; retained backup: $Archive" `
            -Cause $_.Exception)
    }
}

function ConvertFrom-ProductionMusicRuntimeRollback {
    param([Parameter(Mandatory)][string]$Json)

    try {
        $value = $Json | ConvertFrom-Json -ErrorAction Stop
        $names = @($value.PSObject.Properties.Name)
        $expectedNames = @('complete','database','destinationCount','restoredCollections')
        $unexpectedNames = @($names | Where-Object { $_ -notin $expectedNames })
        $missingNames = @($expectedNames | Where-Object { $_ -notin $names })
        if ($unexpectedNames.Count -ne 0 -or $missingNames.Count -ne 0 -or
            $value.complete -isnot [bool] -or $value.complete -ne $true -or
            $value.database -isnot [string] -or
            [string]$value.database -cne 'christopherbell' -or
            ($value.destinationCount -isnot [int] -and
                $value.destinationCount -isnot [long]) -or
            $value.destinationCount -ne 2 -or
            $value.restoredCollections -isnot [Array] -or
            $value.restoredCollections.Count -ne 2 -or
            $value.restoredCollections[0] -isnot [string] -or
            $value.restoredCollections[1] -isnot [string] -or
            [string]$value.restoredCollections[0] -cne 'music_queue_state' -or
            [string]$value.restoredCollections[1] -cne 'music_radio_state') {
            throw 'Invalid rollback metadata.'
        }
        return [pscustomobject][ordered]@{
            complete = $true
            database = 'christopherbell'
            destinationCount = 2
            restoredCollections = @('music_queue_state','music_radio_state')
        }
    } catch {
        throw [IO.InvalidDataException]::new(
            'Music runtime rollback returned invalid metadata.',
            $_.Exception)
    }
}

function Assert-ProductionMusicRuntimeWriterStopped {
    param([Parameter(Mandatory)][string]$FailureMessage)

    $service = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    if ([string]$service.Status -cne 'Stopped') {
        throw $FailureMessage
    }
}

function Invoke-ProductionMusicRuntimeStateRollback {
    [CmdletBinding()]
    param(
        [switch]$Confirm,
        [switch]$WhatIf
    )

    if ($WhatIf) {
        return [pscustomobject][ordered]@{
            database = 'christopherbell'
            destination = 'music_runtime_state'
            sources = @('music_queue_state','music_radio_state')
            mutates = $false
            requiresStoppedWriter = $true
            requiresFreshVerifiedBackup = $true
        }
    }
    if (-not $Confirm) {
        throw 'Music runtime rollback requires explicit confirmation.'
    }

    $config = Read-ProductionConfig
    Assert-ProductionMusicRuntimeWriterStopped `
        -FailureMessage 'ChristopherBellDev must be stopped before Music runtime rollback.'
    $operationStartedAt = Get-Date
    $backup = New-ProductionBackup
    Assert-ProductionMusicRuntimeBackup `
        -Archive $backup `
        -OperationStartedAt $operationStartedAt
    Assert-ProductionMusicRuntimeWriterStopped `
        -FailureMessage 'ChristopherBellDev must remain stopped during Music runtime rollback.'
    $json = Invoke-CheckedProcess `
        -FilePath $config.mongoShellExe `
        -ArgumentList @(
            '--quiet'
            '--norc'
            'mongodb://127.0.0.1:27017/admin'
            '--eval'
            (Get-ProductionMusicRuntimeRollbackScript)
        ) `
        -WorkingDirectory $config.repositoryPath
    $result = ConvertFrom-ProductionMusicRuntimeRollback -Json $json
    $result | Add-Member -NotePropertyName backup -NotePropertyValue $backup
    return $result
}

Export-ModuleMember -Function `
    Get-ProductionMusicRuntimeRollbackScript,Invoke-ProductionMusicRuntimeStateRollback
