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

function Assert-ExactJsonProperties {
    param($Value, [string[]]$Expected, [string]$Label)
    $actual = @($Value.PSObject.Properties.Name)
    if ($actual.Count -ne $Expected.Count) { throw "$Label has invalid properties." }
    foreach ($name in $Expected) {
        if (-not ($actual -ccontains $name)) { throw "$Label has invalid properties." }
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
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
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
            release = $Release
            purpose = $Purpose
            expiresAtEpochMillis = [DateTimeOffset]::UtcNow.AddSeconds($LifetimeSeconds).ToUnixTimeMilliseconds()
            nonce = [guid]::NewGuid().ToString('N')
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
            'version','markerState','release','purpose','expiresAtEpochMillis','nonce') 'Authorization'
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.markerState -isnot [string] -or
            [string]$value.markerState -cne [string]$Marker.state -or
            $value.release -isnot [string] -or
            [string]$value.release -cne [string]$ReleaseIdentity.sha -or
            $value.purpose -isnot [string] -or
            -not ($script:AuthorizationPurposes -ccontains [string]$value.purpose) -or
            ($value.expiresAtEpochMillis -isnot [int] -and
                $value.expiresAtEpochMillis -isnot [long]) -or
            [long]$value.expiresAtEpochMillis -lt $now -or
            [long]$value.expiresAtEpochMillis -gt ($now + 120000) -or
            $value.nonce -isnot [string] -or
            [string]$value.nonce -cnotmatch '^[0-9a-f]{32}$') {
            throw 'Authorization is invalid.'
        }
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
