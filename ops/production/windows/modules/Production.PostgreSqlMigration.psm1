Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot 'Production.PostgreSql.psm1')
Import-Module (Join-Path $PSScriptRoot 'Production.Deploy.psm1')

$script:DefaultProcessAction = {
    param($FilePath,$Arguments,$Environment)
    Invoke-CheckedProcess -FilePath $FilePath -ArgumentList @($Arguments) `
        -Environment (@{} + $Environment)
}
$script:DefaultLockAction = {
    param($Path) Enter-DeploymentLock -LockPath $Path
}
$script:DefaultIdentityAction = {
    param($Config,$Password)
    $psql = Join-Path $Config.postgresqlBinPath 'psql.exe'
    $output = Invoke-CheckedProcess -FilePath $psql -ArgumentList @(
        "--dbname=$($Config.migrationTargetJdbcUrl -replace '^jdbc:','')"
        "--username=$($Config.migrationTargetUsername)"
        '--no-align'
        '--tuples-only'
        '--command'
        "select inet_server_addr()::text || ':' || inet_server_port(), current_database(), current_user, current_setting('server_version');"
    ) -Environment @{ PGPASSWORD = $Password }
    $values = @(([string]$output).Trim() -split '\|')
    if ($values.Count -ne 4) {
        throw 'PostgreSQL migration preflight identity is unavailable.'
    }
    [pscustomobject]@{
        Endpoint = $values[0]
        Database = $values[1]
        Role = $values[2]
        ServerVersion = $values[3]
    }
}
$script:DefaultCandidateAction = {
    param($Config,$Release,$Environment)
    Test-CandidateRelease -Config $Config -Release $Release `
        -AdditionalEnvironment $Environment
}

function Assert-ProductionMigrationSecret {
    param([Parameter(Mandatory)][string]$Secret)
    if ([string]::IsNullOrWhiteSpace($Secret) -or $Secret.Length -lt 16 -or
        $Secret -match '(?i)replace|placeholder') {
        throw 'The protected PostgreSQL bridge secret is missing or invalid.'
    }
}

function Assert-ProductionMigrationTargetConfiguration {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    foreach ($name in 'migrationSourceUri','migrationSourceDatabase',
        'migrationTargetJdbcUrl','migrationTargetDatabase','migrationTargetRole',
        'migrationTargetUsername','migrationTargetServerVersion','migrationSchemaPrefix',
        'migrationCleanupTarget','migrationCandidateCleanupPort','candidatePort') {
        if (-not $Config.PSObject.Properties[$name]) {
            throw 'The PostgreSQL migration isolated-target preflight failed.'
        }
    }
    if ([string]$Config.migrationSourceUri -cne
            'mongodb://127.0.0.1:27017/christopherbell' -or
        [string]$Config.migrationSourceDatabase -cne 'christopherbell') {
        throw 'The PostgreSQL migration source identity preflight failed.'
    }
    $targetMatch = [regex]::Match([string]$Config.migrationTargetJdbcUrl,
        '^jdbc:postgresql://127\.0\.0\.1:(?<Port>[0-9]{2,5})/christopherbell$')
    if (-not $targetMatch.Success) {
        throw 'The PostgreSQL migration isolated-target preflight failed.'
    }
    $targetPort = [int]$targetMatch.Groups['Port'].Value
    if ($targetPort -eq 5432 -or $targetPort -eq 8080 -or $targetPort -eq 27017 -or
        $targetPort -lt 1024 -or $targetPort -gt 65535 -or
        [string]$Config.migrationTargetDatabase -cne 'christopherbell' -or
        [string]$Config.migrationTargetRole -cne 'christopherbell_bridge' -or
        [string]$Config.migrationTargetUsername -cne 'christopherbell_bridge' -or
        [string]$Config.migrationTargetServerVersion -cne '18.4' -or
        [string]$Config.migrationSchemaPrefix -cne '') {
        throw 'The PostgreSQL migration isolated-target preflight failed.'
    }
    $candidatePort = [int]$Config.candidatePort
    if ($candidatePort -eq 8080 -or $candidatePort -eq $targetPort -or
        $candidatePort -lt 1024 -or $candidatePort -gt 65535 -or
        [int]$Config.migrationCandidateCleanupPort -ne $candidatePort -or
        [string]$Config.migrationCleanupTarget -cne
            "127.0.0.1:$targetPort/christopherbell") {
        throw 'The PostgreSQL migration candidate cleanup preflight failed.'
    }
}

function Assert-ProductionMigrationObservedIdentity {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][pscustomobject]$Identity
    )
    $targetPort = [regex]::Match([string]$Config.migrationTargetJdbcUrl,
        '^jdbc:postgresql://127\.0\.0\.1:(?<Port>[0-9]{2,5})/').Groups['Port'].Value
    if ([string]$Identity.Endpoint -cne "127.0.0.1:$targetPort" -or
        [string]$Identity.Database -cne [string]$Config.migrationTargetDatabase -or
        [string]$Identity.Role -cne [string]$Config.migrationTargetRole -or
        [string]$Identity.ServerVersion -cne [string]$Config.migrationTargetServerVersion) {
        throw 'The PostgreSQL migration preflight identity drifted.'
    }
}

function Resolve-ProductionMigrationInputs {
    param(
        [pscustomobject]$Config,
        [string]$ReleasePath,
        [string]$ReleaseSha,
        [string]$BridgePassword,
        [scriptblock]$ProcessAction
    )
    if (-not $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionMigrationTargetConfiguration -Config $Config
    foreach ($name in 'programDataRoot','javaExe') {
        if (-not $Config.PSObject.Properties[$name] -or
            [string]::IsNullOrWhiteSpace([string]$Config.$name) -or
            -not (Test-ProductionAbsolutePath -Path ([string]$Config.$name))) {
            throw "Missing or invalid migration configuration value: $name"
        }
    }
    if (-not $ReleasePath) {
        $ReleasePath = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    }
    if (-not $ReleasePath) { throw 'The active production release is missing.' }
    $ReleasePath = Assert-ReleasePath -Config $Config -Path $ReleasePath
    if (-not $ReleaseSha) {
        try {
            $metadata = Get-Content -LiteralPath (Join-Path $ReleasePath 'release.json') `
                -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
            $names = @($metadata.PSObject.Properties.Name)
            if (-not ($names -ccontains 'sha') -or $metadata.sha -isnot [string]) {
                throw 'The active release metadata does not contain an exact SHA.'
            }
            $ReleaseSha = [string]$metadata.sha
        } catch {
            throw [IO.InvalidDataException]::new(
                'The active production release metadata is invalid.', $_.Exception)
        }
    }
    if ($ReleaseSha -notmatch '^[0-9a-f]{40}$') {
        throw 'The migration release SHA is invalid.'
    }
    if ([IO.Path]::GetFileName($ReleasePath) -cne $ReleaseSha) {
        throw 'The migration release path does not match its SHA.'
    }
    if (-not $BridgePassword) {
        $secrets = Read-ProductionPostgreSqlSecrets -Path (
            Join-Path $Config.programDataRoot 'config\postgresql.env')
        $BridgePassword = [string]$secrets.Bridge
    }
    Assert-ProductionMigrationSecret -Secret $BridgePassword
    $jar = Join-Path $ReleasePath 'app.jar'
    if ($ProcessAction -eq $script:DefaultProcessAction) {
        if (-not (Test-Path -LiteralPath $Config.javaExe -PathType Leaf) -or
            -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
            throw 'The active production Java runtime or application archive is missing.'
        }
    }
    return [pscustomobject]@{
        Config = $Config
        ReleasePath = $ReleasePath
        ReleaseSha = $ReleaseSha
        BridgePassword = $BridgePassword
        Jar = $jar
    }
}

function Get-ProductionMigrationStatePath {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    Join-Path $Config.programDataRoot 'migration\postgresql-shadow.json'
}

function Read-ProductionMigrationToken {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    $path = Get-ProductionMigrationStatePath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    Assert-ProductionPathNotReparse -Path $path | Out-Null
    Assert-ProtectedProductionPath -Path $path | Out-Null
    try {
        $state = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -ErrorAction Stop
        return [guid][string]$state.lockToken
    } catch {
        throw [IO.InvalidDataException]::new('The PostgreSQL migration state is invalid.', $_.Exception)
    }
}

function Write-ProductionMigrationToken {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][guid]$LockToken
    )
    $path = Get-ProductionMigrationStatePath -Config $Config
    $directory = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
        Protect-ProductionPath -Path $directory -Directory | Out-Null
    }
    [ordered]@{ lockToken = $LockToken.ToString() } | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $path -Encoding utf8
    Protect-ProductionPath -Path $path | Out-Null
}

function Invoke-ProductionMigrationProcess {
    param(
        [Parameter(Mandatory)][pscustomobject]$Inputs,
        [Parameter(Mandatory)][guid]$LockToken,
        [Parameter(Mandatory)][ValidateSet('shadow','reconcile')][string]$Command,
        [Parameter(Mandatory)][scriptblock]$ProcessAction
    )
    $environment = @{
        POSTGRESQL_MIGRATION_SOURCE_URI = $Inputs.Config.migrationSourceUri
        POSTGRESQL_MIGRATION_SOURCE_DATABASE = $Inputs.Config.migrationSourceDatabase
        POSTGRESQL_MIGRATION_TARGET_JDBC_URL = $Inputs.Config.migrationTargetJdbcUrl
        POSTGRESQL_MIGRATION_TARGET_DATABASE = $Inputs.Config.migrationTargetDatabase
        POSTGRESQL_MIGRATION_TARGET_ROLE = $Inputs.Config.migrationTargetRole
        POSTGRESQL_MIGRATION_SCHEMA_PREFIX = $Inputs.Config.migrationSchemaPrefix
        POSTGRESQL_MIGRATION_RELEASE = $Inputs.ReleaseSha
        POSTGRESQL_MIGRATION_BRIDGE_RELEASE = '1'
        POSTGRESQL_MIGRATION_LOCK_TOKEN = $LockToken.ToString()
        POSTGRESQL_MIGRATION_BATCH_SIZE = '500'
        POSTGRESQL_MIGRATION_TARGET_USERNAME = $Inputs.Config.migrationTargetUsername
        POSTGRESQL_MIGRATION_TARGET_PASSWORD = $Inputs.BridgePassword
    }
    & $ProcessAction $Inputs.Config.javaExe @(
        '-Dloader.main=dev.christopherbell.configuration.persistence.migration.PostgresqlMigrationCli',
        '-cp',$Inputs.Jar,'org.springframework.boot.loader.launch.PropertiesLauncher',$Command
    ) $environment
}

function Invoke-ProductionPostgreSqlShadow {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [string]$ReleasePath,
        [string]$ReleaseSha,
        [string]$BridgePassword,
        [guid]$LockToken = [guid]::Empty,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$LockAction = $script:DefaultLockAction,
        [scriptblock]$IdentityAction = $script:DefaultIdentityAction,
        [scriptblock]$CandidateAction = $script:DefaultCandidateAction
    )
    $inputs = Resolve-ProductionMigrationInputs -Config $Config -ReleasePath $ReleasePath `
        -ReleaseSha $ReleaseSha -BridgePassword $BridgePassword -ProcessAction $ProcessAction
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL migration shadow',
        'stage and reconcile all catalog kinds without finalization')) { return }
    $identity = & $IdentityAction $inputs.Config $inputs.BridgePassword
    Assert-ProductionMigrationObservedIdentity -Config $inputs.Config -Identity $identity
    $lock = & $LockAction (Join-Path $inputs.Config.programDataRoot 'locks\deploy.lock')
    if (-not $lock -or -not ($lock.PSObject.Methods.Name -contains 'Dispose')) {
        throw 'The production deployment lock could not be acquired.'
    }
    try {
        if ($LockToken -eq [guid]::Empty) {
            $LockToken = Read-ProductionMigrationToken -Config $inputs.Config
            if (-not $LockToken) {
                $LockToken = [guid]::NewGuid()
                Write-ProductionMigrationToken -Config $inputs.Config -LockToken $LockToken
            }
        }
        Invoke-ProductionMigrationProcess -Inputs $inputs -LockToken $LockToken `
            -Command shadow -ProcessAction $ProcessAction | Write-Output
        Invoke-ProductionMigrationProcess -Inputs $inputs -LockToken $LockToken `
            -Command reconcile -ProcessAction $ProcessAction | Write-Output
        & $CandidateAction $inputs.Config $inputs.ReleasePath @{
            APP_PERSISTENCE_BACKEND = 'postgresql'
            SPRING_DATASOURCE_URL = $inputs.Config.migrationTargetJdbcUrl
            SPRING_DATASOURCE_USERNAME = $inputs.Config.migrationTargetUsername
            SPRING_DATASOURCE_PASSWORD = $inputs.BridgePassword
        } | Write-Output
    } finally { $lock.Dispose() }
}

function Invoke-ProductionPostgreSqlReconcile {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [string]$ReleasePath,
        [string]$ReleaseSha,
        [string]$BridgePassword,
        [guid]$LockToken = [guid]::Empty,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$LockAction = $script:DefaultLockAction,
        [scriptblock]$IdentityAction = $script:DefaultIdentityAction,
        [scriptblock]$CandidateAction = $script:DefaultCandidateAction
    )
    $inputs = Resolve-ProductionMigrationInputs -Config $Config -ReleasePath $ReleasePath `
        -ReleaseSha $ReleaseSha -BridgePassword $BridgePassword -ProcessAction $ProcessAction
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL migration reconciliation',
        'reconcile the existing shadow run without finalization')) { return }
    $identity = & $IdentityAction $inputs.Config $inputs.BridgePassword
    Assert-ProductionMigrationObservedIdentity -Config $inputs.Config -Identity $identity
    $lock = & $LockAction (Join-Path $inputs.Config.programDataRoot 'locks\deploy.lock')
    if (-not $lock -or -not ($lock.PSObject.Methods.Name -contains 'Dispose')) {
        throw 'The production deployment lock could not be acquired.'
    }
    try {
        if ($LockToken -eq [guid]::Empty) {
            $LockToken = Read-ProductionMigrationToken -Config $inputs.Config
        }
        if (-not $LockToken -or $LockToken -eq [guid]::Empty) {
            throw 'A durable PostgreSQL migration lock token is required for reconciliation.'
        }
        Invoke-ProductionMigrationProcess -Inputs $inputs -LockToken $LockToken `
            -Command reconcile -ProcessAction $ProcessAction | Write-Output
        & $CandidateAction $inputs.Config $inputs.ReleasePath @{
            APP_PERSISTENCE_BACKEND = 'postgresql'
            SPRING_DATASOURCE_URL = $inputs.Config.migrationTargetJdbcUrl
            SPRING_DATASOURCE_USERNAME = $inputs.Config.migrationTargetUsername
            SPRING_DATASOURCE_PASSWORD = $inputs.BridgePassword
        } | Write-Output
    } finally { $lock.Dispose() }
}

Export-ModuleMember -Function Invoke-ProductionPostgreSqlShadow,
    Invoke-ProductionPostgreSqlReconcile
