Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:FixedProductionRoot = 'C:\ProgramData\christopherbell.dev'
$script:ProductionDatabase = 'christopherbell'
$script:ManifestDigest = '576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24'
$script:EngineActions = @(
    'preview','stage','verify-stage','publish-next','verify-live',
    'drop-legacy','reverse-next','recover-prepublication','prepare-restore',
    'restore-verify')
$script:EngineResultProperties = @(
    'complete','database','action','state','manifestDigest','backupIdentity',
    'expectedEvidenceDigest','evidenceDigest','evidence','kinds','indexes',
    'nextOperation')

function Assert-ProductionDomainCollectionConfirmation {
    param(
        [Parameter(Mandatory)][string]$Operation,
        [switch]$Confirm,
        [switch]$WhatIf
    )

    if (-not $WhatIf -and -not $Confirm) {
        throw "$Operation requires explicit confirmation."
    }
}

function Assert-ProductionDomainCollectionCandidateIsolation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][ValidateRange(1,65535)][int]$CandidatePort,
        [Parameter(Mandatory)][ValidateRange(1,65535)][int]$ProductionPort
    )

    if ($Database -cnotmatch '^cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24}$' -or
        [string]::Equals($Database, $script:ProductionDatabase,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The candidate database identity is not isolated from production.'
    }
    if ($CandidatePort -eq $ProductionPort) {
        throw 'The candidate port must differ from the production port.'
    }
}

function Assert-ProductionDomainCollectionExactProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Description
    )

    if ($null -eq $Value -or $Value -isnot [psobject]) {
        throw "$Description does not satisfy the exact result contract."
    }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    if ($actual.Count -ne $expected.Count -or
        [string]::Join("`n", $actual) -cne [string]::Join("`n", $expected)) {
        throw "$Description does not satisfy the exact result contract."
    }
}

function Assert-ProductionDomainCollectionDigest {
    param([AllowNull()][object]$Value, [Parameter(Mandatory)][string]$Description)
    if ($Value -isnot [string] -or [string]$Value -cnotmatch '^[0-9a-f]{64}$') {
        throw "$Description is invalid."
    }
}

function Assert-ProductionDomainCollectionEngineEvidence {
    param([Parameter(Mandatory)]$Evidence)

    Assert-ProductionDomainCollectionExactProperties -Value $Evidence -Names @(
        'version','manifestDigest','release','backupIdentity','presentSources',
        'kinds','collections','v014') -Description 'Migration evidence'
    if (($Evidence.version -isnot [int] -and $Evidence.version -isnot [long]) -or
        [int]$Evidence.version -ne 1 -or
        [string]$Evidence.manifestDigest -cne $script:ManifestDigest -or
        $Evidence.release -isnot [string] -or
        [string]$Evidence.release -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Migration evidence is invalid.'
    }
    Assert-ProductionDomainCollectionDigest `
        -Value $Evidence.backupIdentity -Description 'Migration evidence backup identity'
    if (@($Evidence.kinds).Count -ne 52) {
        throw 'Migration evidence must contain exactly 52 kind metrics.'
    }
    foreach ($metric in @($Evidence.kinds)) {
        Assert-ProductionDomainCollectionExactProperties `
            -Value $metric -Names @('kind','count','checksum') `
            -Description 'Migration kind metric'
        if ($metric.kind -isnot [string] -or
            [string]$metric.kind -cnotmatch '^[a-z][a-z0-9_]{0,63}$' -or
            ($metric.count -isnot [int] -and $metric.count -isnot [long]) -or
            [long]$metric.count -lt 0 -or
            [long]$metric.count -gt 9007199254740991) {
            throw 'Migration kind metric is invalid.'
        }
        Assert-ProductionDomainCollectionDigest `
            -Value $metric.checksum -Description 'Migration kind checksum'
    }
    foreach ($metric in @($Evidence.collections)) {
        Assert-ProductionDomainCollectionExactProperties `
            -Value $metric -Names @('name','count','checksum','indexDigest') `
            -Description 'Migration collection metric'
        if ($metric.name -isnot [string] -or
            [string]$metric.name -cnotmatch '^[a-z][a-z0-9_]{0,127}$' -or
            ($metric.count -isnot [int] -and $metric.count -isnot [long]) -or
            [long]$metric.count -lt 0 -or
            [long]$metric.count -gt 9007199254740991) {
            throw 'Migration collection metric is invalid.'
        }
        Assert-ProductionDomainCollectionDigest `
            -Value $metric.checksum -Description 'Migration collection checksum'
        Assert-ProductionDomainCollectionDigest `
            -Value $metric.indexDigest -Description 'Migration collection index digest'
    }
    Assert-ProductionDomainCollectionExactProperties `
        -Value $Evidence.v014 `
        -Names @('id','checksum','queueChecksum','radioChecksum','targetChecksum') `
        -Description 'Migration V014 evidence'
    foreach ($name in 'checksum','queueChecksum','radioChecksum','targetChecksum') {
        Assert-ProductionDomainCollectionDigest `
            -Value $Evidence.v014.$name -Description "Migration V014 $name"
    }
}

function ConvertFrom-ProductionDomainCollectionResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Json,
        [Parameter(Mandatory)][string]$ExpectedDatabase,
        [Parameter(Mandatory)][string]$ExpectedAction
    )

    $trimmed = $Json.Trim()
    if ($trimmed -cnotmatch '^\{' -or $trimmed -cnotmatch '\}$') {
        throw 'Mongo migration output must be a single JSON document.'
    }
    try {
        $value = $trimmed | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw [IO.InvalidDataException]::new(
            'Mongo migration output must be a single JSON document.', $_.Exception)
    }
    Assert-ProductionDomainCollectionExactProperties `
        -Value $value -Names $script:EngineResultProperties `
        -Description 'Mongo migration result'
    if ($value.complete -isnot [bool] -or
        $value.database -isnot [string] -or
        [string]$value.database -cne $ExpectedDatabase -or
        $value.action -isnot [string] -or [string]$value.action -cne $ExpectedAction -or
        $value.state -isnot [string] -or [string]::IsNullOrWhiteSpace($value.state) -or
        [string]$value.manifestDigest -cne $script:ManifestDigest) {
        throw 'Mongo migration result identity or completion is invalid.'
    }
    if (-not [bool]$value.complete -and
        ($value.nextOperation -isnot [string] -or
            [string]$value.nextOperation -cne $ExpectedAction)) {
        throw 'Mongo migration continuation is invalid.'
    }
    if ($value.kinds -isnot [Array] -or $value.indexes -isnot [Array]) {
        throw 'Mongo migration redacted metrics are invalid.'
    }
    Assert-ProductionDomainCollectionDigest `
        -Value $value.backupIdentity -Description 'Mongo migration backup identity'
    Assert-ProductionDomainCollectionDigest `
        -Value $value.expectedEvidenceDigest -Description 'Mongo migration expected evidence digest'
    if ($null -ne $value.evidenceDigest) {
        Assert-ProductionDomainCollectionDigest `
            -Value $value.evidenceDigest -Description 'Mongo migration evidence digest'
    }
    if ($ExpectedAction -eq 'preview') {
        if ($null -eq $value.evidence -or $null -eq $value.evidenceDigest) {
            throw 'Mongo migration preview did not return protected evidence.'
        }
        Assert-ProductionDomainCollectionEngineEvidence -Evidence $value.evidence
        if ([string]$value.evidenceDigest -cne [string]$value.expectedEvidenceDigest) {
            throw 'Mongo migration preview evidence digest does not match its result.'
        }
    } elseif ($null -ne $value.evidence) {
        throw 'Mongo migration mutation unexpectedly returned evidence.'
    }
    return $value
}

function Invoke-ProductionDomainCollectionEngine {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Action,
        [Parameter(Mandatory)][string]$OwnerToken,
        [Parameter(Mandatory)][string]$Release,
        [Parameter(Mandatory)][string]$BackupIdentity,
        [Parameter(Mandatory)][string]$EvidenceDigest,
        $Evidence,
        [string]$MongoUri = ''
    )

    if ($Database -cnotmatch '^(christopherbell|cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24})$') {
        throw 'Mongo migration database identity is invalid.'
    }
    if (-not ($script:EngineActions -ccontains $Action)) {
        throw 'Mongo migration action is invalid.'
    }
    if ($OwnerToken -cnotmatch '^[0-9a-f]{32}$' -or
        $Release -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Mongo migration owner or release identity is invalid.'
    }
    Assert-ProductionDomainCollectionDigest `
        -Value $BackupIdentity -Description 'Mongo migration backup identity'
    Assert-ProductionDomainCollectionDigest `
        -Value $EvidenceDigest -Description 'Mongo migration evidence digest'
    if (($Action -eq 'preview') -ne ($EvidenceDigest -ceq ('0' * 64))) {
        throw 'Mongo migration evidence digest is invalid for the requested action.'
    }
    if ($Action -ne 'preview' -and $null -eq $Evidence) {
        throw 'Mongo migration protected evidence is required for mutation.'
    }
    if ($null -ne $Evidence) {
        Assert-ProductionDomainCollectionEngineEvidence -Evidence $Evidence
    }
    $effectiveMongoUri = 'mongodb://127.0.0.1:27017/admin'
    if (-not [string]::IsNullOrEmpty($MongoUri)) {
        if ($Database -cnotmatch '^cbell_candidate_' -or
            $MongoUri -cnotmatch '^mongodb://127\.0\.0\.1:([0-9]{4,5})/admin$') {
            throw 'The disposable Mongo URI is not an isolated candidate boundary.'
        }
        $disposablePort = [int]$Matches[1]
        if ($disposablePort -in 27017,8080,8081 -or $disposablePort -gt 65535) {
            throw 'The disposable Mongo URI uses a forbidden production port.'
        }
        $effectiveMongoUri = $MongoUri
    }
    $arguments = @(
        $Database,$Action,$script:ManifestDigest,$OwnerToken,$Release,
        $BackupIdentity,$EvidenceDigest)
    $argumentsJson = $arguments | ConvertTo-Json -Compress
    $bootstrap = "globalThis.DOMAIN_COLLECTION_ARGS=$argumentsJson;"
    $environment = @{}
    if ($null -ne $Evidence) {
        $bootstrap += 'globalThis.DOMAIN_COLLECTION_EVIDENCE=JSON.parse(process.env.CBELL_DOMAIN_EVIDENCE);'
        $environment.CBELL_DOMAIN_EVIDENCE = $Evidence | ConvertTo-Json -Depth 100 -Compress
    }
    $bootstrap += 'void 0;'
    $scripts = Join-Path $PSScriptRoot '..\scripts'
    $manifest = Join-Path $scripts 'DomainCollectionManifest.js'
    $engine = Join-Path $scripts 'Invoke-DomainCollectionMigration.js'
    foreach ($path in $manifest,$engine) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw 'A required domain migration script is missing.'
        }
    }
    $json = Invoke-CheckedProcess `
        -FilePath $Config.mongoShellExe `
        -ArgumentList @(
            '--quiet','--norc',$effectiveMongoUri,
            '--eval',$bootstrap,'--file',$manifest,'--file',$engine) `
        -WorkingDirectory $Config.repositoryPath `
        -Environment $environment
    ConvertFrom-ProductionDomainCollectionResult `
        -Json ([string]$json).Trim() `
        -ExpectedDatabase $Database `
        -ExpectedAction $Action
}

function New-ProductionDomainCollectionPreviewContext {
    param([Parameter(Mandatory)]$Config)
    $null = Get-ProductionDomainCollectionTerminalReinitializationState `
        -Config $Config
    $active = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    if (-not $active) { throw 'The active production release is unavailable.' }
    $release = Split-Path -Leaf $active
    if ($release -cnotmatch '^[0-9a-f]{40}$') {
        throw 'The active production release identity is invalid.'
    }
    [pscustomobject][ordered]@{
        config = $Config
        release = $release
        ownerToken = [guid]::NewGuid().ToString('N')
        backupIdentity = '0' * 64
    }
}

function Invoke-ProductionDomainCollectionPreviewAction {
    param([Parameter(Mandatory)]$Context)
    Invoke-ProductionDomainCollectionEngine `
        -Config $Context.config `
        -Database $script:ProductionDatabase `
        -Action preview `
        -OwnerToken $Context.ownerToken `
        -Release $Context.release `
        -BackupIdentity $Context.backupIdentity `
        -EvidenceDigest ('0' * 64)
}

function Get-ProductionDomainCollectionReleaseMetadataSchema {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$Metadata,
        [Parameter(Mandatory)][string]$Sha
    )
    $historicalNames = @('sha','source','builtAt','musicSchema')
    $modernNames = @('sha','source','builtAt','musicSchema','domainSchema')
    $names = @($Metadata.PSObject.Properties.Name)
    $isHistorical = ($names.Count -eq $historicalNames.Count -and
        ([string]::Join("`0", $names) -ceq [string]::Join("`0", $historicalNames)))
    $isModern = ($names.Count -eq $modernNames.Count -and
        ([string]::Join("`0", $names) -ceq [string]::Join("`0", $modernNames)))
    if (-not $isHistorical -and -not $isModern) {
        throw 'Release metadata shape is invalid.'
    }
    if ($Sha -cnotmatch '^[0-9a-f]{40}$' -or
        $Config.remote -isnot [string] -or [string]::IsNullOrWhiteSpace($Config.remote) -or
        $Config.branch -isnot [string] -or [string]::IsNullOrWhiteSpace($Config.branch) -or
        $Metadata.sha -isnot [string] -or [string]$Metadata.sha -cne $Sha -or
        $Metadata.source -isnot [string] -or
        [string]$Metadata.source -cne "$($Config.remote)/$($Config.branch)" -or
        $Metadata.musicSchema -isnot [string] -or
        [string]$Metadata.musicSchema -cnotin @('LEGACY','TARGET')) {
        throw 'Release metadata identity is invalid.'
    }
    $builtAtValue = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParseExact(
            [string]$Metadata.builtAt, 'o', [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$builtAtValue) -or
        $builtAtValue.Offset -ne [timespan]::Zero) {
        throw 'Release metadata build timestamp is invalid.'
    }
    if ($isHistorical) {
        return [pscustomobject][ordered]@{ modern = $false; schema = '' }
    }
    if ($Metadata.domainSchema -isnot [string] -or
        [string]$Metadata.domainSchema -cnotin @('LEGACY','TARGET')) {
        throw 'Release metadata domain schema is invalid.'
    }
    return [pscustomobject][ordered]@{
        modern = $true
        schema = [string]$Metadata.domainSchema
    }
}

function ConvertFrom-ProductionDomainCollectionReleaseMetadataJson {
    param([Parameter(Mandatory)][string]$MetadataJson)
    $match = [regex]::Match($MetadataJson,
        '^\s*\{\s*"sha"\s*:\s*"(?<sha>[^"\\]+)"\s*,\s*"source"\s*:\s*"(?<source>[^"\\]+)"\s*,\s*"builtAt"\s*:\s*"(?<builtAt>[^"\\]+)"\s*,\s*"musicSchema"\s*:\s*"(?<musicSchema>[^"\\]+)"(?:\s*,\s*"domainSchema"\s*:\s*"(?<domainSchema>[^"\\]+)")?\s*\}\s*$')
    if (-not $match.Success) {
        throw 'Release metadata JSON shape is invalid.'
    }
    $metadata = [ordered]@{
        sha = [string]$match.Groups['sha'].Value
        source = [string]$match.Groups['source'].Value
        builtAt = [string]$match.Groups['builtAt'].Value
        musicSchema = [string]$match.Groups['musicSchema'].Value
    }
    if ($match.Groups['domainSchema'].Success) {
        $metadata.domainSchema = [string]$match.Groups['domainSchema'].Value
    }
    return [pscustomobject]$metadata
}

function Get-ProductionDomainCollectionHistoricalReleaseSchema {
    param([Parameter(Mandatory)][string]$Release)
    $jarPath = Join-Path $Release 'app.jar'
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw 'Historical release executable JAR is unavailable.'
    }
    Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $applicationPath = 'BOOT-INF/classes/dev/christopherbell/Application.class'
        $applicationEntries = @($archive.Entries | Where-Object {
            $_.FullName -ceq $applicationPath -and $_.Length -gt 0
        })
        if ($applicationEntries.Count -ne 1) {
            throw 'Historical release executable JAR is invalid.'
        }
        $migrationPath = 'BOOT-INF/classes/dev/christopherbell/configuration/mongo/migration/V015RequireDomainCollectionSchema.class'
        $migrationEntries = @($archive.Entries | Where-Object {
            $_.FullName -ceq $migrationPath -and $_.Length -gt 0
        })
        if ($migrationEntries.Count -gt 1) {
            throw 'Historical release executable JAR is ambiguous.'
        }
        if ($migrationEntries.Count -eq 1) { return 'TARGET' }
        return 'LEGACY'
    } finally {
        $archive.Dispose()
    }
}

function Get-ProductionDomainCollectionReleaseSchema {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Release,
        [Parameter(Mandatory)][string]$Sha
    )
    try {
        $releasePath = Assert-ReleasePath -Config $Config -Path $Release
        if ((Split-Path -Leaf $releasePath) -cne $Sha) {
            throw 'Release path identity is invalid.'
        }
        $metadataPath = Join-Path $releasePath 'release.json'
        $metadataJson = Get-Content -LiteralPath $metadataPath -Raw -ErrorAction Stop
        $metadata = ConvertFrom-ProductionDomainCollectionReleaseMetadataJson `
            -MetadataJson $metadataJson
        $metadataSchema = Get-ProductionDomainCollectionReleaseMetadataSchema `
            -Config $Config -Metadata $metadata -Sha $Sha
        if ($metadataSchema.modern) {
            return [string]$metadataSchema.schema
        }
        $historicalSchema = Get-ProductionDomainCollectionHistoricalReleaseSchema `
            -Release $releasePath
        if ($historicalSchema -cnotin @('LEGACY','TARGET')) {
            throw 'Historical release executable JAR classification is invalid.'
        }
        $schema = [string]$historicalSchema
        $backfilledMetadata = [ordered]@{
            sha = [string]$metadata.sha
            source = [string]$metadata.source
            builtAt = [string]$metadata.builtAt
            musicSchema = [string]$metadata.musicSchema
            domainSchema = $schema
        }
        Write-ProductionDomainCollectionProtectedJson `
            -Config $Config -Path $metadataPath -Value $backfilledMetadata
        $readbackJson = Get-Content -LiteralPath $metadataPath -Raw -ErrorAction Stop
        $readback = ConvertFrom-ProductionDomainCollectionReleaseMetadataJson `
            -MetadataJson $readbackJson
        $readbackMetadataSchema = Get-ProductionDomainCollectionReleaseMetadataSchema `
            -Config $Config -Metadata $readback -Sha $Sha
        if (-not $readbackMetadataSchema.modern -or
            [string]$readbackMetadataSchema.schema -cne $schema) {
            throw 'Release metadata backfill readback is invalid.'
        }
        return $schema
    } catch {
        throw [IO.InvalidDataException]::new(
            'Domain collection release metadata is invalid.', $_.Exception)
    }
}

function Get-ProductionDomainCollectionStatePath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'state\domain-collection-cutover.json'
}

function Assert-ProductionDomainCollectionStateTransition {
    param(
        [Parameter(Mandatory)][string]$Current,
        [Parameter(Mandatory)][string]$Next
    )
    if ($Current -ceq 'ROLLED_BACK') {
        throw 'Protected domain collection rollback state is terminal.'
    }
    $allowed = @{
        INITIALIZED = @('PREVIEWED')
        PREVIEWED = @('CANDIDATE_VERIFIED','LEGACY_DATA_VERIFIED')
        CANDIDATE_VERIFIED = @('LIVE_PUBLISHED','LEGACY_DATA_VERIFIED')
        LIVE_PUBLISHED = @('TARGET_START_PENDING','LEGACY_DATA_VERIFIED')
        TARGET_START_PENDING = @('DROP_STARTED','LEGACY_DATA_VERIFIED')
        DROP_STARTED = @('LEGACY_DROPPED','ROLLBACK_VERIFIED')
        LEGACY_DROPPED = @('TARGET_ACTIVE','ROLLBACK_VERIFIED')
        TARGET_ACTIVE = @('ROLLBACK_VERIFIED')
        ROLLBACK_VERIFIED = @('LEGACY_DATA_VERIFIED')
        LEGACY_DATA_VERIFIED = @('ROLLBACK_READY')
        ROLLBACK_READY = @('ROLLED_BACK')
    }
    if (-not $allowed.ContainsKey($Current) -or
        -not ($allowed[$Current] -ccontains $Next)) {
        throw "Protected domain collection state transition $Current -> $Next is invalid."
    }
}

function Write-ProductionDomainCollectionProtectedJson {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value,
        [ValidateRange(2,100)][int]$Depth = 20
    )
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    Protect-ProductionPath -Path $parent
    Assert-ProtectedProductionPath -Path $parent | Out-Null
    $temporary = "$Path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Value | ConvertTo-Json -Depth $Depth | Set-Content `
            -LiteralPath $temporary -Encoding utf8
        Protect-ProductionPath -Path $temporary
        Assert-ProtectedProductionPath -Path $temporary | Out-Null
        Move-Item -LiteralPath $temporary -Destination $Path -Force
        Protect-ProductionPath -Path $Path
        Assert-ProtectedProductionPath -Path $Path | Out-Null
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function New-ProductionDomainCollectionVerifiedBackup {
    param([Parameter(Mandatory)]$Config)

    $archive = New-ProductionBackup
    $archivePath = [IO.Path]::GetFullPath([string]$archive)
    $backupRoot = [IO.Path]::GetFullPath([string]$Config.backupRoot).TrimEnd('\')
    if (-not $archivePath.StartsWith(
            $backupRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        throw 'The domain collection backup archive escaped the configured backup root.'
    }
    Assert-ProductionPathNotReparse -Path $archivePath | Out-Null
    $sidecarPath = "$archivePath.sha256.json"
    Assert-ProductionPathNotReparse -Path $sidecarPath | Out-Null
    try {
        $sidecar = Get-Content -LiteralPath $sidecarPath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionExactProperties `
            -Value $sidecar -Names @('archive','sha256','createdAt') `
            -Description 'Backup sidecar'
        $created = if ($sidecar.createdAt -is [DateTimeOffset]) {
            [DateTimeOffset]$sidecar.createdAt
        } elseif ($sidecar.createdAt -is [DateTime]) {
            [DateTimeOffset]$sidecar.createdAt
        } else {
            [DateTimeOffset]::ParseExact(
                [string]$sidecar.createdAt, 'o',
                [Globalization.CultureInfo]::InvariantCulture)
        }
        $age = [DateTimeOffset]::UtcNow - $created.ToUniversalTime()
        $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if (-not [string]::Equals(
                [IO.Path]::GetFullPath([string]$sidecar.archive),
                $archivePath,[StringComparison]::OrdinalIgnoreCase) -or
            [string]$sidecar.sha256 -cnotmatch '^[0-9A-Fa-f]{64}$' -or
            [string]$sidecar.sha256.ToLowerInvariant() -cne $actualHash -or
            $age.TotalSeconds -lt -5 -or $age.TotalMinutes -gt 15) {
            throw 'Backup sidecar identity, hash, or freshness is invalid.'
        }
        Invoke-CheckedProcess `
            -FilePath (Join-Path $Config.mongoToolsPath 'mongorestore.exe') `
            -ArgumentList (Get-NativeMongoRestoreDryRunArguments $archivePath) `
            -WorkingDirectory $Config.repositoryPath | Out-Null
        Protect-ProductionPath -Path $archivePath
        Protect-ProductionPath -Path $sidecarPath
        Assert-ProtectedProductionPath -Path $archivePath | Out-Null
        Assert-ProtectedProductionPath -Path $sidecarPath | Out-Null
        [pscustomobject][ordered]@{
            archive = $archivePath
            backupIdentity = $actualHash
            sidecar = $sidecarPath
        }
    } catch {
        throw [IO.InvalidDataException]::new(
            'A fresh exact dry-restored backup could not be proven.', $_.Exception)
    }
}

function Assert-ProductionDomainCollectionTerminalLegacyState {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$Marker,
        [Parameter(Mandatory)]$State
    )
    if ([int]$Marker.version -ne 2 -or
        [string]$Marker.state -cne 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED' -or
        [bool]$Marker.legacyDropped) {
        throw ('Terminal domain collection reinitialization requires an exact ' +
            'legacy-compatible v2 marker.')
    }
    if ([string]$State.state -cne 'ROLLED_BACK') {
        throw 'Terminal domain collection reinitialization requires exact ROLLED_BACK state.'
    }
    if ([string]$Marker.targetRelease -cne [string]$State.targetRelease -or
        [string]$Marker.legacyRelease -cne [string]$State.legacyRelease -or
        [string]$Marker.evidenceDigest -cne [string]$State.evidenceDigest -or
        [string]$Marker.backupIdentity -cne [string]$State.backupIdentity) {
        throw 'Terminal domain collection marker and protected state identities do not match.'
    }
    $active = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    if (-not $active) {
        throw 'Terminal domain collection state requires an active legacy release.'
    }
    $active = Assert-ReleasePath $Config $active
    $expected = Assert-ReleasePath $Config (
        Join-Path $Config.programDataRoot "releases\$($State.legacyRelease)")
    if (-not [string]::Equals(
            [IO.Path]::GetFullPath($active),[IO.Path]::GetFullPath($expected),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Terminal domain collection state does not own the active legacy release.'
    }
    if ((Get-ProductionDomainCollectionReleaseSchema `
            -Config $Config -Release $active -Sha ([string]$State.legacyRelease)) -cne 'LEGACY') {
        throw 'Terminal domain collection active release is not an exact legacy domain-schema release.'
    }
    return $State
}

function Get-ProductionDomainCollectionTerminalReinitializationState {
    param([Parameter(Mandatory)]$Config)
    $marker = Read-ProductionDomainSchemaDirection -Config $Config
    if (-not $marker) {
        $statePath = Get-ProductionDomainCollectionStatePath -Config $Config
        if (Test-Path -LiteralPath $statePath -PathType Leaf) {
            throw ('Protected domain collection state exists without its exact marker; ' +
                'preview and cutover are blocked.')
        }
        return $null
    }
    if ([string]$marker.state -cne 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
        throw ('Domain collection preview or cutover requires an absent marker or an exact ' +
            'terminal legacy rollback state.')
    }
    $state = Read-ProductionDomainCollectionProtectedState -Config $Config
    if ($state.PSObject.Properties['terminalReconciliationAuthorized'] -and
        [bool]$state.terminalReconciliationAuthorized) {
        throw ('Terminal domain collection state requires rollback reconciliation ' +
            'before preview or a future cutover.')
    }
    Assert-ProductionDomainCollectionTerminalLegacyState `
        -Config $Config -Marker $marker -State $state
}

function Archive-ProductionDomainCollectionTerminalState {
    param([Parameter(Mandatory)]$State)
    if ([string]$State.state -cne 'ROLLED_BACK') {
        throw 'Only exact terminal domain collection state may be archived.'
    }
    $source = Get-ProductionDomainCollectionStatePath -Config $State.config
    Assert-ProductionPathNotReparse -Path $source | Out-Null
    Assert-ProtectedProductionPath -Path $source | Out-Null
    $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).
        Hash.ToLowerInvariant()
    $historyRoot = Join-Path $State.config.programDataRoot 'state\history'
    New-Item -ItemType Directory -Path $historyRoot -Force | Out-Null
    Protect-ProductionPath -Path $historyRoot
    Assert-ProtectedProductionPath -Path $historyRoot | Out-Null
    $destination = Join-Path $historyRoot `
        "domain-collection-cutover.$sourceHash.json"
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        Assert-ProductionPathNotReparse -Path $destination | Out-Null
        Assert-ProtectedProductionPath -Path $destination | Out-Null
        $existingHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).
            Hash.ToLowerInvariant()
        if ($existingHash -cne $sourceHash) {
            throw 'Protected terminal domain collection history identity changed.'
        }
        return $destination
    }
    $temporary = "$destination.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        Copy-Item -LiteralPath $source -Destination $temporary
        Protect-ProductionPath -Path $temporary
        Assert-ProtectedProductionPath -Path $temporary | Out-Null
        $temporaryHash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).
            Hash.ToLowerInvariant()
        if ($temporaryHash -cne $sourceHash) {
            throw 'Staged terminal domain collection history identity changed.'
        }
        Move-Item -LiteralPath $temporary -Destination $destination
        Protect-ProductionPath -Path $destination
        Assert-ProtectedProductionPath -Path $destination | Out-Null
        $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).
            Hash.ToLowerInvariant()
        $currentSourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).
            Hash.ToLowerInvariant()
        if ($destinationHash -cne $sourceHash -or
            $currentSourceHash -cne $sourceHash) {
            throw 'Published terminal domain collection history identity changed.'
        }
        return $destination
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-ProductionDomainCollectionTerminalReconciliationPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot `
        'state\domain-collection-rollback-reconciliation.json'
}

function Write-ProductionDomainCollectionTerminalReconciliationAuthorization {
    param([Parameter(Mandatory)]$State)
    Write-ProductionDomainCollectionProtectedJson `
        -Config $State.config `
        -Path (Get-ProductionDomainCollectionTerminalReconciliationPath `
            -Config $State.config) `
        -Value ([ordered]@{
            version = 1
            targetRelease = [string]$State.targetRelease
            legacyRelease = [string]$State.legacyRelease
            evidenceDigest = [string]$State.evidenceDigest
            backupIdentity = [string]$State.backupIdentity
        })
}

function Test-ProductionDomainCollectionTerminalReconciliationAuthorization {
    param([Parameter(Mandatory)]$State)
    $path = Get-ProductionDomainCollectionTerminalReconciliationPath `
        -Config $State.config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    try {
        Assert-ProductionPathNotReparse -Path $path | Out-Null
        Assert-ProtectedProductionPath -Path $path | Out-Null
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionExactProperties -Value $value -Names @(
            'version','targetRelease','legacyRelease','evidenceDigest',
            'backupIdentity') -Description 'Terminal reconciliation authorization'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.targetRelease -isnot [string] -or
            [string]$value.targetRelease -cne [string]$State.targetRelease -or
            $value.legacyRelease -isnot [string] -or
            [string]$value.legacyRelease -cne [string]$State.legacyRelease -or
            $value.evidenceDigest -isnot [string] -or
            [string]$value.evidenceDigest -cne [string]$State.evidenceDigest -or
            $value.backupIdentity -isnot [string] -or
            [string]$value.backupIdentity -cne [string]$State.backupIdentity) {
            throw 'Terminal reconciliation authorization identity is invalid.'
        }
        return $true
    } catch {
        throw [IO.InvalidDataException]::new(
            'Terminal reconciliation authorization is invalid.', $_.Exception)
    }
}

function Remove-ProductionDomainCollectionTerminalReconciliationAuthorization {
    param([Parameter(Mandatory)]$State)
    if (-not (Test-ProductionDomainCollectionTerminalReconciliationAuthorization `
            -State $State)) {
        throw 'Terminal reconciliation authorization is missing.'
    }
    Remove-Item -LiteralPath (
        Get-ProductionDomainCollectionTerminalReconciliationPath `
            -Config $State.config) -Force -ErrorAction Stop
}

function Get-ProductionDomainCollectionPrepublicationBindingPath {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$EvidenceDigest,
        [Parameter(Mandatory)][string]$OwnerToken
    )
    Assert-ProductionDomainCollectionDigest `
        -Value $EvidenceDigest -Description 'Prepublication evidence digest'
    if ($OwnerToken -cnotmatch '^[0-9a-f]{32}$') {
        throw 'Prepublication owner identity is invalid.'
    }
    $identityBytes = [Text.Encoding]::UTF8.GetBytes(
        "v1`n$EvidenceDigest`n$OwnerToken")
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $bindingKey = ([BitConverter]::ToString($sha256.ComputeHash(
                    $identityBytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
    Join-Path $Config.programDataRoot `
        "state\domain-collection-prepublication.$bindingKey.json"
}

function Get-ProductionDomainCollectionPrepublicationReconciliationPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot `
        'state\domain-collection-prepublication-reconciliation.json'
}

function Get-ProductionDomainCollectionPriorMarkerBase64 {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionMusicSchemaDirectionPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return '' }
    $null = Read-ProductionMusicSchemaDirection -Config $Config
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
}

function Read-ProductionDomainCollectionPrepublicationBinding {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$EvidenceDigest,
        [Parameter(Mandatory)][string]$OwnerToken,
        [string]$ExpectedSha256 = ''
    )
    $path = Get-ProductionDomainCollectionPrepublicationBindingPath `
        -Config $Config -EvidenceDigest $EvidenceDigest -OwnerToken $OwnerToken
    try {
        Assert-ProductionPathNotReparse -Path $path | Out-Null
        Assert-ProtectedProductionPath -Path $path | Out-Null
        $actualSha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).
            Hash.ToLowerInvariant()
        if (-not [string]::IsNullOrEmpty($ExpectedSha256) -and
            $actualSha -cne $ExpectedSha256) {
            throw 'Prepublication binding digest changed.'
        }
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionExactProperties -Value $value -Names @(
            'version','manifestDigest','targetRelease','legacyRelease',
            'evidenceDigest','backupIdentity','evidenceFileSha256','ownerToken',
            'candidateDatabase','priorMarkerBase64','priorStateSha256',
            'historyFile') -Description 'Prepublication binding'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.manifestDigest -isnot [string] -or
            [string]$value.manifestDigest -cne $script:ManifestDigest -or
            $value.targetRelease -isnot [string] -or
            [string]$value.targetRelease -cnotmatch '^[0-9a-f]{40}$' -or
            $value.legacyRelease -isnot [string] -or
            [string]$value.legacyRelease -cnotmatch '^[0-9a-f]{40}$' -or
            $value.ownerToken -isnot [string] -or
            [string]$value.ownerToken -cnotmatch '^[0-9a-f]{32}$' -or
            $value.candidateDatabase -isnot [string] -or
            [string]$value.candidateDatabase -cnotmatch
                '^cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24}$' -or
            $value.priorMarkerBase64 -isnot [string] -or
            $value.priorStateSha256 -isnot [string] -or
            $value.historyFile -isnot [string]) {
            throw 'Prepublication binding identity is invalid.'
        }
        foreach ($name in 'evidenceDigest','backupIdentity','evidenceFileSha256') {
            Assert-ProductionDomainCollectionDigest `
                -Value $value.$name -Description "Prepublication $name"
        }
        if ([string]$value.evidenceDigest -cne $EvidenceDigest) {
            throw 'Prepublication binding evidence identity changed.'
        }
        if ([string]$value.ownerToken -cne $OwnerToken) {
            throw 'Prepublication binding owner identity changed.'
        }
        if (-not [string]::IsNullOrEmpty([string]$value.priorMarkerBase64)) {
            try {
                [void][Convert]::FromBase64String([string]$value.priorMarkerBase64)
            } catch {
                throw 'Prepublication prior marker identity is invalid.'
            }
        }
        $priorStateSha = [string]$value.priorStateSha256
        $historyFile = [string]$value.historyFile
        if ([string]::IsNullOrEmpty($priorStateSha)) {
            # The Music schema marker predates the domain-cutover state file,
            # so the first domain cutover may legitimately preserve a marker.
            if (-not [string]::IsNullOrEmpty($historyFile)) {
                throw 'First prepublication binding contains prior history.'
            }
        } else {
            Assert-ProductionDomainCollectionDigest `
                -Value $priorStateSha -Description 'Prepublication prior state'
            if ([string]::IsNullOrEmpty([string]$value.priorMarkerBase64)) {
                throw 'Terminal prepublication binding is missing its prior marker.'
            }
            $expectedHistory = Join-Path $Config.programDataRoot `
                "state\history\domain-collection-cutover.$priorStateSha.json"
            if (-not [string]::Equals(
                    [IO.Path]::GetFullPath($historyFile),
                    [IO.Path]::GetFullPath($expectedHistory),
                    [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Prepublication history path identity changed.'
            }
            Assert-ProductionPathNotReparse -Path $historyFile | Out-Null
            Assert-ProtectedProductionPath -Path $historyFile | Out-Null
            if ((Get-FileHash -LiteralPath $historyFile -Algorithm SHA256).
                    Hash.ToLowerInvariant() -cne $priorStateSha) {
                throw 'Prepublication history identity changed.'
            }
        }
        [pscustomobject][ordered]@{
            path = $path
            sha256 = $actualSha
            version = 1
            manifestDigest = [string]$value.manifestDigest
            targetRelease = [string]$value.targetRelease
            legacyRelease = [string]$value.legacyRelease
            evidenceDigest = [string]$value.evidenceDigest
            backupIdentity = [string]$value.backupIdentity
            evidenceFileSha256 = [string]$value.evidenceFileSha256
            ownerToken = [string]$value.ownerToken
            candidateDatabase = [string]$value.candidateDatabase
            priorMarkerBase64 = [string]$value.priorMarkerBase64
            priorStateSha256 = $priorStateSha
            historyFile = $historyFile
        }
    } catch {
        throw [IO.InvalidDataException]::new(
            'Protected prepublication binding is invalid.', $_.Exception)
    }
}

function Assert-ProductionDomainCollectionPrepublicationBindingMatchesState {
    param(
        [Parameter(Mandatory)]$Binding,
        [Parameter(Mandatory)]$State
    )
    if ([string]$Binding.targetRelease -cne [string]$State.targetRelease -or
        [string]$Binding.legacyRelease -cne [string]$State.legacyRelease -or
        [string]$Binding.evidenceDigest -cne [string]$State.evidenceDigest -or
        [string]$Binding.backupIdentity -cne [string]$State.backupIdentity -or
        [string]$Binding.evidenceFileSha256 -cne
            [string]$State.evidenceFileSha256 -or
        [string]$Binding.ownerToken -cne [string]$State.ownerToken -or
        [string]$Binding.candidateDatabase -cne
            [string]$State.candidateDatabase -or
        [string]$Binding.priorMarkerBase64 -cne
            [string]$State.priorMarkerBase64) {
        throw 'Protected prepublication binding does not match cutover state.'
    }
    return $Binding
}

function Write-ProductionDomainCollectionPrepublicationBinding {
    param([Parameter(Mandatory)]$Context)
    $path = Get-ProductionDomainCollectionPrepublicationBindingPath `
        -Config $Context.config -EvidenceDigest $Context.evidenceDigest `
        -OwnerToken $Context.ownerToken
    $value = [ordered]@{
        version = 1
        manifestDigest = $script:ManifestDigest
        targetRelease = [string]$Context.targetRelease
        legacyRelease = [string]$Context.legacyRelease
        evidenceDigest = [string]$Context.evidenceDigest
        backupIdentity = [string]$Context.backupIdentity
        evidenceFileSha256 = [string]$Context.evidenceFileSha256
        ownerToken = [string]$Context.ownerToken
        candidateDatabase = [string]$Context.candidateDatabase
        priorMarkerBase64 = [string]$Context.priorMarkerBase64
        priorStateSha256 = [string]$Context.priorStateSha256
        historyFile = [string]$Context.historyFile
    }
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $existing = Read-ProductionDomainCollectionPrepublicationBinding `
            -Config $Context.config -EvidenceDigest $Context.evidenceDigest `
            -OwnerToken $Context.ownerToken
        Assert-ProductionDomainCollectionPrepublicationBindingMatchesState `
            -Binding $existing -State $Context
        return $existing
    }
    Write-ProductionDomainCollectionProtectedJson `
        -Config $Context.config -Path $path -Value $value
    $binding = Read-ProductionDomainCollectionPrepublicationBinding `
        -Config $Context.config -EvidenceDigest $Context.evidenceDigest `
        -OwnerToken $Context.ownerToken
    Assert-ProductionDomainCollectionPrepublicationBindingMatchesState `
        -Binding $binding -State $Context
}

function Write-ProductionDomainCollectionPrepublicationReconciliation {
    param([Parameter(Mandatory)]$Context, [Parameter(Mandatory)]$Binding)
    Write-ProductionDomainCollectionProtectedJson `
        -Config $Context.config `
        -Path (Get-ProductionDomainCollectionPrepublicationReconciliationPath `
            -Config $Context.config) `
        -Value ([ordered]@{
            version = 1
            evidenceDigest = [string]$Binding.evidenceDigest
            ownerToken = [string]$Binding.ownerToken
            bindingSha256 = [string]$Binding.sha256
        })
}

function Read-ProductionDomainCollectionPrepublicationReconciliation {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionDomainCollectionPrepublicationReconciliationPath `
        -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        Assert-ProductionPathNotReparse -Path $path | Out-Null
        Assert-ProtectedProductionPath -Path $path | Out-Null
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionExactProperties -Value $value -Names @(
            'version','evidenceDigest','ownerToken','bindingSha256') `
            -Description 'Prepublication reconciliation'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1) {
            throw 'Prepublication reconciliation version is invalid.'
        }
        foreach ($name in 'evidenceDigest','bindingSha256') {
            Assert-ProductionDomainCollectionDigest `
                -Value $value.$name -Description "Prepublication reconciliation $name"
        }
        if ($value.ownerToken -isnot [string] -or
            [string]$value.ownerToken -cnotmatch '^[0-9a-f]{32}$') {
            throw 'Prepublication reconciliation owner identity is invalid.'
        }
        $binding = Read-ProductionDomainCollectionPrepublicationBinding `
            -Config $Config -EvidenceDigest ([string]$value.evidenceDigest) `
            -OwnerToken ([string]$value.ownerToken) `
            -ExpectedSha256 ([string]$value.bindingSha256)
        [pscustomobject][ordered]@{
            path = $path
            binding = $binding
        }
    } catch {
        throw [IO.InvalidDataException]::new(
            'Protected prepublication reconciliation or binding is invalid.',
            $_.Exception)
    }
}

function Remove-ProductionDomainCollectionPrepublicationReconciliation {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Binding)
    $current = Read-ProductionDomainCollectionPrepublicationReconciliation `
        -Config $Config
    if (-not $current -or
        [string]$current.binding.sha256 -cne [string]$Binding.sha256) {
        throw 'Prepublication reconciliation identity changed before removal.'
    }
    Remove-Item -LiteralPath $current.path -Force -ErrorAction Stop
}

function Test-ProductionDomainCollectionPrepublicationMarker {
    param([AllowNull()]$Marker, [Parameter(Mandatory)]$Binding)
    return $null -ne $Marker -and
        [int]$Marker.version -eq 2 -and
        [string]$Marker.state -ceq 'ROLLBACK_IN_PROGRESS' -and
        [string]$Marker.targetRelease -ceq [string]$Binding.targetRelease -and
        [string]$Marker.currentRelease -ceq [string]$Binding.legacyRelease -and
        [string]$Marker.legacyRelease -ceq [string]$Binding.legacyRelease -and
        [string]$Marker.manifestDigest -ceq $script:ManifestDigest -and
        [string]$Marker.evidenceDigest -ceq [string]$Binding.evidenceDigest -and
        [string]$Marker.backupIdentity -ceq [string]$Binding.backupIdentity -and
        -not [bool]$Marker.legacyDropped
}

function Test-ProductionDomainCollectionPriorMarkerIdentity {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Binding)
    $path = Get-ProductionMusicSchemaDirectionPath -Config $Config
    if ([string]::IsNullOrEmpty([string]$Binding.priorMarkerBase64)) {
        return -not (Test-Path -LiteralPath $path -PathType Leaf)
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    Assert-ProductionPathNotReparse -Path $path | Out-Null
    Assert-ProtectedProductionPath -Path $path | Out-Null
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) -ceq
        [string]$Binding.priorMarkerBase64
}

function Test-ProductionDomainCollectionPriorStateIdentity {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Binding)
    $path = Get-ProductionDomainCollectionStatePath -Config $Config
    if ([string]::IsNullOrEmpty([string]$Binding.priorStateSha256)) {
        return -not (Test-Path -LiteralPath $path -PathType Leaf)
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    Assert-ProductionPathNotReparse -Path $path | Out-Null
    Assert-ProtectedProductionPath -Path $path | Out-Null
    (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() `
        -ceq [string]$Binding.priorStateSha256
}

function Publish-ProductionDomainCollectionPrepublicationContext {
    param([Parameter(Mandatory)]$Context)
    $binding = Write-ProductionDomainCollectionPrepublicationBinding `
        -Context $Context
    Write-ProductionDomainCollectionPrepublicationReconciliation `
        -Context $Context -Binding $binding
    Write-ProductionDomainCollectionRollbackMarker `
        -State $Context -MarkerState ROLLBACK_IN_PROGRESS
    Save-ProductionDomainCollectionContextState -Context $Context -State PREVIEWED
    Remove-ProductionDomainCollectionPrepublicationReconciliation `
        -Config $Context.config -Binding $binding
}

function Resolve-ProductionDomainCollectionPrepublicationPublication {
    param([Parameter(Mandatory)]$Config)
    $reconciliation =
        Read-ProductionDomainCollectionPrepublicationReconciliation `
            -Config $Config
    if (-not $reconciliation) { return }
    $binding = $reconciliation.binding
    $marker = Read-ProductionDomainSchemaDirection -Config $Config
    $markerIsPrepublication = Test-ProductionDomainCollectionPrepublicationMarker `
        -Marker $marker -Binding $binding
    $markerIsPrior = Test-ProductionDomainCollectionPriorMarkerIdentity `
        -Config $Config -Binding $binding
    $stateIsPrior = Test-ProductionDomainCollectionPriorStateIdentity `
        -Config $Config -Binding $binding
    if ($markerIsPrepublication) {
        if ($stateIsPrior) {
            Restore-ProductionDomainCollectionLegacyRelease -State ([pscustomobject]@{
                config = $Config
                priorMarkerBase64 = [string]$binding.priorMarkerBase64
            })
            Remove-ProductionDomainCollectionPrepublicationReconciliation `
                -Config $Config -Binding $binding
            return
        }
        $state = Read-ProductionDomainCollectionProtectedState -Config $Config
        if ([string]$state.state -cne 'PREVIEWED') {
            throw 'One-sided prepublication reconciliation found an invalid state.'
        }
        Assert-ProductionDomainCollectionPrepublicationBindingMatchesState `
            -Binding $binding -State $state | Out-Null
        Remove-ProductionDomainCollectionPrepublicationReconciliation `
            -Config $Config -Binding $binding
        return
    }
    if ($markerIsPrior -and $stateIsPrior) {
        Remove-ProductionDomainCollectionPrepublicationReconciliation `
            -Config $Config -Binding $binding
        return
    }
    throw ('One-sided prepublication reconciliation does not match the exact ' +
        'prior or committed marker/state boundary.')
}

function Resolve-ProductionDomainCollectionPrepublicationForCutoverRetry {
    param([Parameter(Mandatory)]$Config)
    Resolve-ProductionDomainCollectionPrepublicationPublication -Config $Config
    $marker = Read-ProductionDomainSchemaDirection -Config $Config
    if (-not $marker -or
        [string]$marker.state -cne 'ROLLBACK_IN_PROGRESS') {
        return
    }
    $state = Read-ProductionDomainCollectionProtectedState -Config $Config
    if ([string]$state.state -cnotin @(
            'PREVIEWED','CANDIDATE_VERIFIED','LIVE_PUBLISHED')) {
        throw ('The rollback barrier belongs to a non-prepublication recovery ' +
            'state; cutover retry is blocked.')
    }
    Invoke-ProductionDomainCollectionFailureRecovery `
        -Context $state -PostDrop:$false
}

function Save-ProductionDomainCollectionContextState {
    param([Parameter(Mandatory)]$Context, [Parameter(Mandatory)][string]$State)
    $current = if ($Context.PSObject.Properties['state']) {
        [string]$Context.state
    } else { 'INITIALIZED' }
    Assert-ProductionDomainCollectionStateTransition -Current $current -Next $State
    $value = [ordered]@{
        version = 1
        state = $State
        updatedAtEpochMillis = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        targetRelease = [string]$Context.targetRelease
        legacyRelease = [string]$Context.legacyRelease
        archive = [string]$Context.archive
        backupIdentity = [string]$Context.backupIdentity
        evidenceDigest = [string]$Context.evidenceDigest
        evidenceFile = [string]$Context.evidenceFile
        evidenceFileSha256 = [string]$Context.evidenceFileSha256
        ownerToken = [string]$Context.ownerToken
        candidateDatabase = [string]$Context.candidateDatabase
        legacyDropped = [bool]$Context.dropStarted
        priorMarkerBase64 = [string]$Context.priorMarkerBase64
    }
    if ($State -ceq 'ROLLED_BACK') {
        Write-ProductionDomainCollectionTerminalReconciliationAuthorization `
            -State $Context
    }
    Write-ProductionDomainCollectionProtectedJson `
        -Config $Context.config `
        -Path (Get-ProductionDomainCollectionStatePath -Config $Context.config) `
        -Value $value
    $Context.state = $State
    if ($State -ceq 'ROLLED_BACK') {
        Remove-ProductionDomainCollectionTerminalReconciliationAuthorization `
            -State $Context
    }
}

function Restore-ProductionDomainCollectionSnapshotInitializationFailure {
    param([Parameter(Mandatory)]$Context)

    Resolve-ProductionDomainCollectionPrepublicationPublication `
        -Config $Context.config
    $marker = Read-ProductionDomainSchemaDirection -Config $Context.config
    if ($marker -and [string]$marker.state -ceq 'ROLLBACK_IN_PROGRESS') {
        $state = Read-ProductionDomainCollectionProtectedState `
            -Config $Context.config
        Invoke-ProductionDomainCollectionFailureRecovery `
            -Context $state -PostDrop:$false
        return
    }
    Restore-ProductionDomainCollectionLegacyRelease -State $Context
    Start-ProductionDomainCollectionLegacy -State $Context
    try {
        Set-ProductionWebsiteRecoveryPolicy -Policy Normal
    } catch {
        $normalizationFailure = $_.Exception
        try {
            Stop-ProductionDomainCollectionWriter -Context $Context
        } catch {
            throw [AggregateException]::new(
                'Legacy recovery normalization and writer containment both failed.',
                [Exception[]]@($normalizationFailure, $_.Exception))
        }
        throw $normalizationFailure
    }
}

function New-ProductionDomainCollectionCutoverContext {
    param([Parameter(Mandatory)]$Config)

    $terminalState = Get-ProductionDomainCollectionTerminalReinitializationState `
        -Config $Config
    $targetRelease = Resolve-OriginMainRelease $Config
    $targetPath = New-ReleaseFromOriginMain $Config $targetRelease
    if ((Get-ProductionDomainCollectionReleaseSchema `
            -Config $Config -Release $targetPath -Sha $targetRelease) -cne 'TARGET') {
        throw 'Domain collection cutover requires an exact target-schema release.'
    }
    $legacyPath = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    if (-not $legacyPath) { throw 'Domain collection cutover requires an active legacy release.' }
    $legacyRelease = Split-Path -Leaf $legacyPath
    if ($legacyRelease -cnotmatch '^[0-9a-f]{40}$' -or
        (Get-ProductionDomainCollectionReleaseSchema `
            -Config $Config -Release $legacyPath -Sha $legacyRelease) -cne 'LEGACY') {
        throw 'Domain collection cutover requires a proven legacy domain-schema release.'
    }
    $priorMarkerBase64 = Get-ProductionDomainCollectionPriorMarkerBase64 `
        -Config $Config
    $snapshotContext = [pscustomobject][ordered]@{
        config = $Config
        targetRelease = $targetRelease
        targetPath = $targetPath
        currentRelease = $legacyRelease
        legacyRelease = $legacyRelease
        legacyPath = $legacyPath
        priorMarkerBase64 = $priorMarkerBase64
        dropStarted = $false
        writerStopped = $false
        state = 'INITIALIZED'
    }
    Stop-ProductionDomainCollectionWriter -Context $snapshotContext
    $snapshotContext.writerStopped = $true
    try {
        $backup = New-ProductionDomainCollectionVerifiedBackup -Config $Config
        $owner = [guid]::NewGuid().ToString('N')
        $preview = Invoke-ProductionDomainCollectionEngine `
            -Config $Config -Database $script:ProductionDatabase -Action preview `
            -OwnerToken $owner -Release $targetRelease `
            -BackupIdentity $backup.backupIdentity -EvidenceDigest ('0' * 64)
        $evidenceFile = Join-Path $Config.programDataRoot `
            "state\domain-collection-evidence.$($preview.evidenceDigest).json"
        Write-ProductionDomainCollectionProtectedJson `
            -Config $Config -Path $evidenceFile -Value $preview.evidence -Depth 100
        $evidenceFileSha = (Get-FileHash -LiteralPath $evidenceFile -Algorithm SHA256).Hash.ToLowerInvariant()
        $candidate = New-CandidateDatabaseName -Sha $targetRelease
        Assert-ProductionDomainCollectionCandidateIsolation `
            -Database $candidate `
            -CandidatePort ([int]$Config.candidatePort) `
            -ProductionPort ([int]$Config.productionPort)
        $historyFile = ''
        $priorStateSha256 = ''
        if ($terminalState) {
            $historyFile = Archive-ProductionDomainCollectionTerminalState `
                -State $terminalState
            $priorStateSha256 = (Get-FileHash -LiteralPath $historyFile `
                -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $context = [pscustomobject][ordered]@{
            config = $Config
            targetRelease = $targetRelease
            targetPath = $targetPath
            currentRelease = $legacyRelease
            legacyRelease = $legacyRelease
            legacyPath = $legacyPath
            archive = [string]$backup.archive
            backupIdentity = [string]$backup.backupIdentity
            evidenceDigest = [string]$preview.evidenceDigest
            evidence = $preview.evidence
            evidenceFile = $evidenceFile
            evidenceFileSha256 = $evidenceFileSha
            ownerToken = $owner
            candidateDatabase = $candidate
            priorMarkerBase64 = $priorMarkerBase64
            priorStateSha256 = $priorStateSha256
            historyFile = $historyFile
            dropStarted = $false
            writerStopped = $true
            state = 'INITIALIZED'
        }
        Publish-ProductionDomainCollectionPrepublicationContext -Context $context
        return $context
    } catch {
        $initializationFailure = $_.Exception
        try {
            Restore-ProductionDomainCollectionSnapshotInitializationFailure `
                -Context $snapshotContext
        } catch {
            throw [AggregateException]::new(
                'Domain collection snapshot initialization and guarded recovery both failed.',
                [Exception[]]@($initializationFailure, $_.Exception))
        }
        throw $initializationFailure
    }
}

function Invoke-ProductionDomainCollectionUntilComplete {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Action,
        [ValidateRange(1,1000)][int]$MaximumOperations = 500
    )
    for ($operation = 0; $operation -lt $MaximumOperations; $operation++) {
        $result = Invoke-ProductionDomainCollectionEngine `
            -Config $Context.config -Database $Database -Action $Action `
            -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
            -BackupIdentity $Context.backupIdentity `
            -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
        if ([bool]$result.complete) { return $result }
        if ([string]$result.nextOperation -cne $Action) {
            throw 'Mongo migration engine returned an invalid continuation.'
        }
    }
    throw 'Mongo migration exceeded its bounded operation count.'
}

function Invoke-ProductionDomainCollectionCandidateProof {
    param([Parameter(Mandatory)]$Context)
    $database = [string]$Context.candidateDatabase
    Assert-ProductionDomainCollectionCandidateIsolation `
        -Database $database `
        -CandidatePort ([int]$Context.config.candidatePort) `
        -ProductionPort ([int]$Context.config.productionPort)
    try {
        Restore-CandidateDatabaseFromBackup `
            -Config $Context.config -Archive $Context.archive -Database $database
        $null = Invoke-ProductionDomainCollectionEngine `
            -Config $Context.config -Database $database -Action restore-verify `
            -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
            -BackupIdentity $Context.backupIdentity `
            -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
        $null = Invoke-ProductionDomainCollectionUntilComplete `
            -Context $Context -Database $database -Action stage
        $null = Invoke-ProductionDomainCollectionEngine `
            -Config $Context.config -Database $database -Action verify-stage `
            -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
            -BackupIdentity $Context.backupIdentity `
            -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
        $null = Invoke-ProductionDomainCollectionUntilComplete `
            -Context $Context -Database $database -Action publish-next
        $null = Invoke-ProductionDomainCollectionEngine `
            -Config $Context.config -Database $database -Action verify-live `
            -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
            -BackupIdentity $Context.backupIdentity `
            -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
        Test-CandidateRelease `
            -Config $Context.config -Release $Context.targetPath -Database $database
        $null = Invoke-ProductionDomainCollectionUntilComplete `
            -Context $Context -Database $database -Action drop-legacy
        $null = Invoke-ProductionDomainCollectionEngine `
            -Config $Context.config -Database $database -Action verify-live `
            -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
            -BackupIdentity $Context.backupIdentity `
            -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
        Save-ProductionDomainCollectionContextState `
            -Context $Context -State CANDIDATE_VERIFIED
    } finally {
        Remove-CandidateDatabase -Config $Context.config -Database $database
    }
}

function Stop-ProductionDomainCollectionWriter {
    param([Parameter(Mandatory)]$Context)
    Stop-ProductionWebsiteService `
        -ProductionPort ([int]$Context.config.productionPort) `
        -KeepRecoverySuspended
}

function Invoke-ProductionDomainCollectionStageAndPublish {
    param([Parameter(Mandatory)]$Context)
    $null = Invoke-ProductionDomainCollectionUntilComplete `
        -Context $Context -Database $script:ProductionDatabase -Action stage
    $null = Invoke-ProductionDomainCollectionEngine `
        -Config $Context.config -Database $script:ProductionDatabase -Action verify-stage `
        -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
        -BackupIdentity $Context.backupIdentity `
        -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
    $null = Invoke-ProductionDomainCollectionUntilComplete `
        -Context $Context -Database $script:ProductionDatabase -Action publish-next
    $null = Invoke-ProductionDomainCollectionEngine `
        -Config $Context.config -Database $script:ProductionDatabase -Action verify-live `
        -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
        -BackupIdentity $Context.backupIdentity `
        -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
    Save-ProductionDomainCollectionContextState -Context $Context -State LIVE_PUBLISHED
}

function Prepare-ProductionDomainCollectionTargetCutover {
    param([Parameter(Mandatory)]$Context)
    Write-ProductionDomainSchemaDirection `
        -Config $Context.config -State TARGET_CUTOVER_IN_PROGRESS `
        -TargetRelease $Context.targetRelease -LegacyRelease $Context.legacyRelease `
        -CurrentRelease $Context.targetRelease `
        -EvidenceDigest $Context.evidenceDigest `
        -BackupIdentity $Context.backupIdentity -LegacyDropped:$false | Out-Null
    Save-ProductionDomainCollectionContextState -Context $Context -State TARGET_START_PENDING
}

function Invoke-ProductionDomainCollectionDropLegacy {
    param([Parameter(Mandatory)]$Context)
    $Context.dropStarted = $true
    Save-ProductionDomainCollectionContextState -Context $Context -State DROP_STARTED
    $null = Invoke-ProductionDomainCollectionUntilComplete `
        -Context $Context -Database $script:ProductionDatabase -Action drop-legacy
    $null = Invoke-ProductionDomainCollectionEngine `
        -Config $Context.config -Database $script:ProductionDatabase -Action verify-live `
        -OwnerToken $Context.ownerToken -Release $Context.targetRelease `
        -BackupIdentity $Context.backupIdentity `
        -EvidenceDigest $Context.evidenceDigest -Evidence $Context.evidence
    Save-ProductionDomainCollectionContextState -Context $Context -State LEGACY_DROPPED
}

function Complete-ProductionDomainCollectionCutover {
    param([Parameter(Mandatory)]$Context)
    Write-ProductionDomainSchemaDirection `
        -Config $Context.config -State TARGET_ACTIVE `
        -TargetRelease $Context.targetRelease -LegacyRelease $Context.legacyRelease `
        -CurrentRelease $Context.targetRelease `
        -EvidenceDigest $Context.evidenceDigest `
        -BackupIdentity $Context.backupIdentity -LegacyDropped:$true | Out-Null
    Save-ProductionDomainCollectionContextState -Context $Context -State TARGET_ACTIVE
    Switch-ProductionRelease `
        -Config $Context.config -Release $Context.targetPath `
        -AuthorizationMarkerState TARGET_ACTIVE `
        -AuthorizationPurpose TARGET_DEPLOY `
        -AuthorizationRelease $Context.targetRelease `
        -KeepRecoverySuspended -WriterAlreadyStopped
    Set-ProductionWebsiteRecoveryPolicy -Policy Normal
    Update-ProductionAutoDeployToolsUnderHeldLock -Config $Context.config
}

function Invoke-ProductionDomainCollectionFailureRecovery {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][bool]$PostDrop
    )
    try {
        Stop-ProductionDomainCollectionWriter -Context $Context
        Invoke-ProductionDomainCollectionRollbackStateMachine `
            -State $Context -PostDrop:$PostDrop
    } catch {
        Stop-ProductionDomainCollectionRollbackAfterFailure `
            -State $Context -Failure $_.Exception
    }
}

function Read-ProductionDomainCollectionProtectedState {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionDomainCollectionStatePath -Config $Config
    try {
        Assert-ProtectedProductionPath -Path $path | Out-Null
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionExactProperties -Value $value -Names @(
            'version','state','updatedAtEpochMillis','targetRelease','legacyRelease',
            'archive','backupIdentity','evidenceDigest','evidenceFile',
            'evidenceFileSha256','ownerToken','candidateDatabase','legacyDropped',
            'priorMarkerBase64') -Description 'Protected cutover state'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.state -isnot [string] -or
            [string]$value.state -cnotin @(
                'PREVIEWED','CANDIDATE_VERIFIED','LIVE_PUBLISHED',
                'TARGET_START_PENDING','DROP_STARTED','LEGACY_DROPPED',
                'TARGET_ACTIVE','ROLLBACK_VERIFIED','LEGACY_DATA_VERIFIED',
                'ROLLBACK_READY','ROLLED_BACK') -or
            ($value.updatedAtEpochMillis -isnot [int] -and
                $value.updatedAtEpochMillis -isnot [long]) -or
            [long]$value.updatedAtEpochMillis -lt 1 -or
            $value.targetRelease -isnot [string] -or
            [string]$value.targetRelease -cnotmatch '^[0-9a-f]{40}$' -or
            $value.legacyRelease -isnot [string] -or
            [string]$value.legacyRelease -cnotmatch '^[0-9a-f]{40}$' -or
            $value.ownerToken -isnot [string] -or
            [string]$value.ownerToken -cnotmatch '^[0-9a-f]{32}$' -or
            $value.candidateDatabase -isnot [string] -or
            [string]$value.candidateDatabase -cnotmatch
                '^cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24}$' -or
            $value.legacyDropped -isnot [bool]) {
            throw 'Protected cutover state identity is invalid.'
        }
        if ($value.priorMarkerBase64 -isnot [string]) {
            throw 'Protected prior schema marker identity is invalid.'
        }
        if (-not [string]::IsNullOrEmpty([string]$value.priorMarkerBase64)) {
            try {
                [void][Convert]::FromBase64String([string]$value.priorMarkerBase64)
            } catch {
                throw 'Protected prior schema marker identity is invalid.'
            }
        }
        foreach ($name in 'backupIdentity','evidenceDigest','evidenceFileSha256') {
            Assert-ProductionDomainCollectionDigest `
                -Value $value.$name -Description "Protected cutover $name"
        }
        $archive = [IO.Path]::GetFullPath([string]$value.archive)
        $backupRoot = [IO.Path]::GetFullPath([string]$Config.backupRoot).TrimEnd('\')
        $evidenceFile = [IO.Path]::GetFullPath([string]$value.evidenceFile)
        $stateRoot = [IO.Path]::GetFullPath(
            (Join-Path $Config.programDataRoot 'state')).TrimEnd('\')
        if (-not $archive.StartsWith(
                $backupRoot + '\',[StringComparison]::OrdinalIgnoreCase) -or
            -not $evidenceFile.StartsWith(
                $stateRoot + '\',[StringComparison]::OrdinalIgnoreCase)) {
            throw 'Protected cutover paths escaped their fixed roots.'
        }
        foreach ($file in $archive,"$archive.sha256.json",$evidenceFile) {
            Assert-ProductionPathNotReparse -Path $file | Out-Null
            Assert-ProtectedProductionPath -Path $file | Out-Null
        }
        $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        $evidenceHash = (Get-FileHash -LiteralPath $evidenceFile -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($archiveHash -cne [string]$value.backupIdentity -or
            $evidenceHash -cne [string]$value.evidenceFileSha256) {
            throw 'Protected cutover archive or evidence identity changed.'
        }
        $sidecar = Get-Content -LiteralPath "$archive.sha256.json" -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionExactProperties `
            -Value $sidecar -Names @('archive','sha256','createdAt') `
            -Description 'Protected backup sidecar'
        if (-not [string]::Equals(
                [IO.Path]::GetFullPath([string]$sidecar.archive),$archive,
                [StringComparison]::OrdinalIgnoreCase) -or
            $sidecar.sha256 -isnot [string] -or
            [string]$sidecar.sha256.ToLowerInvariant() -cne $archiveHash) {
            throw 'Protected backup sidecar is not bound to the archive.'
        }
        $evidence = Get-Content -LiteralPath $evidenceFile -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ProductionDomainCollectionEngineEvidence -Evidence $evidence
        if ([string]$evidence.manifestDigest -cne $script:ManifestDigest -or
            [string]$evidence.release -cne [string]$value.targetRelease -or
            [string]$evidence.backupIdentity -cne [string]$value.backupIdentity) {
            throw 'Protected evidence is not bound to the cutover state.'
        }
        $domainMarker = Read-ProductionDomainSchemaDirection -Config $Config
        $markerRequired = [string]$value.state -in @(
            'PREVIEWED','CANDIDATE_VERIFIED','LIVE_PUBLISHED',
            'TARGET_START_PENDING','DROP_STARTED','LEGACY_DROPPED','TARGET_ACTIVE',
            'ROLLBACK_VERIFIED','LEGACY_DATA_VERIFIED','ROLLBACK_READY','ROLLED_BACK')
        if ($markerRequired -and -not $domainMarker) {
            throw 'Protected domain schema marker is missing for the recovery state.'
        }
        if ($domainMarker -and
            ([string]$domainMarker.targetRelease -cne [string]$value.targetRelease -or
                [string]$domainMarker.legacyRelease -cne [string]$value.legacyRelease -or
                [string]$domainMarker.manifestDigest -cne $script:ManifestDigest -or
                [string]$domainMarker.evidenceDigest -cne [string]$value.evidenceDigest -or
                [string]$domainMarker.backupIdentity -cne [string]$value.backupIdentity)) {
            throw 'Protected domain schema marker does not match recovery state.'
        }
        if ([string]$value.state -eq 'TARGET_ACTIVE' -and
            (-not $domainMarker.legacyDropped -or -not [bool]$value.legacyDropped)) {
            throw 'Protected target-active deletion state is inconsistent.'
        }
        if ([string]$value.state -in @(
                'PREVIEWED','CANDIDATE_VERIFIED','LIVE_PUBLISHED') -and
            ([string]$domainMarker.state -cne 'ROLLBACK_IN_PROGRESS' -or
                [bool]$domainMarker.legacyDropped -or
                [string]$domainMarker.currentRelease -cne
                    [string]$value.legacyRelease)) {
            throw 'Protected prepublication state is missing its exact startup barrier.'
        }
        if ([string]$value.state -eq 'ROLLBACK_VERIFIED' -and
            [string]$domainMarker.state -cne 'ROLLBACK_IN_PROGRESS') {
            throw 'Protected rollback verification is missing its startup barrier.'
        }
        if ([string]$value.state -eq 'LEGACY_DATA_VERIFIED' -and
            [string]$domainMarker.state -cne 'ROLLBACK_IN_PROGRESS') {
            throw 'Protected restored data is missing its startup barrier.'
        }
        if ([string]$value.state -eq 'ROLLBACK_READY' -and
            [string]$domainMarker.state -cnotin @(
                'ROLLBACK_IN_PROGRESS','TARGET_CUTOVER_IN_PROGRESS',
                'LEGACY_ACTIVE_RECONCILIATION_REQUIRED')) {
            throw 'Protected rollback-ready state has an unsafe startup marker.'
        }
        $state = [pscustomobject][ordered]@{
            config = $Config
            state = [string]$value.state
            targetRelease = [string]$value.targetRelease
            currentRelease = if ($domainMarker) {
                [string]$domainMarker.currentRelease
            } else { [string]$value.targetRelease }
            targetPath = Join-Path $Config.programDataRoot `
                "releases\$($value.targetRelease)"
            legacyRelease = [string]$value.legacyRelease
            legacyPath = Join-Path $Config.programDataRoot `
                "releases\$($value.legacyRelease)"
            archive = $archive
            backupIdentity = [string]$value.backupIdentity
            evidenceDigest = [string]$value.evidenceDigest
            evidenceFile = $evidenceFile
            evidenceFileSha256 = [string]$value.evidenceFileSha256
            evidence = $evidence
            ownerToken = [string]$value.ownerToken
            candidateDatabase = [string]$value.candidateDatabase
            dropStarted = [bool]$value.legacyDropped
            legacyDropped = [bool]$value.legacyDropped
            priorMarkerBase64 = [string]$value.priorMarkerBase64
            terminalReconciliation = [string]$value.state -eq 'ROLLED_BACK'
            terminalReconciliationAuthorized = $false
        }
        if ([string]$value.state -in @(
                'PREVIEWED','CANDIDATE_VERIFIED','LIVE_PUBLISHED')) {
            $binding = Read-ProductionDomainCollectionPrepublicationBinding `
                -Config $Config -EvidenceDigest ([string]$value.evidenceDigest) `
                -OwnerToken ([string]$value.ownerToken)
            Assert-ProductionDomainCollectionPrepublicationBindingMatchesState `
                -Binding $binding -State $state | Out-Null
            $state | Add-Member NoteProperty priorStateSha256 `
                ([string]$binding.priorStateSha256)
            $state | Add-Member NoteProperty historyFile `
                ([string]$binding.historyFile)
        }
        if ([string]$value.state -eq 'ROLLED_BACK') {
            Assert-ProductionDomainCollectionTerminalLegacyState `
                -Config $Config -Marker $domainMarker -State $state | Out-Null
            $state.terminalReconciliationAuthorized =
                Test-ProductionDomainCollectionTerminalReconciliationAuthorization `
                    -State $state
        }
        return $state
    } catch {
        throw [IO.InvalidDataException]::new(
            'Protected domain collection recovery state is invalid.', $_.Exception)
    }
}

function Restore-ProductionDomainCollectionBackup {
    param([Parameter(Mandatory)]$State)
    $actualHash = (Get-FileHash -LiteralPath $State.archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -cne [string]$State.backupIdentity) {
        throw 'Domain collection restore archive identity changed.'
    }
    $null = Invoke-ProductionDomainCollectionUntilComplete `
        -Context $State -Database $script:ProductionDatabase `
        -Action prepare-restore
    Invoke-CheckedProcess `
        -FilePath (Join-Path $State.config.mongoToolsPath 'mongorestore.exe') `
        -ArgumentList @(
            '--uri=mongodb://127.0.0.1:27017',
            "--archive=$($State.archive)",'--gzip',
            '--nsInclude=christopherbell.*') `
        -WorkingDirectory $State.config.repositoryPath | Out-Null
    $null = Invoke-ProductionDomainCollectionEngine `
        -Config $State.config -Database $script:ProductionDatabase `
        -Action restore-verify -OwnerToken $State.ownerToken `
        -Release $State.targetRelease -BackupIdentity $State.backupIdentity `
        -EvidenceDigest $State.evidenceDigest -Evidence $State.evidence
    Save-ProductionDomainCollectionContextState `
        -Context $State -State LEGACY_DATA_VERIFIED
}

function Assert-ProductionDomainCollectionRollbackFreshness {
    param([Parameter(Mandatory)]$State)
    $result = Invoke-ProductionDomainCollectionEngine `
        -Config $State.config -Database $script:ProductionDatabase `
        -Action verify-live -OwnerToken $State.ownerToken `
        -Release $State.targetRelease -BackupIdentity $State.backupIdentity `
        -EvidenceDigest $State.evidenceDigest -Evidence $State.evidence
    if (-not [bool]$result.complete -or [string]$result.state -cne 'TARGET_ACTIVE') {
        throw 'The stopped target database no longer matches the rollback-bound snapshot.'
    }
}

function Reverse-ProductionDomainCollectionPublication {
    param([Parameter(Mandatory)]$State)
    $null = Invoke-ProductionDomainCollectionUntilComplete `
        -Context $State -Database $script:ProductionDatabase -Action reverse-next
}

function Recover-ProductionDomainCollectionPrepublication {
    param([Parameter(Mandatory)]$State)
    $null = Invoke-ProductionDomainCollectionUntilComplete `
        -Context $State -Database $script:ProductionDatabase `
        -Action recover-prepublication
}

function Restore-ProductionDomainCollectionLegacyRelease {
    param([Parameter(Mandatory)]$State)
    $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $State.config
    if ([string]::IsNullOrEmpty([string]$State.priorMarkerBase64)) {
        if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
            Remove-Item -LiteralPath $markerPath -Force
        }
        return
    }
    $bytes = try {
        [Convert]::FromBase64String([string]$State.priorMarkerBase64)
    } catch {
        throw [IO.InvalidDataException]::new(
            'The prior schema marker recovery identity is invalid.', $_.Exception)
    }
    $temporary = "$markerPath.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllBytes($temporary, $bytes)
        Protect-ProductionPath -Path $temporary
        Assert-ProtectedProductionPath -Path $temporary | Out-Null
        Move-Item -LiteralPath $temporary -Destination $markerPath -Force
        Protect-ProductionPath -Path $markerPath
        Assert-ProtectedProductionPath -Path $markerPath | Out-Null
        $null = Read-ProductionMusicSchemaDirection -Config $State.config
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Start-ProductionDomainCollectionLegacy {
    param([Parameter(Mandatory)]$State)
    Ensure-ProductionWriterStartGuardUnderHeldLock -Config $State.config
    Switch-ProductionRelease `
        -Config $State.config -Release $State.legacyPath `
        -KeepRecoverySuspended -WriterAlreadyStopped
}

function Write-ProductionDomainCollectionRollbackMarker {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)]
        [ValidateSet('ROLLBACK_IN_PROGRESS','LEGACY_ACTIVE_RECONCILIATION_REQUIRED')]
        [string]$MarkerState
    )
    $currentRelease = if ($State.PSObject.Properties['currentRelease'] -and
        [string]$State.currentRelease -cmatch '^[0-9a-f]{40}$') {
        [string]$State.currentRelease
    } else { [string]$State.targetRelease }
    $legacyDropped = if ($MarkerState -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
        $false
    } elseif ($State.PSObject.Properties['legacyDropped']) {
        [bool]$State.legacyDropped
    } elseif ($State.PSObject.Properties['dropStarted']) {
        [bool]$State.dropStarted
    } else { $false }
    Write-ProductionDomainSchemaDirection `
        -Config $State.config -State $MarkerState `
        -TargetRelease $State.targetRelease `
        -CurrentRelease $currentRelease `
        -LegacyRelease $State.legacyRelease `
        -EvidenceDigest $State.evidenceDigest `
        -BackupIdentity $State.backupIdentity `
        -LegacyDropped:$legacyDropped | Out-Null
}

function Stop-ProductionDomainCollectionRollbackAfterFailure {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][Exception]$Failure
    )
    try {
        Stop-ProductionDomainCollectionWriter -Context $State
    } catch {
        throw [AggregateException]::new(
            'Domain collection rollback failed and the stopped writer with suspended recovery could not be reproven.',
            [Exception[]]@($Failure,$_.Exception))
    }
    throw [InvalidOperationException]::new(
        ('Domain collection rollback failed; ChristopherBellDev remains stopped with recovery suspended. ' +
            'Retry mongo-consolidation-rollback under deploy.lock.'),
        $Failure)
}

function Invoke-ProductionDomainCollectionRollbackStateMachine {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][bool]$PostDrop
    )
    $stateName = [string]$State.state
    if ($stateName -ceq 'ROLLED_BACK') {
        if (-not $State.PSObject.Properties['terminalReconciliation'] -or
            -not [bool]$State.terminalReconciliation) {
            throw 'Terminal rollback reconciliation requires exact protected readback.'
        }
        if (-not $State.PSObject.Properties['terminalReconciliationAuthorized'] -or
            -not [bool]$State.terminalReconciliationAuthorized) {
            throw 'Terminal rollback reconciliation requires one-shot authorization.'
        }
        Write-ProductionDomainCollectionRollbackMarker `
            -State $State -MarkerState LEGACY_ACTIVE_RECONCILIATION_REQUIRED
        Start-ProductionDomainCollectionLegacy -State $State
        Set-ProductionWebsiteRecoveryPolicy -Policy Normal
        Remove-ProductionDomainCollectionTerminalReconciliationAuthorization `
            -State $State
        return
    }
    if ($stateName -cnotin @('LEGACY_DATA_VERIFIED','ROLLBACK_READY')) {
        $legacyDropped = $null -ne $State.PSObject.Properties['legacyDropped'] -and
            [bool]$State.legacyDropped
        $restoreBound = $PostDrop -or $legacyDropped -or
            $stateName -cin @(
                'DROP_STARTED','LEGACY_DROPPED','TARGET_ACTIVE','ROLLBACK_VERIFIED')
        if ($restoreBound) {
            if ($stateName -cne 'ROLLBACK_VERIFIED') {
                Assert-ProductionDomainCollectionRollbackFreshness -State $State
                Write-ProductionDomainCollectionRollbackMarker `
                    -State $State -MarkerState ROLLBACK_IN_PROGRESS
                Save-ProductionDomainCollectionContextState `
                    -Context $State -State ROLLBACK_VERIFIED
            } else {
                Write-ProductionDomainCollectionRollbackMarker `
                    -State $State -MarkerState ROLLBACK_IN_PROGRESS
            }
            Restore-ProductionDomainCollectionBackup -State $State
        } else {
            Write-ProductionDomainCollectionRollbackMarker `
                -State $State -MarkerState ROLLBACK_IN_PROGRESS
            Recover-ProductionDomainCollectionPrepublication -State $State
            Save-ProductionDomainCollectionContextState `
                -Context $State -State LEGACY_DATA_VERIFIED
        }
    }
    if ([string]$State.state -eq 'LEGACY_DATA_VERIFIED') {
        Save-ProductionDomainCollectionContextState `
            -Context $State -State ROLLBACK_READY
    }
    if ([string]$State.state -ne 'ROLLBACK_READY') {
        throw 'Protected rollback did not reach the durable no-rerestore state.'
    }
    Write-ProductionDomainCollectionRollbackMarker `
        -State $State -MarkerState LEGACY_ACTIVE_RECONCILIATION_REQUIRED
    Start-ProductionDomainCollectionLegacy -State $State
    Set-ProductionWebsiteRecoveryPolicy -Policy Normal
    Save-ProductionDomainCollectionContextState -Context $State -State ROLLED_BACK
}

function Get-ProductionDomainCollectionPreview {
    [CmdletBinding()]
    param()

    $config = Read-ProductionConfig
    $guard = Enter-ProductionFixedRootDeploymentLock `
        -Config $config -FixedRoot $script:FixedProductionRoot
    try {
        $context = New-ProductionDomainCollectionPreviewContext -Config $config
        Assert-ProductionFixedRootBoundary `
            -Config $config `
            -FixedRoot $script:FixedProductionRoot `
            -ExpectedBoundary $guard.Boundary | Out-Null
        Invoke-ProductionDomainCollectionPreviewAction -Context $context
    } finally {
        $guard.Lock.Dispose()
    }
}

function Invoke-ProductionDomainCollectionCutover {
    [CmdletBinding()]
    param([switch]$Confirm, [switch]$WhatIf)

    Assert-ProductionDomainCollectionConfirmation `
        -Operation 'Domain collection consolidation' `
        -Confirm:$Confirm `
        -WhatIf:$WhatIf
    if ($WhatIf) { return Get-ProductionDomainCollectionPreview }

    $config = Read-ProductionConfig
    $guard = Enter-ProductionFixedRootDeploymentLock `
        -Config $config -FixedRoot $script:FixedProductionRoot
    $context = $null
    try {
        Resolve-ProductionDomainCollectionPrepublicationForCutoverRetry `
            -Config $config
        try {
            $context = New-ProductionDomainCollectionCutoverContext -Config $config
            Invoke-ProductionDomainCollectionCandidateProof -Context $context
            Assert-ProductionFixedRootBoundary `
                -Config $config `
                -FixedRoot $script:FixedProductionRoot `
                -ExpectedBoundary $guard.Boundary | Out-Null
            Invoke-ProductionDomainCollectionStageAndPublish -Context $context
            Prepare-ProductionDomainCollectionTargetCutover -Context $context
            Assert-ProductionFixedRootBoundary `
                -Config $config `
                -FixedRoot $script:FixedProductionRoot `
                -ExpectedBoundary $guard.Boundary | Out-Null
            $null = Invoke-ProductionDomainCollectionEngine `
                -Config $context.config -Database $script:ProductionDatabase `
                -Action verify-live -OwnerToken $context.ownerToken `
                -Release $context.targetRelease `
                -BackupIdentity $context.backupIdentity `
                -EvidenceDigest $context.evidenceDigest -Evidence $context.evidence
            Invoke-ProductionDomainCollectionDropLegacy -Context $context
            Complete-ProductionDomainCollectionCutover -Context $context
        } catch {
            $cutoverFailure = $_.Exception
            if ($null -ne $context) {
                $postDrop = [bool]$context.dropStarted
                try {
                    Invoke-ProductionDomainCollectionFailureRecovery `
                        -Context $context -PostDrop:$postDrop
                } catch {
                    throw [AggregateException]::new(
                        'Domain collection cutover and guarded recovery both failed.',
                        [Exception[]]@($cutoverFailure, $_.Exception))
                }
            }
            throw $cutoverFailure
        }
    } finally {
        $guard.Lock.Dispose()
    }
}

function Invoke-ProductionDomainCollectionRollback {
    [CmdletBinding()]
    param([switch]$Confirm, [switch]$WhatIf)

    Assert-ProductionDomainCollectionConfirmation `
        -Operation 'Domain collection rollback' `
        -Confirm:$Confirm `
        -WhatIf:$WhatIf
    if ($WhatIf) {
        return [pscustomobject][ordered]@{
            action = 'rollback-preview'
            mutationPerformed = $false
        }
    }

    $config = Read-ProductionConfig
    $guard = Enter-ProductionFixedRootDeploymentLock `
        -Config $config -FixedRoot $script:FixedProductionRoot
    try {
        $state = Read-ProductionDomainCollectionProtectedState -Config $config
        if ([string]$state.state -ceq 'ROLLED_BACK' -and
            (-not $state.PSObject.Properties['terminalReconciliationAuthorized'] -or
                -not [bool]$state.terminalReconciliationAuthorized)) {
            throw 'Terminal rollback replay requires one-shot reconciliation authorization.'
        }
        try {
            Stop-ProductionDomainCollectionWriter -Context $state
            Invoke-ProductionDomainCollectionRollbackStateMachine `
                -State $state -PostDrop:([bool]$state.legacyDropped)
        } catch {
            Stop-ProductionDomainCollectionRollbackAfterFailure `
                -State $state -Failure $_.Exception
        }
    } finally {
        $guard.Lock.Dispose()
    }
}

Export-ModuleMember -Function `
    Get-ProductionDomainCollectionPreview,Invoke-ProductionDomainCollectionCutover,`
    Invoke-ProductionDomainCollectionRollback
