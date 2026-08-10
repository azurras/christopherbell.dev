Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Production.WriterStart.psm1') -Global -Force

function Get-ProductionMusicRuntimeRollbackScript {
    @'
const target = db.getSiblingDB('christopherbell');
const destination = target.getCollection('music_runtime_state');
const queueSource = target.getCollection('music_queue_state');
const radioSource = target.getCollection('music_radio_state');
let rollbackPhase = 'preflight';
const rollbackErrorCodes = {
  'preflight': 'PREFLIGHT_FAILED',
  'queue-replacement': 'QUEUE_REPLACEMENT_FAILED',
  'radio-replacement': 'RADIO_REPLACEMENT_FAILED',
  'readback': 'READBACK_FAILED'
};
try {
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
rollbackPhase = 'queue-replacement';
const queueResult = queueSource.replaceOne({ _id: 'global' }, queueLegacy);
if (exactCount(queueResult.matchedCount) !== '1') {
  throw new Error('legacy queue replacement failed');
}
rollbackPhase = 'radio-replacement';
const radioResult = radioSource.replaceOne({ _id: 'global' }, radioLegacy);
if (exactCount(radioResult.matchedCount) !== '1') {
  throw new Error('legacy radio replacement failed');
}
rollbackPhase = 'readback';
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
} catch (failure) {
  print(JSON.stringify({
    complete: false,
    phase: rollbackPhase,
    errorCode: rollbackErrorCodes[rollbackPhase]
  }));
}
'@
}

function Get-ProductionMusicMigrationActivationNoLock {
    param([Parameter(Mandatory)]$Config)

    $script = @'
const target = db.getSiblingDB('christopherbell');
const migrationCount = target.getCollection('application_migrations')
  .countDocuments({_id:'014-consolidate-music-runtime-state'});
const destinationCount = target.getCollection('music_runtime_state').countDocuments({});
print(JSON.stringify({active:migrationCount !== 0 || destinationCount !== 0}));
'@
    try {
        $json = Invoke-CheckedProcess `
            -FilePath $Config.mongoShellExe `
            -ArgumentList @(
                '--quiet','--norc','mongodb://127.0.0.1:27017/admin','--eval',$script) `
            -WorkingDirectory $Config.repositoryPath
        $value = $json | ConvertFrom-Json -ErrorAction Stop
        $names = @($value.PSObject.Properties.Name)
        if ($names.Count -ne 1 -or $names[0] -cne 'active' -or
            $value.active -isnot [bool]) {
            throw 'Invalid activation metadata.'
        }
        return [bool]$value.active
    } catch {
        throw [System.InvalidOperationException]::new(
            'Music runtime migration activation could not be proven; rollback is blocked.')
    }
}


function Get-ProductionMusicRuntimeReconciliationScript {
    @'
const target = db.getSiblingDB('christopherbell');
const destination = target.getCollection('music_runtime_state');
const queueSource = target.getCollection('music_queue_state');
const radioSource = target.getCollection('music_radio_state');
let reconciliationPhase = 'preflight';
const reconciliationErrorCodes = {
  'preflight':'PREFLIGHT_FAILED',
  'queue-replacement':'QUEUE_REPLACEMENT_FAILED',
  'radio-replacement':'RADIO_REPLACEMENT_FAILED',
  'readback':'READBACK_FAILED'
};
try {
const has = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const exactKeys = (value, required, optional) => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const keys = Object.keys(value);
  return required.every((key) => has(value, key)) &&
      keys.every((key) => required.includes(key) || optional.includes(key));
};
const validText = (value, maximum) => typeof value === 'string' &&
    value.trim().length !== 0 && value.length <= maximum;
const exactDate = (value) => value instanceof Date && !Number.isNaN(value.valueOf());
const exactLong = (value) => {
  const encoded = EJSON.serialize(value, {relaxed:false});
  return encoded !== null && typeof encoded === 'object' &&
      ((typeof encoded.$numberInt === 'string' && /^-?[0-9]+$/.test(encoded.$numberInt)) ||
       (typeof encoded.$numberLong === 'string' && /^-?[0-9]+$/.test(encoded.$numberLong)));
};
const exactDouble = (value) => {
  const encoded = EJSON.serialize(value, {relaxed:false});
  return encoded !== null && typeof encoded === 'object' &&
      typeof encoded.$numberDouble === 'string' &&
      Number.isFinite(Number(encoded.$numberDouble));
};
const documents = (collection) => collection.find({}).toArray();
const queueDocuments = documents(queueSource);
const radioDocuments = documents(radioSource);
const destinationDocuments = documents(destination);
const queueTypeRows = queueSource.aggregate([{$project:{
  _id:1, versionType:{$type:'$version'}}}]).toArray();
const radioTypeRows = radioSource.aggregate([{$project:{
  _id:1, versionType:{$type:'$version'}, stationSequenceType:{$type:'$stationSequence'}}}]).toArray();
const destinationTypeRows = destination.aggregate([{$project:{
  _id:1, versionType:{$type:'$version'},
  stationSequenceType:{$type:'$radio.stationSequence'}}}]).toArray();
if (queueDocuments.length > 1 || radioDocuments.length > 1 ||
    destinationDocuments.length > 2) {
  throw new Error('music runtime reconciliation cardinality is invalid');
}
const queue = queueDocuments[0] || null;
const radio = radioDocuments[0] || null;
const byId = new Map(destinationDocuments.map((document) => [document._id, document]));
if (byId.size !== destinationDocuments.length ||
    [...byId.keys()].some((id) => id !== 'queue' && id !== 'radio')) {
  throw new Error('music runtime reconciliation destination identity is invalid');
}
const targetClass = 'dev.christopherbell.music.radio.MusicRuntimeStateDocument';
const queueClass = 'dev.christopherbell.music.radio.MusicQueueState';
const radioClass = 'dev.christopherbell.music.radio.MusicRadioState';
const typeRow = (rows, id) => rows.find((row) => row._id === id) || null;
const exactIntegerType = (value) => value === 'int' || value === 'long';
const validVersion = (document, type) => !has(document, 'version') ?
    type === 'missing' :
    (exactIntegerType(type) && exactLong(document.version) &&
      !document.version.toString().startsWith('-'));
const validClass = (document, expected) => !has(document, '_class') ||
    document._class === expected;
const validEntry = (entry) => exactKeys(entry,
    ['id','trackId','observedToken','enqueuedByAccountId','enqueuedAt'], []) &&
    validText(entry.id, 100) && validText(entry.trackId, 128) &&
    validText(entry.observedToken, 128) && validText(entry.enqueuedByAccountId, 128) &&
    exactDate(entry.enqueuedAt);
const validEntries = (entries) => Array.isArray(entries) && entries.length <= 1000 &&
    entries.every(validEntry) && new Set(entries.map((entry) => entry.id)).size === entries.length;
const validRadioPayload = (value, stationSequenceType) =>
    exactIntegerType(stationSequenceType) && exactLong(value.stationSequence) &&
    !value.stationSequence.toString().startsWith('-') && value.stationSequence.toString() !== '0' &&
    validText(value.trackId, 128) && validText(value.observedToken, 128) &&
    exactDate(value.startedAt) && exactDouble(value.durationSeconds) &&
    value.durationSeconds > 0 && value.durationSeconds <= 86400 &&
    ((value.source === 'QUEUE' && has(value, 'queueEntryId') &&
        validText(value.queueEntryId, 100)) ||
     (value.source === 'RADIO' && !has(value, 'queueEntryId')));
const validQueue = queue === null ||
    (exactKeys(queue, ['_id','entries'], ['version','_class']) &&
     queue._id === 'global' && validEntries(queue.entries) &&
     validVersion(queue, typeRow(queueTypeRows, 'global').versionType) &&
     validClass(queue, queueClass));
const validRadio = radio === null ||
    (exactKeys(radio, ['_id','stationSequence','trackId','observedToken','startedAt',
      'durationSeconds','source'], ['queueEntryId','version','_class']) &&
     radio._id === 'global' &&
     validRadioPayload(radio, typeRow(radioTypeRows, 'global').stationSequenceType) &&
     validVersion(radio, typeRow(radioTypeRows, 'global').versionType) &&
     validClass(radio, radioClass));
if (!validQueue || !validRadio) {
  throw new Error('music runtime reconciliation legacy shape is invalid');
}
const validTargetQueue = !byId.has('queue') ||
    (exactKeys(byId.get('queue'), ['_id','kind','queue'], ['version','_class']) &&
     byId.get('queue')._id === 'queue' && byId.get('queue').kind === 'QUEUE' &&
     validVersion(byId.get('queue'), typeRow(destinationTypeRows, 'queue').versionType) &&
     validClass(byId.get('queue'), targetClass) &&
     exactKeys(byId.get('queue').queue, ['entries'], []) &&
     validEntries(byId.get('queue').queue.entries));
const validTargetRadio = !byId.has('radio') ||
    (exactKeys(byId.get('radio'), ['_id','kind','radio'], ['version','_class']) &&
     byId.get('radio')._id === 'radio' && byId.get('radio').kind === 'RADIO' &&
     validVersion(byId.get('radio'), typeRow(destinationTypeRows, 'radio').versionType) &&
     validClass(byId.get('radio'), targetClass) &&
     validRadioPayload(byId.get('radio').radio,
       typeRow(destinationTypeRows, 'radio').stationSequenceType));
if (!validTargetQueue || !validTargetRadio) {
  throw new Error('music runtime reconciliation target shape is invalid');
}
if ((queue === null && byId.has('queue')) || (radio === null && byId.has('radio'))) {
  throw new Error('music runtime reconciliation absence conflicts with destination');
}
const withMetadata = (document, source, existing) => {
  if (has(source, 'version')) document.version = source.version;
  if (existing && has(existing, '_class')) {
    if (existing._class !== targetClass) {
      throw new Error('music runtime reconciliation target class is invalid');
    }
    document._class = existing._class;
  }
  return document;
};
let queueTarget = null;
let radioTarget = null;
if (queue !== null) {
  queueTarget = withMetadata(
    {_id:'queue',kind:'QUEUE',queue:{entries:queue.entries}},
    queue, byId.get('queue'));
  reconciliationPhase = 'queue-replacement';
  destination.replaceOne({_id:'queue'}, queueTarget, {upsert:true});
}
if (radio !== null) {
  const payload = {
    stationSequence:radio.stationSequence,
    trackId:radio.trackId,
    observedToken:radio.observedToken,
    startedAt:radio.startedAt,
    durationSeconds:radio.durationSeconds,
    source:radio.source
  };
  if (has(radio, 'queueEntryId')) payload.queueEntryId = radio.queueEntryId;
  radioTarget = withMetadata({_id:'radio',kind:'RADIO',radio:payload},
    radio, byId.get('radio'));
  reconciliationPhase = 'radio-replacement';
  destination.replaceOne({_id:'radio'}, radioTarget, {upsert:true});
}
reconciliationPhase = 'readback';
const canonical = (value) => EJSON.stringify(value, {relaxed:false});
if ((queueTarget !== null && canonical(destination.findOne({_id:'queue'})) !== canonical(queueTarget)) ||
    (radioTarget !== null && canonical(destination.findOne({_id:'radio'})) !== canonical(radioTarget)) ||
    destination.countDocuments({}) !== (queueTarget !== null ? 1 : 0) + (radioTarget !== null ? 1 : 0)) {
  throw new Error('music runtime reconciliation readback failed');
}
print(JSON.stringify({
  complete:true,
  database:'christopherbell',
  destinationCount:(queueTarget !== null ? 1 : 0) + (radioTarget !== null ? 1 : 0),
  sourcePresence:{queue:queue !== null,radio:radio !== null}
}));
} catch (failure) {
  print(JSON.stringify({
    complete:false,
    phase:reconciliationPhase,
    errorCode:reconciliationErrorCodes[reconciliationPhase]
  }));
}
'@
}

function New-ProductionMusicRuntimeRollbackFailure {
    param(
        [Parameter(Mandatory)][string]$Message,
        [Parameter(Mandatory)][System.Exception]$Cause
    )

    return [System.InvalidOperationException]::new($Message, $Cause)
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
        if ($value.complete -is [bool] -and $value.complete -eq $false) {
            $failureNames = @('complete','phase','errorCode')
            $failureCodes = @{
                'preflight' = 'PREFLIGHT_FAILED'
                'queue-replacement' = 'QUEUE_REPLACEMENT_FAILED'
                'radio-replacement' = 'RADIO_REPLACEMENT_FAILED'
                'readback' = 'READBACK_FAILED'
            }
            if ($names.Count -ne 3 -or
                @($failureNames | Where-Object { $_ -notin $names }).Count -ne 0 -or
                $value.phase -isnot [string] -or
                $value.errorCode -isnot [string] -or
                -not $failureCodes.ContainsKey([string]$value.phase) -or
                [string]$value.errorCode -cne $failureCodes[[string]$value.phase]) {
                throw 'Invalid rollback failure metadata.'
            }
            $phaseFailure = [System.InvalidOperationException]::new(
                'Music runtime rollback script reported an allowlisted failure.')
            $phaseFailure.Data['MusicRuntimePhase'] = [string]$value.phase
            $phaseFailure.Data['MusicRuntimeErrorCode'] = [string]$value.errorCode
            throw $phaseFailure
        }
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
        if ($_.Exception.Data.Contains('MusicRuntimePhase') -and
            $_.Exception.Data.Contains('MusicRuntimeErrorCode')) {
            throw $_.Exception
        }
        $metadataFailure = [System.IO.InvalidDataException]::new(
            'Music runtime rollback returned invalid metadata.')
        $metadataFailure.Data['MusicRuntimePhase'] = 'metadata'
        $metadataFailure.Data['MusicRuntimeErrorCode'] = 'METADATA_INVALID'
        throw $metadataFailure
    }
}

function New-ProductionMusicRuntimePostBackupFailure {
    param(
        [Parameter(Mandatory)][string]$Phase,
        [Parameter(Mandatory)][string]$ErrorCode,
        [Parameter(Mandatory)][string]$Archive,
        [Parameter(Mandatory)][System.Exception]$Cause
    )

    $allowed = @{
        'writer-check' = 'WRITER_NOT_STOPPED'
        'process' = 'MONGOSH_PROCESS_FAILED'
        'preflight' = 'PREFLIGHT_FAILED'
        'queue-replacement' = 'QUEUE_REPLACEMENT_FAILED'
        'radio-replacement' = 'RADIO_REPLACEMENT_FAILED'
        'readback' = 'READBACK_FAILED'
        'metadata' = 'METADATA_INVALID'
    }
    if (-not $allowed.ContainsKey($Phase) -or $allowed[$Phase] -cne $ErrorCode) {
        throw 'Music runtime rollback received an invalid internal failure phase.'
    }
    return [System.InvalidOperationException]::new(
        "Music runtime rollback failed [phase=$Phase,error=$ErrorCode]; retained backup: $Archive",
        $Cause)
}

function Assert-ProductionMusicRuntimeWriterStopped {
    param([Parameter(Mandatory)][string]$FailureMessage)

    $service = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    if ([string]$service.Status -cne 'Stopped') {
        throw $FailureMessage
    }
}

function Invoke-ProductionMusicRuntimeReverseCopyNoLock {
    param([Parameter(Mandatory)]$Config)

    Assert-ProductionMusicRuntimeWriterStopped `
        -FailureMessage 'ChristopherBellDev must be stopped before Music runtime rollback.'
    $operationStartedAt = Get-Date
    $backup = New-ProductionBackup
    Assert-ProductionMusicRuntimeBackup `
        -Archive $backup `
        -OperationStartedAt $operationStartedAt
    try {
        Assert-ProductionMusicRuntimeWriterStopped `
            -FailureMessage 'ChristopherBellDev must remain stopped during Music runtime rollback.'
    } catch {
        throw (New-ProductionMusicRuntimePostBackupFailure `
            -Phase 'writer-check' `
            -ErrorCode 'WRITER_NOT_STOPPED' `
            -Archive $backup `
            -Cause $_.Exception)
    }
    try {
        $json = Invoke-CheckedProcess `
            -FilePath $Config.mongoShellExe `
            -ArgumentList @(
                '--quiet'
                '--norc'
                'mongodb://127.0.0.1:27017/admin'
                '--eval'
                (Get-ProductionMusicRuntimeRollbackScript)
            ) `
            -WorkingDirectory $Config.repositoryPath
    } catch {
        throw (New-ProductionMusicRuntimePostBackupFailure `
            -Phase 'process' `
            -ErrorCode 'MONGOSH_PROCESS_FAILED' `
            -Archive $backup `
            -Cause $_.Exception)
    }
    try {
        $result = ConvertFrom-ProductionMusicRuntimeRollback -Json $json
    } catch {
        $phase = if ($_.Exception.Data.Contains('MusicRuntimePhase')) {
            [string]$_.Exception.Data['MusicRuntimePhase']
        } else { 'metadata' }
        $errorCode = if ($_.Exception.Data.Contains('MusicRuntimeErrorCode')) {
            [string]$_.Exception.Data['MusicRuntimeErrorCode']
        } else { 'METADATA_INVALID' }
        throw (New-ProductionMusicRuntimePostBackupFailure `
            -Phase $phase `
            -ErrorCode $errorCode `
            -Archive $backup `
            -Cause $_.Exception)
    }
    $result | Add-Member -NotePropertyName backup -NotePropertyValue $backup
    return $result
}

function Invoke-ProductionMusicRuntimeReconciliationNoLock {
    param([Parameter(Mandatory)]$Config)

    Assert-ProductionMusicRuntimeWriterStopped `
        -FailureMessage 'ChristopherBellDev must be stopped before Music runtime reconciliation.'
    $operationStartedAt = Get-Date
    $backup = New-ProductionBackup
    Assert-ProductionMusicRuntimeBackup `
        -Archive $backup `
        -OperationStartedAt $operationStartedAt
    try {
        Assert-ProductionMusicRuntimeWriterStopped `
            -FailureMessage 'ChristopherBellDev must remain stopped during Music runtime reconciliation.'
    } catch {
        throw [System.InvalidOperationException]::new(
            "Music runtime reconciliation failed [phase=writer-check,error=WRITER_NOT_STOPPED]; retained backup: $backup",
            $_.Exception)
    }
    try {
        $json = Invoke-CheckedProcess `
            -FilePath $Config.mongoShellExe `
            -ArgumentList @(
                '--quiet','--norc','mongodb://127.0.0.1:27017/admin','--eval',
                (Get-ProductionMusicRuntimeReconciliationScript)) `
            -WorkingDirectory $Config.repositoryPath
    } catch {
        throw [System.InvalidOperationException]::new(
            "Music runtime reconciliation failed [phase=process,error=MONGOSH_PROCESS_FAILED]; retained backup: $backup",
            $_.Exception)
    }
    try {
        $value = $json | ConvertFrom-Json -ErrorAction Stop
        $names = @($value.PSObject.Properties.Name)
        if ($value.complete -is [bool] -and -not $value.complete) {
            $failures = @{
                'preflight' = 'PREFLIGHT_FAILED'
                'queue-replacement' = 'QUEUE_REPLACEMENT_FAILED'
                'radio-replacement' = 'RADIO_REPLACEMENT_FAILED'
                'readback' = 'READBACK_FAILED'
            }
            if ($names.Count -ne 3 -or
                @(@('complete','phase','errorCode') |
                    Where-Object { $_ -notin $names }).Count -ne 0 -or
                $value.phase -isnot [string] -or
                $value.errorCode -isnot [string] -or
                -not $failures.ContainsKey([string]$value.phase) -or
                [string]$value.errorCode -cne $failures[[string]$value.phase]) {
                throw 'Invalid failure metadata.'
            }
            $phaseFailure = [System.InvalidOperationException]::new(
                'Music runtime reconciliation script reported an allowlisted failure.')
            $phaseFailure.Data['MusicRuntimePhase'] = [string]$value.phase
            $phaseFailure.Data['MusicRuntimeErrorCode'] = [string]$value.errorCode
            throw $phaseFailure
        }
        $presenceNames = @($value.sourcePresence.PSObject.Properties.Name)
        if ($names.Count -ne 4 -or
            @(@('complete','database','destinationCount','sourcePresence') |
                Where-Object { $_ -notin $names }).Count -ne 0 -or
            $value.complete -isnot [bool] -or -not $value.complete -or
            [string]$value.database -cne 'christopherbell' -or
            ($value.destinationCount -isnot [int] -and
                $value.destinationCount -isnot [long]) -or
            $presenceNames.Count -ne 2 -or
            @(@('queue','radio') | Where-Object { $_ -notin $presenceNames }).Count -ne 0 -or
            $value.sourcePresence.queue -isnot [bool] -or
            $value.sourcePresence.radio -isnot [bool] -or
            [int]$value.destinationCount -ne
                ([int]$value.sourcePresence.queue + [int]$value.sourcePresence.radio)) {
            throw 'Invalid metadata.'
        }
    } catch {
        $phase = if ($_.Exception.Data.Contains('MusicRuntimePhase')) {
            [string]$_.Exception.Data['MusicRuntimePhase']
        } else { 'metadata' }
        $errorCode = if ($_.Exception.Data.Contains('MusicRuntimeErrorCode')) {
            [string]$_.Exception.Data['MusicRuntimeErrorCode']
        } else { 'METADATA_INVALID' }
        throw [System.InvalidOperationException]::new(
            "Music runtime reconciliation failed [phase=$phase,error=$errorCode]; retained backup: $backup",
            $_.Exception)
    }
    return [pscustomobject][ordered]@{
        complete = $true
        database = 'christopherbell'
        destinationCount = [int]$value.destinationCount
        sourcePresence = [pscustomobject][ordered]@{
            queue = [bool]$value.sourcePresence.queue
            radio = [bool]$value.sourcePresence.radio
        }
        backup = $backup
    }
}

# Testable internal primitive retained for bounded reverse-copy verification. It is
# deliberately not exported; production callers must use the coordinated binary
# rollback boundary in Production.Operations.
function Invoke-ProductionMusicRuntimeStateRollback {
    [CmdletBinding()]
    param([switch]$Confirm, [switch]$WhatIf)
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
    if (-not $Confirm) { throw 'Music runtime rollback requires explicit confirmation.' }
    $config = Read-ProductionConfig
    $lock = Enter-DeploymentLock `
        -LockPath (Join-Path $config.programDataRoot 'locks\deploy.lock')
    try {
        Invoke-ProductionMusicRuntimeReverseCopyNoLock -Config $config
    } finally {
        $lock.Dispose()
    }
}

Export-ModuleMember -Function `
    Get-ProductionMusicRuntimeRollbackScript,Get-ProductionMusicRuntimeReconciliationScript,`
    Invoke-ProductionMusicRuntimeReconciliationNoLock,Get-ProductionMusicMigrationActivationNoLock
