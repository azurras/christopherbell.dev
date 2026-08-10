Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MarkerStates = @(
    'TARGET_ACTIVE',
    'TARGET_CUTOVER_IN_PROGRESS',
    'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
)
$script:AuthorizationPurposes = @(
    'TARGET_CUTOVER',
    'TARGET_DEPLOY',
    'TARGET_RECONCILIATION',
    'LEGACY_ROLLBACK',
    'LEGACY_RESTORE'
)

function Get-ProductionMusicSchemaDirectionPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'state\music-runtime-schema-direction.json'
}

function Get-ProductionWriterStartAuthorizationPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'state\music-runtime-pending-start.json'
}

function Get-ProductionWriterStartGuardManifestPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'service\Production.WriterStart.bundle.json'
}

function Assert-ExactJsonProperties {
    param($Value, [string[]]$Expected, [string]$Label)
    $actual = @($Value.PSObject.Properties.Name)
    if ($actual.Count -ne $Expected.Count) { throw "$Label has invalid properties." }
    foreach ($name in $Expected) {
        if (-not ($actual -ccontains $name)) { throw "$Label has invalid properties." }
    }
}

function Get-ProductionWriterStartIssuerIdentity {
    $process = [Diagnostics.Process]::GetCurrentProcess()
    try {
        [pscustomobject][ordered]@{
            issuerPid = [int]$process.Id
            issuerStartTimeUtcTicks = [long]$process.StartTime.ToUniversalTime().Ticks
        }
    } finally {
        $process.Dispose()
    }
}

function Assert-ProductionWriterStartIssuerIdentity {
    param([Parameter(Mandatory)]$Authorization)
    try {
        $issuer = Get-Process -Id ([int]$Authorization.issuerPid) -ErrorAction Stop
        $actualTicks = [long]$issuer.StartTime.ToUniversalTime().Ticks
    } catch {
        throw [System.InvalidOperationException]::new(
            'Writer-start authorization issuer is not alive.', $_.Exception)
    }
    if ($actualTicks -ne [long]$Authorization.issuerStartTimeUtcTicks) {
        throw 'Writer-start authorization issuer process identity changed.'
    }
}

function Read-ProductionWriterStartGuardManifest {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionWriterStartGuardManifestPath -Config $Config
    try {
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @(
            'version','launcherSha256','moduleSha256') 'Writer-start guard manifest'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.launcherSha256 -isnot [string] -or
            [string]$value.launcherSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $value.moduleSha256 -isnot [string] -or
            [string]$value.moduleSha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw 'Invalid writer-start guard manifest.'
        }
        [pscustomobject][ordered]@{
            version = 1
            launcherSha256 = [string]$value.launcherSha256
            moduleSha256 = [string]$value.moduleSha256
        }
    } catch {
        throw [System.IO.InvalidDataException]::new(
            'Installed writer-start guard manifest is invalid.', $_.Exception)
    }
}

function Assert-ProductionWriterStartGuardBundle {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedLauncherSha256,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedModuleSha256
    )
    $serviceRoot = Join-Path $Config.programDataRoot 'service'
    $launcher = Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1'
    $module = Join-Path $serviceRoot 'Production.WriterStart.psm1'
    $manifestPath = Get-ProductionWriterStartGuardManifestPath -Config $Config
    $manifest = Read-ProductionWriterStartGuardManifest -Config $Config
    if ($manifest.launcherSha256 -cne $ExpectedLauncherSha256 -or
        $manifest.moduleSha256 -cne $ExpectedModuleSha256 -or
        (Get-FileHash -LiteralPath $launcher -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
            $ExpectedLauncherSha256 -or
        (Get-FileHash -LiteralPath $module -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
            $ExpectedModuleSha256) {
        throw 'Installed writer-start guard SHA-256 verification failed.'
    }
    if (Get-Command Assert-ProductionPathNotReparse -ErrorAction SilentlyContinue) {
        Assert-ProductionPathNotReparse -Path $serviceRoot | Out-Null
    }
    foreach ($path in @($launcher,$module,$manifestPath)) {
        if (Get-Command Assert-ProtectedProductionPath -ErrorAction SilentlyContinue) {
            Assert-ProtectedProductionPath -Path $path | Out-Null
        }
    }
    return $manifest
}

function Publish-ProductionWriterStartGuardFile {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        [IO.File]::Move($Source, $Destination)
        return
    }
    $backup = "$Destination.$PID.$([guid]::NewGuid().ToString('N')).backup"
    try {
        [IO.File]::Replace($Source, $Destination, $backup, $true)
    } finally {
        if (Test-Path -LiteralPath $backup) {
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        }
    }
}

function Publish-ProductionWriterStartGuardBundle {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$SourceLauncherPath,
        [Parameter(Mandatory)][string]$SourceModulePath
    )
    foreach ($source in @($SourceLauncherPath,$SourceModulePath)) {
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw 'Writer-start guard source file is missing.'
        }
        if (Get-Command Assert-ProductionPathNotReparse -ErrorAction SilentlyContinue) {
            Assert-ProductionPathNotReparse -Path $source | Out-Null
        }
    }
    $serviceRoot = Join-Path $Config.programDataRoot 'service'
    New-Item -ItemType Directory -Path $serviceRoot -Force | Out-Null
    if (Get-Command Assert-ProductionPathNotReparse -ErrorAction SilentlyContinue) {
        Assert-ProductionPathNotReparse -Path $serviceRoot | Out-Null
    }
    $launcherSha = (Get-FileHash -LiteralPath $SourceLauncherPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $moduleSha = (Get-FileHash -LiteralPath $SourceModulePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $staging = Join-Path $serviceRoot ('.writer-start-guard-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    try {
        $stagedLauncher = Join-Path $staging 'Start-ChristopherBellDev.ps1'
        $stagedModule = Join-Path $staging 'Production.WriterStart.psm1'
        $stagedManifest = Join-Path $staging 'Production.WriterStart.bundle.json'
        Copy-Item -LiteralPath $SourceLauncherPath -Destination $stagedLauncher
        Copy-Item -LiteralPath $SourceModulePath -Destination $stagedModule
        [ordered]@{
            version = 1
            launcherSha256 = $launcherSha
            moduleSha256 = $moduleSha
        } | ConvertTo-Json | Set-Content -LiteralPath $stagedManifest -Encoding utf8
        foreach ($path in @($staging,$stagedLauncher,$stagedModule,$stagedManifest)) {
            if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
                Protect-ProductionPath -Path $path
                Assert-ProtectedProductionPath -Path $path | Out-Null
            }
        }
        if ((Get-FileHash $stagedLauncher -Algorithm SHA256).Hash.ToLowerInvariant() -cne $launcherSha -or
            (Get-FileHash $stagedModule -Algorithm SHA256).Hash.ToLowerInvariant() -cne $moduleSha) {
            throw 'Staged writer-start guard SHA-256 verification failed.'
        }

        # Publishing the launcher first makes every partial upgrade fail closed against the
        # absent or old manifest. The manifest is the atomic commit point for the pair.
        Publish-ProductionWriterStartGuardFile `
            -Source $stagedLauncher `
            -Destination (Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1')
        Publish-ProductionWriterStartGuardFile `
            -Source $stagedModule `
            -Destination (Join-Path $serviceRoot 'Production.WriterStart.psm1')
        Publish-ProductionWriterStartGuardFile `
            -Source $stagedManifest `
            -Destination (Get-ProductionWriterStartGuardManifestPath -Config $Config)
        foreach ($path in @(
            (Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1'),
            (Join-Path $serviceRoot 'Production.WriterStart.psm1'),
            (Get-ProductionWriterStartGuardManifestPath -Config $Config))) {
            if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
                Protect-ProductionPath -Path $path
            }
        }
        Assert-ProductionWriterStartGuardBundle -Config $Config `
            -ExpectedLauncherSha256 $launcherSha `
            -ExpectedModuleSha256 $moduleSha | Out-Null
        [pscustomobject][ordered]@{
            launcherSha256 = $launcherSha
            moduleSha256 = $moduleSha
        }
    } finally {
        if (Test-Path -LiteralPath $staging) {
            Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-ProductionMusicSchemaDirection {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionMusicSchemaDirectionPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @(
            'version','state','updatedAtEpochMillis','targetRelease','legacyRelease') 'Marker'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.state -isnot [string] -or
            -not ($script:MarkerStates -ccontains [string]$value.state) -or
            ($value.updatedAtEpochMillis -isnot [int] -and
                $value.updatedAtEpochMillis -isnot [long]) -or
            [long]$value.updatedAtEpochMillis -lt 1 -or
            $value.targetRelease -isnot [string] -or
            [string]$value.targetRelease -cnotmatch '^[0-9a-f]{40}$' -or
            $value.legacyRelease -isnot [string] -or
            [string]$value.legacyRelease -cnotmatch '^[0-9a-f]{40}$') {
            throw 'Invalid marker.'
        }
        [pscustomobject][ordered]@{
            version = 1
            state = [string]$value.state
            updatedAtEpochMillis = [long]$value.updatedAtEpochMillis
            targetRelease = [string]$value.targetRelease
            legacyRelease = [string]$value.legacyRelease
        }
    } catch {
        throw [System.IO.InvalidDataException]::new(
            'Music runtime schema-direction marker is invalid.', $_.Exception)
    }
}

function Write-ProductionMusicSchemaDirection {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]
        [ValidateSet('TARGET_ACTIVE','TARGET_CUTOVER_IN_PROGRESS','LEGACY_ACTIVE_RECONCILIATION_REQUIRED')]
        [string]$State,
        [Parameter(Mandatory)][ValidateScript({ $_ -cmatch '^[0-9a-f]{40}$' })][string]$TargetRelease,
        [Parameter(Mandatory)][ValidateScript({ $_ -cmatch '^[0-9a-f]{40}$' })][string]$LegacyRelease
    )
    if (-not ($script:MarkerStates -ccontains $State)) {
        throw 'Music runtime schema-direction marker state is invalid.'
    }
    $path = Get-ProductionMusicSchemaDirectionPath -Config $Config
    $parent = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
        Protect-ProductionPath -Path $parent
        Assert-ProtectedProductionPath -Path $parent | Out-Null
    }
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [ordered]@{
            version = 1
            state = $State
            updatedAtEpochMillis = [DateTimeOffset]::new(
                (Get-Date).ToUniversalTime()).ToUnixTimeMilliseconds()
            targetRelease = $TargetRelease
            legacyRelease = $LegacyRelease
        } | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding utf8
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $temporary
            Assert-ProtectedProductionPath -Path $temporary | Out-Null
        }
        Move-Item -LiteralPath $temporary -Destination $path -Force
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $path
            Assert-ProtectedProductionPath -Path $path | Out-Null
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-ProductionReleaseIdentity {
    param([Parameter(Mandatory)]$Config)
    $current = Join-Path $Config.programDataRoot 'current'
    $release = Get-Item -LiteralPath $current -Force -ErrorAction Stop
    $targets = @($release.Target)
    if ($targets.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$targets[0])) {
        throw 'Active release junction is invalid.'
    }
    $sha = Split-Path -Leaf ([string]$targets[0])
    if ($sha -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Active release identity is invalid.'
    }
    $metadataPath = Join-Path $current 'release.json'
    try {
        $metadata = Get-Content -LiteralPath $metadataPath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $names = @($metadata.PSObject.Properties.Name)
        if (-not ($names -ccontains 'sha') -or [string]$metadata.sha -cne $sha) {
            throw 'Release SHA mismatch.'
        }
        $schema = $null
        if ($names -ccontains 'musicSchema') {
            if ($metadata.musicSchema -isnot [string] -or
                [string]$metadata.musicSchema -cnotin @('LEGACY','TARGET')) {
                throw 'Invalid release schema.'
            }
            $schema = [string]$metadata.musicSchema
        }
        [pscustomobject][ordered]@{ sha=$sha; musicSchema=$schema }
    } catch {
        throw [System.IO.InvalidDataException]::new(
            'Active release metadata is invalid.', $_.Exception)
    }
}

function Get-ProductionMusicMigrationActivationForWriterStart {
    param([Parameter(Mandatory)]$Config)
    $query = @'
const target = db.getSiblingDB('christopherbell');
const migrations = target.getCollection('application_migrations')
  .countDocuments({_id:'014-consolidate-music-runtime-state'});
const destination = target.getCollection('music_runtime_state').countDocuments({});
print(JSON.stringify({active:migrations !== 0 || destination !== 0}));
'@
    $errorPath = Join-Path $Config.programDataRoot `
        "state\writer-start-probe.$PID.$([guid]::NewGuid().ToString('N')).err"
    try {
        $output = & $Config.mongoShellExe '--quiet' '--norc' `
            'mongodb://127.0.0.1:27017/admin' '--eval' $query 2>$errorPath
        if ($LASTEXITCODE -ne 0) { throw 'Activation probe failed.' }
        $value = ($output -join "`n") | ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @('active') 'Activation result'
        if ($value.active -isnot [bool]) { throw 'Activation result is invalid.' }
        [bool]$value.active
    } catch {
        throw [System.InvalidOperationException]::new(
            'Music runtime migration activation could not be proven; writer start is blocked.',
            $_.Exception)
    } finally {
        if (Test-Path -LiteralPath $errorPath) {
            Remove-Item -LiteralPath $errorPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Grant-ProductionWriterStartAuthorization {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]
        [ValidateSet('TARGET_ACTIVE','TARGET_CUTOVER_IN_PROGRESS','LEGACY_ACTIVE_RECONCILIATION_REQUIRED')]
        [string]$MarkerState,
        [Parameter(Mandatory)][ValidateScript({ $_ -cmatch '^[0-9a-f]{40}$' })][string]$Release,
        [Parameter(Mandatory)]
        [ValidateSet('TARGET_CUTOVER','TARGET_DEPLOY','TARGET_RECONCILIATION','LEGACY_ROLLBACK','LEGACY_RESTORE')]
        [string]$Purpose,
        [ValidateRange(1,120)][int]$LifetimeSeconds = 30
    )
    if (-not ($script:MarkerStates -ccontains $MarkerState) -or
        -not ($script:AuthorizationPurposes -ccontains $Purpose)) {
        throw 'Writer-start authorization state or purpose is invalid.'
    }
    $marker = Read-ProductionMusicSchemaDirection -Config $Config
    if (-not $marker -or [string]$marker.state -cne $MarkerState) {
        throw 'Writer-start authorization does not match the exact schema-direction marker.'
    }
    $issuer = Get-ProductionWriterStartIssuerIdentity
    $nonce = [guid]::NewGuid().ToString('N')
    $expiresAt = [DateTimeOffset]::UtcNow.AddSeconds($LifetimeSeconds).ToUnixTimeMilliseconds()
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
    $published = $false
    $parent = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
        Protect-ProductionPath -Path $parent
        Assert-ProtectedProductionPath -Path $parent | Out-Null
    }
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [ordered]@{
            version = 1
            markerState = $MarkerState
            markerTargetRelease = [string]$marker.targetRelease
            markerLegacyRelease = [string]$marker.legacyRelease
            release = $Release
            purpose = $Purpose
            expiresAtEpochMillis = $expiresAt
            nonce = $nonce
            issuerPid = [int]$issuer.issuerPid
            issuerStartTimeUtcTicks = [long]$issuer.issuerStartTimeUtcTicks
        } | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding utf8
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $temporary
            Assert-ProtectedProductionPath -Path $temporary | Out-Null
        }
        Move-Item -LiteralPath $temporary -Destination $path -Force
        $published = $true
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $path
            Assert-ProtectedProductionPath -Path $path | Out-Null
        }
    } catch {
        if ($published -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        throw [System.InvalidOperationException]::new(
            'Pending writer-start authorization creation failed.', $_.Exception)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
    [pscustomobject][ordered]@{
        nonce = $nonce
        markerState = $MarkerState
        markerTargetRelease = [string]$marker.targetRelease
        markerLegacyRelease = [string]$marker.legacyRelease
        release = $Release
        purpose = $Purpose
        issuerPid = [int]$issuer.issuerPid
        issuerStartTimeUtcTicks = [long]$issuer.issuerStartTimeUtcTicks
    }
}

function Revoke-ProductionWriterStartAuthorization {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Authorization)
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    try {
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        if ($value.nonce -isnot [string] -or
            [string]$value.nonce -cne [string]$Authorization.nonce -or
            [string]$value.markerState -cne [string]$Authorization.markerState -or
            [string]$value.markerTargetRelease -cne
                [string]$Authorization.markerTargetRelease -or
            [string]$value.markerLegacyRelease -cne
                [string]$Authorization.markerLegacyRelease -or
            [string]$value.release -cne [string]$Authorization.release -or
            [string]$value.purpose -cne [string]$Authorization.purpose -or
            [int]$value.issuerPid -ne [int]$Authorization.issuerPid -or
            [long]$value.issuerStartTimeUtcTicks -ne
                [long]$Authorization.issuerStartTimeUtcTicks) {
            throw 'Pending writer-start authorization does not match the revocation token.'
        }
        Remove-Item -LiteralPath $path -Force -ErrorAction Stop
    } catch {
        throw [System.InvalidOperationException]::new(
            'Pending writer-start authorization revocation failed.', $_.Exception)
    }
}

function Use-ProductionWriterStartAuthorization {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Marker,
        [Parameter(Mandatory)]$ReleaseIdentity)
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    $claimed = "$path.claimed.$PID.$([guid]::NewGuid().ToString('N'))"
    try {
        Move-Item -LiteralPath $path -Destination $claimed -ErrorAction Stop
        $value = Get-Content -LiteralPath $claimed -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @(
            'version','markerState','markerTargetRelease','markerLegacyRelease',
            'release','purpose','expiresAtEpochMillis','nonce',
            'issuerPid','issuerStartTimeUtcTicks') 'Authorization'
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.markerState -isnot [string] -or
            [string]$value.markerState -cne [string]$Marker.state -or
            $value.markerTargetRelease -isnot [string] -or
            [string]$value.markerTargetRelease -cne [string]$Marker.targetRelease -or
            $value.markerLegacyRelease -isnot [string] -or
            [string]$value.markerLegacyRelease -cne [string]$Marker.legacyRelease -or
            $value.release -isnot [string] -or
            [string]$value.release -cne [string]$ReleaseIdentity.sha -or
            $value.purpose -isnot [string] -or
            -not ($script:AuthorizationPurposes -ccontains [string]$value.purpose) -or
            ($value.expiresAtEpochMillis -isnot [int] -and
                $value.expiresAtEpochMillis -isnot [long]) -or
            [long]$value.expiresAtEpochMillis -lt $now -or
            [long]$value.expiresAtEpochMillis -gt ($now + 120000) -or
            $value.nonce -isnot [string] -or
            [string]$value.nonce -cnotmatch '^[0-9a-f]{32}$' -or
            ($value.issuerPid -isnot [int] -and $value.issuerPid -isnot [long]) -or
            [int]$value.issuerPid -lt 1 -or
            ($value.issuerStartTimeUtcTicks -isnot [int] -and
                $value.issuerStartTimeUtcTicks -isnot [long]) -or
            [long]$value.issuerStartTimeUtcTicks -lt 1) {
            throw 'Authorization is invalid.'
        }
        Assert-ProductionWriterStartIssuerIdentity -Authorization $value
        $schema = [string]$ReleaseIdentity.musicSchema
        $purpose = [string]$value.purpose
        $validPurpose =
            ($purpose -eq 'TARGET_CUTOVER' -and $Marker.state -eq 'TARGET_CUTOVER_IN_PROGRESS' -and $schema -eq 'TARGET') -or
            ($purpose -eq 'TARGET_DEPLOY' -and $Marker.state -eq 'TARGET_ACTIVE' -and $schema -eq 'TARGET') -or
            ($purpose -eq 'TARGET_RECONCILIATION' -and $Marker.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED' -and $schema -eq 'TARGET') -or
            ($purpose -in @('LEGACY_ROLLBACK','LEGACY_RESTORE') -and $Marker.state -eq 'TARGET_ACTIVE' -and $schema -eq 'LEGACY')
        if (-not $validPurpose) { throw 'Authorization purpose is invalid.' }
        return $true
    } catch {
        throw [System.InvalidOperationException]::new(
            'Pending writer-start authorization is invalid or unavailable; writer start is blocked.',
            $_.Exception)
    } finally {
        if (Test-Path -LiteralPath $claimed) {
            Remove-Item -LiteralPath $claimed -Force -ErrorAction SilentlyContinue
        }
    }
}

function Assert-ProductionWriterStartAllowed {
    param([Parameter(Mandatory)]$Config)
    $release = Read-ProductionReleaseIdentity -Config $Config
    $marker = Read-ProductionMusicSchemaDirection -Config $Config
    if (-not $marker) {
        if (Get-ProductionMusicMigrationActivationForWriterStart -Config $Config) {
            throw 'Music schema-direction marker is absent after migration activation; writer start is blocked.'
        }
        if ($release.musicSchema -eq 'TARGET') {
            throw 'A target-schema release cannot start before the protected first cutover.'
        }
        return
    }
    $expected = if ($marker.state -eq 'TARGET_ACTIVE') {
        [string]$marker.targetRelease
    } elseif ($marker.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
        [string]$marker.legacyRelease
    } else { '' }
    $expectedSchema = if ($marker.state -eq 'TARGET_ACTIVE') { 'TARGET' } else { 'LEGACY' }
    if ($release.sha -eq $expected -and
        ($null -eq $release.musicSchema -or $release.musicSchema -eq $expectedSchema)) {
        return
    }
    if (Use-ProductionWriterStartAuthorization -Config $Config -Marker $marker -ReleaseIdentity $release) {
        return
    }
    throw 'The active release is incompatible with the Music schema-direction marker; writer start is blocked.'
}

Export-ModuleMember -Function Get-ProductionMusicSchemaDirectionPath,Read-ProductionMusicSchemaDirection,Write-ProductionMusicSchemaDirection,Assert-ProductionWriterStartAllowed,Get-ProductionMusicMigrationActivationForWriterStart,Read-ProductionReleaseIdentity
