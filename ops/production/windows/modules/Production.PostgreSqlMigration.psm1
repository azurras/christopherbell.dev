Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot 'Production.PostgreSql.psm1')
Import-Module (Join-Path $PSScriptRoot 'Production.Deploy.psm1')

$script:OwnedSchemas = @('identity','social','communication','federation','music',
    'shared_folder','mobility','lunch','canes','platform')
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
    Get-ProductionMigrationObservedIdentity -Config $Config `
        -Username ([string]$Config.migrationTargetUsername) -Password $Password
}
$script:DefaultAppIdentityAction = {
    param($Config,$Password)
    Get-ProductionMigrationObservedIdentity -Config $Config `
        -Username ([string]$Config.migrationCandidateUsername) -Password $Password
}
$script:DefaultCleanupAction = {
    param($Config,$Password)
    Remove-ProductionMigrationOwnedSchemas -Config $Config -Password $Password
}
$script:DefaultCandidateAction = {
    param($Config,$Release,$Environment)
    Test-CandidateRelease -Config $Config -Release $Release `
        -AdditionalEnvironment $Environment
}

function Assert-ProductionMigrationSecret {
    param(
        [Parameter(Mandatory)][string]$Role,
        [Parameter(Mandatory)][string]$Secret
    )
    if ([string]::IsNullOrWhiteSpace($Secret) -or $Secret.Length -lt 16 -or
        $Secret -match '(?i)replace|placeholder') {
        throw "The protected PostgreSQL $Role secret is missing or invalid."
    }
}

function Get-ProductionMigrationTargetPort {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    $match = [regex]::Match([string]$Config.migrationTargetJdbcUrl,
        '^jdbc:postgresql://127\.0\.0\.1:(?<Port>[0-9]{2,5})/christopherbell$')
    if (-not $match.Success) {
        throw 'The PostgreSQL migration isolated-target preflight failed.'
    }
    return [int]$match.Groups['Port'].Value
}

function Get-ProductionMigrationObservedIdentity {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password
    )
    $schemaLiterals = ($script:OwnedSchemas | ForEach-Object { "'$_'" }) -join ','
    $query = "select host(inet_server_addr()) || ':' || inet_server_port(), " +
        "current_database(), current_user, current_setting('server_version'), " +
        "pg_get_userbyid(database.datdba), " +
        "coalesce(shobj_description(database.oid, 'pg_database'), ''), " +
        "(select count(*) from pg_namespace namespace " +
        "join pg_roles owner on owner.oid = namespace.nspowner " +
        "where namespace.nspname in ($schemaLiterals) " +
        "and owner.rolname = '$($Config.migrationTargetDatabaseOwner)'), " +
        "(select count(*) from pg_class relation " +
        "join pg_namespace namespace on namespace.oid = relation.relnamespace " +
        "join pg_roles owner on owner.oid = relation.relowner " +
        "where namespace.nspname = 'public' " +
        "and relation.relname = 'flyway_schema_history' " +
        "and owner.rolname = '$($Config.migrationTargetDatabaseOwner)') " +
        "from pg_database database where database.datname = current_database();"
    $output = Invoke-CheckedProcess `
        -FilePath (Join-Path $Config.postgresqlBinPath 'psql.exe') `
        -ArgumentList @(
            "--dbname=$($Config.migrationTargetJdbcUrl -replace '^jdbc:','')"
            "--username=$Username"
            '--no-align'
            '--tuples-only'
            '--quiet'
            '--set=ON_ERROR_STOP=1'
            '--command'
            $query
        ) -Environment @{ PGPASSWORD = $Password }
    $values = @(([string]$output).Trim() -split '\|')
    if ($values.Count -ne 8) {
        throw 'PostgreSQL migration preflight identity is unavailable.'
    }
    return [pscustomobject][ordered]@{
        Endpoint = $values[0]
        Database = $values[1]
        Role = $values[2]
        ServerVersion = $values[3]
        DatabaseOwner = $values[4]
        OwnershipToken = $values[5]
        OwnedSchemaCount = [int]$values[6]
        OwnedHistoryCount = [int]$values[7]
    }
}

function Remove-ProductionMigrationOwnedSchemas {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$Password
    )
    $targetPort = Get-ProductionMigrationTargetPort -Config $Config
    $schemaLiterals = ($script:OwnedSchemas | ForEach-Object { "'$_'" }) -join ','
    $schemaIdentifiers = ($script:OwnedSchemas | ForEach-Object { '"' + $_ + '"' }) -join ','
    $sql = "DO `$owned`$ DECLARE observed_owner text; observed_token text; " +
        "observed_count bigint; observed_history_count bigint; BEGIN " +
        "IF host(inet_server_addr()) <> '127.0.0.1' OR inet_server_port() <> $targetPort " +
        "OR current_database() <> '$($Config.migrationTargetDatabase)' " +
        "OR current_user <> '$($Config.migrationCleanupUsername)' THEN " +
        "RAISE EXCEPTION 'migration cleanup target identity drift'; END IF; " +
        "SELECT pg_get_userbyid(database.datdba), " +
        "coalesce(shobj_description(database.oid, 'pg_database'), '') " +
        "INTO observed_owner, observed_token FROM pg_database database " +
        "WHERE database.datname = current_database(); " +
        "SELECT count(*) INTO observed_count FROM pg_namespace namespace " +
        "JOIN pg_roles owner ON owner.oid = namespace.nspowner " +
        "WHERE namespace.nspname IN ($schemaLiterals) " +
        "AND owner.rolname = '$($Config.migrationTargetDatabaseOwner)'; " +
        "SELECT count(*) INTO observed_history_count FROM pg_class relation " +
        "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace " +
        "JOIN pg_roles owner ON owner.oid = relation.relowner " +
        "WHERE namespace.nspname = 'public' " +
        "AND relation.relname = 'flyway_schema_history' " +
        "AND owner.rolname = '$($Config.migrationTargetDatabaseOwner)'; " +
        "IF observed_owner <> '$($Config.migrationTargetDatabaseOwner)' " +
        "OR observed_token <> '$($Config.migrationTargetOwnershipToken)' " +
        "OR observed_count <> $($script:OwnedSchemas.Count) " +
        "OR observed_history_count <> 1 THEN " +
        "RAISE EXCEPTION 'migration cleanup ownership drift'; END IF; END `$owned`$; " +
        "DROP TABLE public.flyway_schema_history; " +
        "DROP SCHEMA $schemaIdentifiers CASCADE; " +
        "SELECT (select count(*) from pg_namespace where nspname IN ($schemaLiterals)) " +
        "|| '|' || (select count(*) from pg_class relation " +
        "join pg_namespace namespace on namespace.oid = relation.relnamespace " +
        "where namespace.nspname = 'public' " +
        "and relation.relname = 'flyway_schema_history');"
    $output = Invoke-CheckedProcess `
        -FilePath (Join-Path $Config.postgresqlBinPath 'psql.exe') `
        -ArgumentList @(
            "--dbname=$($Config.migrationTargetJdbcUrl -replace '^jdbc:','')"
            "--username=$($Config.migrationCleanupUsername)"
            '--no-align'
            '--tuples-only'
            '--quiet'
            '--set=ON_ERROR_STOP=1'
            '--command'
            $sql
        ) -Environment @{ PGPASSWORD = $Password }
    $remaining = @(([string]$output).Trim() -split '\|')
    if ($remaining.Count -ne 2 -or $remaining[0] -cne '0' -or $remaining[1] -cne '0') {
        throw 'PostgreSQL migration cleanup readback did not prove exact schema removal.'
    }
    return [pscustomobject][ordered]@{
        Endpoint = "127.0.0.1:$targetPort"
        Database = [string]$Config.migrationTargetDatabase
        DatabaseOwner = [string]$Config.migrationTargetDatabaseOwner
        OwnershipToken = [string]$Config.migrationTargetOwnershipToken
        OwnedSchemaCount = 0
        OwnedHistoryCount = 0
        Removed = $true
    }
}

function Assert-ProductionMigrationTargetConfiguration {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    foreach ($name in 'postgresqlBinPath','migrationSourceUri','migrationSourceDatabase',
        'migrationTargetJdbcUrl','migrationTargetDatabase','migrationTargetRole',
        'migrationTargetUsername','migrationTargetServerVersion','migrationSchemaPrefix',
        'migrationTargetDatabaseOwner','migrationTargetOwnershipToken',
        'migrationCleanupTarget','migrationCleanupUsername','migrationCandidateRole',
        'migrationCandidateUsername','migrationCandidateCleanupPort','candidatePort') {
        if (-not $Config.PSObject.Properties[$name]) {
            throw 'The PostgreSQL migration isolated-target preflight failed.'
        }
    }
    if ([string]$Config.migrationSourceUri -cne
            'mongodb://127.0.0.1:27017/christopherbell' -or
        [string]$Config.migrationSourceDatabase -cne 'christopherbell') {
        throw 'The PostgreSQL migration source identity preflight failed.'
    }
    $targetPort = Get-ProductionMigrationTargetPort -Config $Config
    $ownershipToken = [guid]::Empty
    $validOwnershipToken = [guid]::TryParse(
        [string]$Config.migrationTargetOwnershipToken, [ref]$ownershipToken) -and
        $ownershipToken -ne [guid]::Empty
    if ($targetPort -eq 5432 -or $targetPort -eq 8080 -or $targetPort -eq 27017 -or
        $targetPort -lt 1024 -or $targetPort -gt 65535 -or
        [string]$Config.migrationTargetDatabase -cne 'christopherbell' -or
        [string]$Config.migrationTargetRole -cne 'christopherbell_bridge' -or
        [string]$Config.migrationTargetUsername -cne 'christopherbell_bridge' -or
        [string]$Config.migrationTargetServerVersion -cne '18.4' -or
        [string]$Config.migrationTargetDatabaseOwner -cne 'christopherbell_owner' -or
        -not $validOwnershipToken -or
        [string]$Config.migrationCleanupUsername -cne 'christopherbell_migrator' -or
        [string]$Config.migrationCandidateRole -cne 'christopherbell_app' -or
        [string]$Config.migrationCandidateUsername -cne 'christopherbell_app' -or
        [string]$Config.migrationSchemaPrefix -cne '') {
        throw 'The PostgreSQL migration isolated-target preflight failed.'
    }
    if (-not (Test-ProductionAbsolutePath -Path ([string]$Config.postgresqlBinPath))) {
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
        [Parameter(Mandatory)][pscustomobject]$Identity,
        [Parameter(Mandatory)][string]$ExpectedRole,
        [Parameter(Mandatory)][string]$FailureLabel
    )
    $targetPort = Get-ProductionMigrationTargetPort -Config $Config
    if ([string]$Identity.Endpoint -cne "127.0.0.1:$targetPort" -or
        [string]$Identity.Database -cne [string]$Config.migrationTargetDatabase -or
        [string]$Identity.Role -cne $ExpectedRole -or
        [string]$Identity.ServerVersion -cne [string]$Config.migrationTargetServerVersion -or
        [string]$Identity.DatabaseOwner -cne [string]$Config.migrationTargetDatabaseOwner -or
        [string]$Identity.OwnershipToken -cne [string]$Config.migrationTargetOwnershipToken -or
        [int]$Identity.OwnedSchemaCount -ne $script:OwnedSchemas.Count -or
        [int]$Identity.OwnedHistoryCount -ne 1) {
        throw "The PostgreSQL $FailureLabel identity drifted."
    }
}

function Assert-ProductionMigrationCleanupReadback {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][pscustomobject]$Readback
    )
    $targetPort = Get-ProductionMigrationTargetPort -Config $Config
    if ([string]$Readback.Endpoint -cne "127.0.0.1:$targetPort" -or
        [string]$Readback.Database -cne [string]$Config.migrationTargetDatabase -or
        [string]$Readback.DatabaseOwner -cne [string]$Config.migrationTargetDatabaseOwner -or
        [string]$Readback.OwnershipToken -cne [string]$Config.migrationTargetOwnershipToken -or
        [int]$Readback.OwnedSchemaCount -ne 0 -or -not [bool]$Readback.Removed) {
        throw 'The PostgreSQL migration cleanup readback did not prove exact schema removal.'
    }
    if ([int]$Readback.OwnedHistoryCount -ne 0) {
        throw 'The PostgreSQL migration cleanup readback did not prove exact schema removal.'
    }
}

function Resolve-ProductionMigrationInputs {
    param(
        [pscustomobject]$Config,
        [string]$ReleasePath,
        [string]$ReleaseSha,
        [string]$BridgePassword,
        [string]$AppPassword,
        [string]$MigratorPassword,
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
    if (-not $BridgePassword -or -not $AppPassword -or -not $MigratorPassword) {
        $secrets = Read-ProductionPostgreSqlSecrets -Path (
            Join-Path $Config.programDataRoot 'config\postgresql.env')
        if (-not $BridgePassword) { $BridgePassword = [string]$secrets.Roles.Bridge }
        if (-not $AppPassword) { $AppPassword = [string]$secrets.Roles.App }
        if (-not $MigratorPassword) { $MigratorPassword = [string]$secrets.Roles.Migrator }
    }
    Assert-ProductionMigrationSecret -Role bridge -Secret $BridgePassword
    Assert-ProductionMigrationSecret -Role app -Secret $AppPassword
    Assert-ProductionMigrationSecret -Role migrator -Secret $MigratorPassword
    if ($BridgePassword -ceq $AppPassword -or $BridgePassword -ceq $MigratorPassword -or
        $AppPassword -ceq $MigratorPassword) {
        throw 'The protected PostgreSQL bridge, app, and migrator secrets must be distinct.'
    }
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
        AppPassword = $AppPassword
        MigratorPassword = $MigratorPassword
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
        [string]$AppPassword,
        [string]$MigratorPassword,
        [guid]$LockToken = [guid]::Empty,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$LockAction = $script:DefaultLockAction,
        [scriptblock]$IdentityAction = $script:DefaultIdentityAction,
        [scriptblock]$AppIdentityAction = $script:DefaultAppIdentityAction,
        [scriptblock]$CandidateAction = $script:DefaultCandidateAction,
        [scriptblock]$CleanupAction = $script:DefaultCleanupAction
    )
    $inputs = Resolve-ProductionMigrationInputs -Config $Config -ReleasePath $ReleasePath `
        -ReleaseSha $ReleaseSha -BridgePassword $BridgePassword -AppPassword $AppPassword `
        -MigratorPassword $MigratorPassword -ProcessAction $ProcessAction
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL migration shadow',
        'stage and reconcile all catalog kinds without finalization')) { return }
    $identity = & $IdentityAction $inputs.Config $inputs.BridgePassword
    Assert-ProductionMigrationObservedIdentity -Config $inputs.Config -Identity $identity `
        -ExpectedRole ([string]$inputs.Config.migrationTargetRole) `
        -FailureLabel 'migration preflight'
    $lock = & $LockAction (Join-Path $inputs.Config.programDataRoot 'locks\deploy.lock')
    if (-not $lock -or -not ($lock.PSObject.Methods.Name -contains 'Dispose')) {
        throw 'The production deployment lock could not be acquired.'
    }
    $migrationStarted = $false
    try {
        if ($LockToken -eq [guid]::Empty) {
            $LockToken = Read-ProductionMigrationToken -Config $inputs.Config
            if (-not $LockToken) {
                $LockToken = [guid]::NewGuid()
                Write-ProductionMigrationToken -Config $inputs.Config -LockToken $LockToken
            }
        }
        $migrationStarted = $true
        Invoke-ProductionMigrationProcess -Inputs $inputs -LockToken $LockToken `
            -Command shadow -ProcessAction $ProcessAction | Write-Output
        Invoke-ProductionMigrationProcess -Inputs $inputs -LockToken $LockToken `
            -Command reconcile -ProcessAction $ProcessAction | Write-Output
        $appIdentity = & $AppIdentityAction $inputs.Config $inputs.AppPassword
        Assert-ProductionMigrationObservedIdentity -Config $inputs.Config -Identity $appIdentity `
            -ExpectedRole ([string]$inputs.Config.migrationCandidateRole) `
            -FailureLabel 'candidate'
        & $CandidateAction $inputs.Config $inputs.ReleasePath @{
            APP_PERSISTENCE_BACKEND = 'postgresql'
            SPRING_DATASOURCE_URL = $inputs.Config.migrationTargetJdbcUrl
            SPRING_DATASOURCE_USERNAME = $inputs.Config.migrationCandidateUsername
            SPRING_DATASOURCE_PASSWORD = $inputs.AppPassword
        } | Write-Output
    } catch {
        $operationFailure = $_.Exception
        if ($migrationStarted) {
            try {
                $readback = & $CleanupAction $inputs.Config $inputs.MigratorPassword
                Assert-ProductionMigrationCleanupReadback `
                    -Config $inputs.Config -Readback $readback
            } catch {
                throw [AggregateException]::new(
                    'PostgreSQL migration and exact owned-schema cleanup both failed.',
                    [Exception[]]@($operationFailure, $_.Exception))
            }
        }
        throw $operationFailure
    } finally { $lock.Dispose() }
}

function Invoke-ProductionPostgreSqlReconcile {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [string]$ReleasePath,
        [string]$ReleaseSha,
        [string]$BridgePassword,
        [string]$AppPassword,
        [string]$MigratorPassword,
        [guid]$LockToken = [guid]::Empty,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$LockAction = $script:DefaultLockAction,
        [scriptblock]$IdentityAction = $script:DefaultIdentityAction,
        [scriptblock]$AppIdentityAction = $script:DefaultAppIdentityAction,
        [scriptblock]$CandidateAction = $script:DefaultCandidateAction,
        [scriptblock]$CleanupAction = $script:DefaultCleanupAction
    )
    $inputs = Resolve-ProductionMigrationInputs -Config $Config -ReleasePath $ReleasePath `
        -ReleaseSha $ReleaseSha -BridgePassword $BridgePassword -AppPassword $AppPassword `
        -MigratorPassword $MigratorPassword -ProcessAction $ProcessAction
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL migration reconciliation',
        'reconcile the existing shadow run without finalization')) { return }
    $identity = & $IdentityAction $inputs.Config $inputs.BridgePassword
    Assert-ProductionMigrationObservedIdentity -Config $inputs.Config -Identity $identity `
        -ExpectedRole ([string]$inputs.Config.migrationTargetRole) `
        -FailureLabel 'migration preflight'
    $lock = & $LockAction (Join-Path $inputs.Config.programDataRoot 'locks\deploy.lock')
    if (-not $lock -or -not ($lock.PSObject.Methods.Name -contains 'Dispose')) {
        throw 'The production deployment lock could not be acquired.'
    }
    $migrationStarted = $false
    try {
        if ($LockToken -eq [guid]::Empty) {
            $LockToken = Read-ProductionMigrationToken -Config $inputs.Config
        }
        if (-not $LockToken -or $LockToken -eq [guid]::Empty) {
            throw 'A durable PostgreSQL migration lock token is required for reconciliation.'
        }
        $migrationStarted = $true
        Invoke-ProductionMigrationProcess -Inputs $inputs -LockToken $LockToken `
            -Command reconcile -ProcessAction $ProcessAction | Write-Output
        $appIdentity = & $AppIdentityAction $inputs.Config $inputs.AppPassword
        Assert-ProductionMigrationObservedIdentity -Config $inputs.Config -Identity $appIdentity `
            -ExpectedRole ([string]$inputs.Config.migrationCandidateRole) `
            -FailureLabel 'candidate'
        & $CandidateAction $inputs.Config $inputs.ReleasePath @{
            APP_PERSISTENCE_BACKEND = 'postgresql'
            SPRING_DATASOURCE_URL = $inputs.Config.migrationTargetJdbcUrl
            SPRING_DATASOURCE_USERNAME = $inputs.Config.migrationCandidateUsername
            SPRING_DATASOURCE_PASSWORD = $inputs.AppPassword
        } | Write-Output
    } catch {
        $operationFailure = $_.Exception
        if ($migrationStarted) {
            try {
                $readback = & $CleanupAction $inputs.Config $inputs.MigratorPassword
                Assert-ProductionMigrationCleanupReadback `
                    -Config $inputs.Config -Readback $readback
            } catch {
                throw [AggregateException]::new(
                    'PostgreSQL reconciliation and exact owned-schema cleanup both failed.',
                    [Exception[]]@($operationFailure, $_.Exception))
            }
        }
        throw $operationFailure
    } finally { $lock.Dispose() }
}

function Get-ProductionPostgreSqlCutoverJournalPath {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    Join-Path $Config.programDataRoot 'migration\postgresql-cutover.json'
}

function Get-ProductionPostgreSqlCutoverJournalBody {
    param([Parameter(Mandatory)]$Journal)
    [ordered]@{
        version = [int]$Journal.version
        release = [string]$Journal.release
        lockToken = [string]$Journal.lockToken
        sourceDatabase = [string]$Journal.sourceDatabase
        targetDatabase = [string]$Journal.targetDatabase
        catalogDigest = [string]$Journal.catalogDigest
        targetJdbcDigest = [string]$Journal.targetJdbcDigest
        phase = [string]$Journal.phase
        authorityPublished = [bool]$Journal.authorityPublished
        startedAt = [string]$Journal.startedAt
        deadlineAt = [string]$Journal.deadlineAt
        transitions = @($Journal.transitions | ForEach-Object {
            [ordered]@{
                prior = [string]$_.prior
                next = [string]$_.next
                at = [string]$_.at
                evidenceDigest = [string]$_.evidenceDigest
            }
        })
    }
}

function Get-ProductionPostgreSqlCutoverDigest {
    param([Parameter(Mandatory)]$Journal)
    $json = Get-ProductionPostgreSqlCutoverJournalBody -Journal $Journal |
        ConvertTo-Json -Depth 20 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Assert-ProductionPostgreSqlCutoverJournal {
    param([Parameter(Mandatory)]$Journal)
    $propertyNames = @($Journal.PSObject.Properties.Name)
    $expectedNames = @('version','release','lockToken','sourceDatabase','targetDatabase',
        'catalogDigest','targetJdbcDigest','phase','authorityPublished','startedAt','deadlineAt',
        'transitions','journalDigest')
    $token = [guid]::Empty
    $started = [datetimeoffset]::MinValue
    $deadline = [datetimeoffset]::MinValue
    $validPhases = @('PLANNED','WRITERS_STOPPED','MONGO_ARCHIVED',
        'POSTGRESQL_FINALIZED','POSTGRESQL_RECONCILED','POSTGRESQL_BACKED_UP',
        'CANDIDATE_VERIFIED','AUTHORITY_PUBLICATION_STARTED','AUTHORITY_PUBLISHED',
        'PRODUCTION_ACTIVE','PRODUCTION_VERIFIED','SOAKING','ROLLED_BACK',
        'FORWARD_RECOVERY_REQUIRED')
    if (($propertyNames.Count -ne $expectedNames.Count) -or
        @($expectedNames | Where-Object { $propertyNames -cnotcontains $_ }).Count -ne 0 -or
        [int]$Journal.version -ne 1 -or
        [string]$Journal.release -cnotmatch '^[0-9a-f]{40}$' -or
        -not [guid]::TryParse([string]$Journal.lockToken, [ref]$token) -or
        $token -eq [guid]::Empty -or
        [string]$Journal.sourceDatabase -cne 'christopherbell' -or
        [string]$Journal.targetDatabase -cne 'christopherbell' -or
        [string]$Journal.catalogDigest -cnotmatch '^[0-9a-f]{64}$' -or
        [string]$Journal.targetJdbcDigest -cnotmatch '^[0-9a-f]{64}$' -or
        $validPhases -cnotcontains [string]$Journal.phase -or
        -not [datetimeoffset]::TryParse([string]$Journal.startedAt, [ref]$started) -or
        -not [datetimeoffset]::TryParse([string]$Journal.deadlineAt, [ref]$deadline) -or
        $deadline -le $started -or
        [string]$Journal.journalDigest -cnotmatch '^[0-9a-f]{64}$' -or
        [string]$Journal.journalDigest -cne (
            Get-ProductionPostgreSqlCutoverDigest -Journal $Journal)) {
        throw 'The PostgreSQL cutover journal is invalid.'
    }
    $allowed = @{
        PLANNED = @('WRITERS_STOPPED','ROLLED_BACK')
        WRITERS_STOPPED = @('MONGO_ARCHIVED','ROLLED_BACK')
        MONGO_ARCHIVED = @('POSTGRESQL_FINALIZED','ROLLED_BACK')
        POSTGRESQL_FINALIZED = @('POSTGRESQL_RECONCILED','ROLLED_BACK')
        POSTGRESQL_RECONCILED = @('POSTGRESQL_BACKED_UP','ROLLED_BACK')
        POSTGRESQL_BACKED_UP = @('CANDIDATE_VERIFIED','ROLLED_BACK')
        CANDIDATE_VERIFIED = @('AUTHORITY_PUBLICATION_STARTED','ROLLED_BACK')
        AUTHORITY_PUBLICATION_STARTED = @('AUTHORITY_PUBLISHED','FORWARD_RECOVERY_REQUIRED')
        AUTHORITY_PUBLISHED = @('PRODUCTION_ACTIVE','FORWARD_RECOVERY_REQUIRED')
        PRODUCTION_ACTIVE = @('PRODUCTION_VERIFIED','FORWARD_RECOVERY_REQUIRED')
        PRODUCTION_VERIFIED = @('SOAKING','FORWARD_RECOVERY_REQUIRED')
    }
    $current = 'PLANNED'
    $previousAt = $started
    $observedAuthority = $false
    foreach ($transition in @($Journal.transitions)) {
        $names = @($transition.PSObject.Properties.Name)
        $at = [datetimeoffset]::MinValue
        if ($names.Count -ne 4 -or
            @('prior','next','at','evidenceDigest' | Where-Object {
                $names -cnotcontains $_ }).Count -ne 0 -or
            $validPhases -cnotcontains [string]$transition.prior -or
            $validPhases -cnotcontains [string]$transition.next -or
            -not [datetimeoffset]::TryParse([string]$transition.at, [ref]$at) -or
            [string]$transition.evidenceDigest -cnotmatch '^[0-9a-f]{64}$') {
            throw 'The PostgreSQL cutover journal is invalid.'
        }
        if ([string]$transition.prior -cne $current -or
            -not $allowed.ContainsKey($current) -or
            $allowed[$current] -cnotcontains [string]$transition.next -or
            $at -lt $previousAt) {
            throw 'The PostgreSQL cutover journal is invalid.'
        }
        $current = [string]$transition.next
        $previousAt = $at
        if ($current -eq 'AUTHORITY_PUBLICATION_STARTED') { $observedAuthority = $true }
    }
    if ($current -cne [string]$Journal.phase -or
        [bool]$Journal.authorityPublished -ne $observedAuthority) {
        throw 'The PostgreSQL cutover journal is invalid.'
    }
    return $Journal
}

function Read-ProductionPostgreSqlCutoverJournal {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    $path = Get-ProductionPostgreSqlCutoverJournalPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    Assert-ProductionPathNotReparse -Path $path | Out-Null
    Assert-ProtectedProductionPath -Path $path | Out-Null
    try {
        $journal = Get-Content -LiteralPath $path -Raw |
            ConvertFrom-Json -Depth 30 -ErrorAction Stop
        return Assert-ProductionPostgreSqlCutoverJournal -Journal $journal
    } catch {
        if ($_.Exception.Message -like '*cutover journal is invalid*') { throw }
        throw [IO.InvalidDataException]::new(
            'The PostgreSQL cutover journal is invalid.', $_.Exception)
    }
}

function Write-ProductionPostgreSqlCutoverJournal {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    $path = Get-ProductionPostgreSqlCutoverJournalPath -Config $Config
    $parent = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    Protect-ProductionPath -Path $parent -Directory | Out-Null
    Assert-ProtectedProductionPath -Path $parent | Out-Null
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Journal | ConvertTo-Json -Depth 30 -Compress |
            Set-Content -LiteralPath $temporary -Encoding utf8 -NoNewline
        Protect-ProductionPath -Path $temporary | Out-Null
        Assert-ProtectedProductionPath -Path $temporary | Out-Null
        Move-Item -LiteralPath $temporary -Destination $path -Force
        Protect-ProductionPath -Path $path | Out-Null
        Assert-ProtectedProductionPath -Path $path | Out-Null
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function New-ProductionPostgreSqlCutoverJournal {
    param(
        [Parameter(Mandatory)]$Preflight,
        [Parameter(Mandatory)][datetimeoffset]$Now,
        [ValidateRange(1,30)][int]$MaintenanceBudgetMinutes
    )
    $token = [guid]::Empty
    if ([string]$Preflight.release -cnotmatch '^[0-9a-f]{40}$' -or
        -not [guid]::TryParse([string]$Preflight.lockToken, [ref]$token) -or
        $token -eq [guid]::Empty -or
        [string]$Preflight.sourceDatabase -cne 'christopherbell' -or
        [string]$Preflight.targetDatabase -cne 'christopherbell' -or
        [string]$Preflight.catalogDigest -cnotmatch '^[0-9a-f]{64}$' -or
        [string]$Preflight.targetJdbcDigest -cnotmatch '^[0-9a-f]{64}$') {
        throw 'The PostgreSQL cutover preflight identity is invalid.'
    }
    $journal = [pscustomobject][ordered]@{
        version = 1
        release = [string]$Preflight.release
        lockToken = $token.ToString()
        sourceDatabase = 'christopherbell'
        targetDatabase = 'christopherbell'
        catalogDigest = [string]$Preflight.catalogDigest
        targetJdbcDigest = [string]$Preflight.targetJdbcDigest
        phase = 'PLANNED'
        authorityPublished = $false
        startedAt = $Now.ToUniversalTime().ToString('o')
        deadlineAt = $Now.AddMinutes($MaintenanceBudgetMinutes).ToUniversalTime().ToString('o')
        transitions = @()
        journalDigest = ''
    }
    $journal.journalDigest = Get-ProductionPostgreSqlCutoverDigest -Journal $journal
    return $journal
}

function Add-ProductionPostgreSqlCutoverTransition {
    param(
        [Parameter(Mandatory)]$Journal,
        [Parameter(Mandatory)][string]$Next,
        [Parameter(Mandatory)][string]$EvidenceDigest,
        [Parameter(Mandatory)][datetimeoffset]$Now,
        [bool]$AuthorityPublished = [bool]$Journal.authorityPublished
    )
    if ($EvidenceDigest -cnotmatch '^[0-9a-f]{64}$') {
        throw 'The PostgreSQL cutover transition evidence is invalid.'
    }
    $transitions = @($Journal.transitions) + [pscustomobject][ordered]@{
        prior = [string]$Journal.phase
        next = $Next
        at = $Now.ToUniversalTime().ToString('o')
        evidenceDigest = $EvidenceDigest
    }
    $updated = [pscustomobject][ordered]@{
        version = [int]$Journal.version
        release = [string]$Journal.release
        lockToken = [string]$Journal.lockToken
        sourceDatabase = [string]$Journal.sourceDatabase
        targetDatabase = [string]$Journal.targetDatabase
        catalogDigest = [string]$Journal.catalogDigest
        targetJdbcDigest = [string]$Journal.targetJdbcDigest
        phase = $Next
        authorityPublished = $AuthorityPublished
        startedAt = [string]$Journal.startedAt
        deadlineAt = [string]$Journal.deadlineAt
        transitions = $transitions
        journalDigest = ''
    }
    $updated.journalDigest = Get-ProductionPostgreSqlCutoverDigest -Journal $updated
    return $updated
}

function Get-ProductionPostgreSqlCutoverEvidenceDigest {
    param([Parameter(Mandatory)]$Evidence)
    $value = if ($Evidence -is [Collections.IDictionary]) {
        $Evidence['digest']
    } else {
        $Evidence.digest
    }
    if ([string]$value -cnotmatch '^[0-9a-f]{64}$') {
        throw 'The PostgreSQL cutover transition evidence is invalid.'
    }
    return [string]$value
}

function Get-ProductionPostgreSqlCutoverSidecarPath {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]
        [ValidateSet('writers-stopped','mongo-archive','postgresql-finalized',
            'postgresql-reconciled','postgresql-backup','candidate','authority-intent',
            'authority','production-active','production-verified','soak')]
        [string]$Name
    )
    Join-Path $Config.programDataRoot "migration\postgresql-cutover-$Name.json"
}

function Write-ProductionPostgreSqlCutoverSidecar {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Value
    )
    $path = Get-ProductionPostgreSqlCutoverSidecarPath -Config $Config -Name $Name
    $parent = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    Protect-ProductionPath -Path $parent -Directory | Out-Null
    Assert-ProtectedProductionPath -Path $parent | Out-Null
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Value | ConvertTo-Json -Depth 20 -Compress |
            Set-Content -LiteralPath $temporary -Encoding utf8 -NoNewline
        Protect-ProductionPath -Path $temporary | Out-Null
        Assert-ProtectedProductionPath -Path $temporary | Out-Null
        Move-Item -LiteralPath $temporary -Destination $path -Force
        Protect-ProductionPath -Path $path | Out-Null
        Assert-ProtectedProductionPath -Path $path | Out-Null
        return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-ProductionPostgreSqlCutoverSidecar {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedDigest
    )
    $path = Get-ProductionPostgreSqlCutoverSidecarPath -Config $Config -Name $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "The PostgreSQL cutover $Name evidence is missing."
    }
    Assert-ProductionPathNotReparse -Path $path | Out-Null
    Assert-ProtectedProductionPath -Path $path | Out-Null
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        $ExpectedDigest) {
        throw "The PostgreSQL cutover $Name evidence digest drifted."
    }
    try { return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -Depth 20 -ErrorAction Stop }
    catch { throw [IO.InvalidDataException]::new(
        "The PostgreSQL cutover $Name evidence is invalid.", $_.Exception) }
}

function Get-ProductionPostgreSqlCutoverTransitionDigest {
    param(
        [Parameter(Mandatory)]$Journal,
        [Parameter(Mandatory)][string]$Phase
    )
    $phaseTransitions = @($Journal.transitions | Where-Object { [string]$_.next -ceq $Phase })
    if ($phaseTransitions.Count -ne 1 -or
        [string]$phaseTransitions[0].evidenceDigest -cnotmatch '^[0-9a-f]{64}$') {
        throw "The PostgreSQL cutover $Phase transition evidence is invalid."
    }
    return [string]$phaseTransitions[0].evidenceDigest
}

function Get-ProductionPostgreSqlCutoverRelease {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    $path = Join-Path $Config.programDataRoot "releases\$($Journal.release)"
    $path = Assert-ReleasePath -Config $Config -Path $path
    if (-not (Test-Path -LiteralPath (Join-Path $path 'app.jar') -PathType Leaf)) {
        throw 'The PostgreSQL cutover release is missing.'
    }
    return $path
}

function Get-ProductionPostgreSqlCutoverCatalogDigest {
    param([Parameter(Mandatory)][string]$Release)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $Release 'app.jar'))
    try {
        $entries = @($archive.Entries | Where-Object FullName -eq
            'BOOT-INF/classes/db/migration/postgresql-migration-catalog.yml')
        if ($entries.Count -ne 1) { throw 'The migration catalog resource is missing.' }
        $stream = $entries[0].Open()
        try {
            $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $hash = [Security.Cryptography.SHA256]::HashData($memory.ToArray())
                return [Convert]::ToHexString($hash).ToLowerInvariant()
            } finally { $memory.Dispose() }
        } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
}

function Get-ProductionPostgreSqlCutoverCanonicalStringHash {
    param([Parameter(Mandatory)][string]$Value)
    $options = [Text.Json.JsonSerializerOptions]::new()
    $options.Encoder = [Text.Encodings.Web.JavaScriptEncoder]::UnsafeRelaxedJsonEscaping
    $json = [Text.Json.JsonSerializer]::Serialize(
        [object]$Value, [type][string], $options)
    $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json))
    [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Get-ProductionPostgreSqlCutoverCanonicalMapHash {
    param([Parameter(Mandatory)][Collections.IDictionary]$Values)
    $options = [Text.Json.JsonSerializerOptions]::new()
    $options.Encoder = [Text.Encodings.Web.JavaScriptEncoder]::UnsafeRelaxedJsonEscaping
    $pairs = foreach ($key in @($Values.Keys | Sort-Object -CaseSensitive)) {
        [Text.Json.JsonSerializer]::Serialize(
            [object][string]$key, [type][string], $options) + ':' +
            [Text.Json.JsonSerializer]::Serialize(
                [object][string]$Values[$key], [type][string], $options)
    }
    $json = '{' + ($pairs -join ',') + '}'
    $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json))
    [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Get-ProductionPostgreSqlCutoverJavaEnvironment {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal,
        [Parameter(Mandatory)][string]$BridgePassword
    )
    @{
        POSTGRESQL_MIGRATION_SOURCE_URI = 'mongodb://127.0.0.1:27017/christopherbell'
        POSTGRESQL_MIGRATION_SOURCE_DATABASE = 'christopherbell'
        POSTGRESQL_MIGRATION_TARGET_JDBC_URL = 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
        POSTGRESQL_MIGRATION_TARGET_DATABASE = 'christopherbell'
        POSTGRESQL_MIGRATION_TARGET_ROLE = 'christopherbell_bridge'
        POSTGRESQL_MIGRATION_SCHEMA_PREFIX = ''
        POSTGRESQL_MIGRATION_RELEASE = [string]$Journal.release
        POSTGRESQL_MIGRATION_BRIDGE_RELEASE = '1'
        POSTGRESQL_MIGRATION_LOCK_TOKEN = [string]$Journal.lockToken
        POSTGRESQL_MIGRATION_BATCH_SIZE = '500'
        POSTGRESQL_MIGRATION_TARGET_USERNAME = 'christopherbell_bridge'
        POSTGRESQL_MIGRATION_TARGET_PASSWORD = $BridgePassword
    }
}

function Invoke-ProductionPostgreSqlCutoverJava {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal,
        [Parameter(Mandatory)][ValidateSet('snapshot','finalize','reconcile')][string]$Command,
        [Parameter(Mandatory)][string]$BridgePassword,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction
    )
    $release = Get-ProductionPostgreSqlCutoverRelease -Config $Config -Journal $Journal
    $main = if ($Command -eq 'snapshot') {
        'dev.christopherbell.configuration.persistence.migration.PostgresqlMigrationSourceSnapshotCli'
    } else {
        'dev.christopherbell.configuration.persistence.migration.PostgresqlMigrationCli'
    }
    $output = & $ProcessAction $Config.javaExe @(
        "-Dloader.main=$main",'-cp',(Join-Path $release 'app.jar'),
        'org.springframework.boot.loader.launch.PropertiesLauncher',$Command
    ) (Get-ProductionPostgreSqlCutoverJavaEnvironment `
        -Config $Config -Journal $Journal -BridgePassword $BridgePassword)
    return ([string]$output).Trim()
}

function New-ProductionPostgreSqlCutoverPreflight {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        $ExistingJournal
    )
    Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
    if ([string]$Config.programDataRoot -cne 'C:\ProgramData\christopherbell.dev' -or
        [int]$Config.productionPort -ne 8080) {
        throw 'The PostgreSQL cutover production identity is invalid.'
    }
    $status = Get-ProductionPostgreSqlStatus -Config $Config
    if ([string]$status.Service -cne 'Running' -or
        [string]$status.Database -cne 'christopherbell' -or
        [string]$status.Role -cne 'christopherbell_app') {
        throw 'The PostgreSQL cutover target is not ready.'
    }
    $releaseSha = Resolve-OriginMainRelease -Config $Config
    if ($ExistingJournal -and [string]$ExistingJournal.release -cne $releaseSha) {
        throw 'The PostgreSQL cutover release changed during the maintenance window.'
    }
    if (-not $ExistingJournal -and (Test-Path -LiteralPath (
            Get-ProductionPostgreSqlCutoverSidecarPath -Config $Config -Name 'authority'))) {
        throw 'PostgreSQL authority already exists without a resumable cutover journal.'
    }
    $release = New-ReleaseFromOriginMain -Config $Config -Sha $releaseSha
    if ([IO.Path]::GetFileName($release) -cne $releaseSha) {
        throw 'The PostgreSQL cutover release identity is invalid.'
    }
    $catalogDigest = Get-ProductionPostgreSqlCutoverCatalogDigest -Release $release
    $target = 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
    $targetHash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($target))
    [pscustomobject][ordered]@{
        release = $releaseSha
        lockToken = if ($ExistingJournal) {
            [string]$ExistingJournal.lockToken
        } else {
            [guid]::NewGuid().ToString()
        }
        sourceDatabase = 'christopherbell'
        targetDatabase = 'christopherbell'
        catalogDigest = $catalogDigest
        targetJdbcDigest = [Convert]::ToHexString($targetHash).ToLowerInvariant()
    }
}

function Invoke-WithProductionPostgreSqlCutoverLock {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    $lock = Enter-DeploymentLock -LockPath (
        Join-Path $Config.programDataRoot 'locks\deploy.lock')
    try { return & $Action }
    finally { $lock.Dispose() }
}

function Assert-ProductionPostgreSqlCutoverWriterStopped {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    $service = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    if ([string]$service.Status -cne 'Stopped') {
        throw 'The PostgreSQL cutover website writer is not stopped.'
    }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object LocalPort -eq ([int]$Config.productionPort))
    if ($listeners.Count -ne 0) {
        throw 'The PostgreSQL cutover production listener is still active.'
    }
}

function Get-ProductionPostgreSqlCutoverSecrets {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    $secrets = Read-ProductionPostgreSqlSecrets -Path (
        Join-Path $Config.programDataRoot 'config\postgresql.env')
    foreach ($role in 'Bridge','App','Backup') {
        Assert-ProductionMigrationSecret -Role $role.ToLowerInvariant() `
            -Secret ([string]$secrets.Roles[$role])
    }
    if ([string]$secrets.Roles.Bridge -ceq [string]$secrets.Roles.App -or
        [string]$secrets.Roles.Bridge -ceq [string]$secrets.Roles.Backup -or
        [string]$secrets.Roles.App -ceq [string]$secrets.Roles.Backup) {
        throw 'The PostgreSQL cutover role secrets must be distinct.'
    }
    return $secrets
}

function Stop-ProductionPostgreSqlCutoverWriters {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        Stop-ProductionWebsiteService -ProductionPort $Config.productionPort `
            -KeepRecoverySuspended
        Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
        $legacy = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
        if (-not $legacy -or [IO.Path]::GetFileName($legacy) -cnotmatch '^[0-9a-f]{40}$') {
            throw 'The PostgreSQL cutover legacy release identity is invalid.'
        }
        $value = [pscustomobject][ordered]@{
            release = [string]$Journal.release
            legacyRelease = [IO.Path]::GetFileName($legacy)
            lockToken = [string]$Journal.lockToken
            service = 'Stopped'
            productionPort = [int]$Config.productionPort
            recovery = 'Suspended'
            observedAt = [datetimeoffset]::UtcNow.ToString('o')
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'writers-stopped' -Value $value
        return @{ digest=$digest }
    }
}

function New-ProductionPostgreSqlCutoverMongoArchive {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
        $archive = New-ProductionBackup
        $evidencePath = "$archive.sha256.json"
        if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or
            -not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
            throw 'The PostgreSQL cutover final Mongo archive evidence is missing.'
        }
        $evidence = Get-Content -LiteralPath $evidencePath -Raw |
            ConvertFrom-Json -ErrorAction Stop
        $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([string]$evidence.sha256 -cnotmatch '^[0-9A-Fa-f]{64}$' -or
            ([string]$evidence.sha256).ToLowerInvariant() -cne $actual) {
            throw 'The PostgreSQL cutover final Mongo archive checksum is invalid.'
        }
        $value = [pscustomobject][ordered]@{
            release = [string]$Journal.release
            lockToken = [string]$Journal.lockToken
            database = 'christopherbell'
            archive = [IO.Path]::GetFullPath($archive)
            sha256 = $actual
            dryRestoreVerified = $true
            createdAt = [string]$evidence.createdAt
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'mongo-archive' -Value $value
        return @{ digest=$digest }
    }
}

function Protect-ProductionPostgreSqlCutoverAuthority {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$SourceDigest,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$BackupDigest
    )
    $root = Join-Path $Config.programDataRoot 'postgresql-migration-authority'
    if ([IO.Path]::GetFullPath($root) -cne
        'C:\ProgramData\christopherbell.dev\postgresql-migration-authority') {
        throw 'The PostgreSQL cutover authority root is invalid.'
    }
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        New-Item -ItemType Directory -Path $root | Out-Null
    }
    Protect-ProductionPath -Path $root -Directory | Out-Null
    Assert-ProtectedProductionPath -Path $root | Out-Null
    $keyPath = Join-Path $root 'authority.key'
    if (-not (Test-Path -LiteralPath $keyPath -PathType Leaf)) {
        $key = [byte[]]::new(64)
        [Security.Cryptography.RandomNumberGenerator]::Fill($key)
        [IO.File]::WriteAllBytes($keyPath, $key)
        Protect-ProductionPath -Path $keyPath | Out-Null
    }
    Assert-ProductionPathNotReparse -Path $keyPath | Out-Null
    Assert-ProtectedProductionPath -Path $keyPath | Out-Null
    $key = [IO.File]::ReadAllBytes($keyPath)
    if ($key.Length -lt 32) { throw 'The PostgreSQL cutover authority key is invalid.' }
    $writerLockPath = Join-Path $root 'writer.lock'
    $writerLock = @(
        "lockToken=$($Journal.lockToken)",
        "release=$($Journal.release)",
        'state=frozen',
        "leaseExpiresAt=$([datetimeoffset][string]$Journal.deadlineAt)"
    ) -join "`n"
    [IO.File]::WriteAllText($writerLockPath, $writerLock, [Text.UTF8Encoding]::new($false))
    Protect-ProductionPath -Path $writerLockPath | Out-Null
    Assert-ProtectedProductionPath -Path $writerLockPath | Out-Null
    $writerDigest = Get-ProductionPostgreSqlCutoverCanonicalStringHash -Value $writerLock
    $values = [ordered]@{
        release = [string]$Journal.release
        catalogDigest = [string]$Journal.catalogDigest
        sourceDatabase = 'christopherbell'
        targetDatabase = 'christopherbell'
        sourceDigest = $SourceDigest
        backupDigest = $BackupDigest
        lockToken = [string]$Journal.lockToken
        sourceUri = 'mongodb://127.0.0.1:27017/christopherbell'
        targetJdbcUrl = 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
        targetRole = 'christopherbell_bridge'
        writerLockPath = [IO.Path]::GetFullPath($writerLockPath)
        writerLockDigest = $writerDigest
    }
    $evidenceDigest = Get-ProductionPostgreSqlCutoverCanonicalMapHash -Values $values
    $hmac = [Security.Cryptography.HMACSHA256]::new($key)
    try {
        $signature = [Convert]::ToHexString($hmac.ComputeHash(
            [Text.Encoding]::ASCII.GetBytes($evidenceDigest))).ToLowerInvariant()
    } finally { $hmac.Dispose() }
    $properties = [Collections.Generic.List[string]]::new()
    foreach ($entry in $values.GetEnumerator()) {
        $encoded = ([string]$entry.Value).Replace('\','\\')
        $properties.Add("$($entry.Key)=$encoded")
    }
    $properties.Add("evidenceDigest=$evidenceDigest")
    $properties.Add("signature=$signature")
    $evidencePath = Join-Path $root 'finalize.properties'
    [IO.File]::WriteAllText($evidencePath, ($properties -join "`n"),
        [Text.Encoding]::ASCII)
    Protect-ProductionPath -Path $evidencePath | Out-Null
    Assert-ProtectedProductionPath -Path $evidencePath | Out-Null
    return [pscustomobject][ordered]@{
        sourceDigest = $SourceDigest
        backupDigest = $BackupDigest
        writerLockDigest = $writerDigest
        evidenceDigest = $evidenceDigest
    }
}

function Invoke-ProductionPostgreSqlCutoverFinalize {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
    $archiveDigest = Get-ProductionPostgreSqlCutoverTransitionDigest `
        -Journal $Journal -Phase 'MONGO_ARCHIVED'
    $archive = Read-ProductionPostgreSqlCutoverSidecar `
        -Config $Config -Name 'mongo-archive' -ExpectedDigest $archiveDigest
    if ([string]$archive.release -cne [string]$Journal.release -or
        [string]$archive.lockToken -cne [string]$Journal.lockToken -or
        [string]$archive.database -cne 'christopherbell' -or
        -not [bool]$archive.dryRestoreVerified -or
        [string]$archive.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'The PostgreSQL cutover final Mongo archive evidence is invalid.'
    }
    $secrets = Get-ProductionPostgreSqlCutoverSecrets -Config $Config
    $snapshot = Invoke-ProductionPostgreSqlCutoverJava -Config $Config -Journal $Journal `
        -Command snapshot -BridgePassword ([string]$secrets.Roles.Bridge)
    $match = [regex]::Match($snapshot,
        '^catalogDigest=(?<Catalog>[0-9a-f]{64}) sourceDigest=(?<Source>[0-9a-f]{64}) kinds=52$')
    if (-not $match.Success -or
        $match.Groups['Catalog'].Value -cne [string]$Journal.catalogDigest) {
        throw 'The PostgreSQL cutover source snapshot evidence is invalid.'
    }
    $authority = Protect-ProductionPostgreSqlCutoverAuthority `
        -Config $Config -Journal $Journal `
        -SourceDigest $match.Groups['Source'].Value `
        -BackupDigest ([string]$archive.sha256)
    $output = Invoke-ProductionPostgreSqlCutoverJava -Config $Config -Journal $Journal `
        -Command finalize -BridgePassword ([string]$secrets.Roles.Bridge)
    $result = [regex]::Match($output,
        '^command=finalize kinds=52 statusDigest=(?<Status>[0-9a-f]{64})$')
    if (-not $result.Success) {
        throw 'The PostgreSQL cutover finalization result is invalid.'
    }
    $value = [pscustomobject][ordered]@{
        release = [string]$Journal.release
        lockToken = [string]$Journal.lockToken
        catalogDigest = [string]$Journal.catalogDigest
        sourceDigest = [string]$authority.sourceDigest
        backupDigest = [string]$authority.backupDigest
        writerLockDigest = [string]$authority.writerLockDigest
        evidenceDigest = [string]$authority.evidenceDigest
        statusDigest = $result.Groups['Status'].Value
        kindCount = 52
        publicationCommitted = $true
        finalizedAt = [datetimeoffset]::UtcNow.ToString('o')
    }
    $digest = Write-ProductionPostgreSqlCutoverSidecar `
        -Config $Config -Name 'postgresql-finalized' -Value $value
    return @{ digest=$digest }
}

function Invoke-ProductionPostgreSqlCutoverReconcile {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
        $finalizedDigest = Get-ProductionPostgreSqlCutoverTransitionDigest `
            -Journal $Journal -Phase 'POSTGRESQL_FINALIZED'
        $finalized = Read-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'postgresql-finalized' -ExpectedDigest $finalizedDigest
        if ([string]$finalized.release -cne [string]$Journal.release -or
            [string]$finalized.lockToken -cne [string]$Journal.lockToken -or
            -not [bool]$finalized.publicationCommitted) {
            throw 'The PostgreSQL cutover finalization evidence is invalid.'
        }
        $secrets = Get-ProductionPostgreSqlCutoverSecrets -Config $Config
        $output = Invoke-ProductionPostgreSqlCutoverJava `
            -Config $Config -Journal $Journal -Command reconcile `
            -BridgePassword ([string]$secrets.Roles.Bridge)
        $result = [regex]::Match($output,
            '^command=reconcile kinds=52 statusDigest=(?<Status>[0-9a-f]{64})$')
        if (-not $result.Success -or
            $result.Groups['Status'].Value -cne [string]$finalized.statusDigest) {
            throw 'The PostgreSQL cutover reconciliation digest drifted.'
        }
        $value = [pscustomobject][ordered]@{
            release = [string]$Journal.release
            lockToken = [string]$Journal.lockToken
            sourceDigest = [string]$finalized.sourceDigest
            statusDigest = $result.Groups['Status'].Value
            kindCount = 52
            relationshipsVerified = $true
            reconciledAt = [datetimeoffset]::UtcNow.ToString('o')
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'postgresql-reconciled' -Value $value
        return @{ digest=$digest }
    }
}

function New-ProductionPostgreSqlCutoverBackup {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
    $backup = New-ProductionPostgreSqlBackup -Config $Config
    if (-not $backup -or [string]$backup.Sha256 -cnotmatch '^[0-9A-Fa-f]{64}$' -or
        -not [bool]$backup.Restore.Restored) {
        throw 'The PostgreSQL cutover backup restore proof is invalid.'
    }
    $value = [pscustomobject][ordered]@{
        release = [string]$Journal.release
        lockToken = [string]$Journal.lockToken
        database = 'christopherbell'
        archive = [IO.Path]::GetFullPath([string]$backup.Archive)
        sha256 = ([string]$backup.Sha256).ToLowerInvariant()
        restoreDatabase = [string]$backup.Restore.Database
        restoreVerified = $true
        createdAt = [datetimeoffset]::UtcNow.ToString('o')
    }
    $digest = Write-ProductionPostgreSqlCutoverSidecar `
        -Config $Config -Name 'postgresql-backup' -Value $value
    return @{ digest=$digest }
}

function Test-ProductionPostgreSqlCutoverCandidate {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
        $release = Get-ProductionPostgreSqlCutoverRelease -Config $Config -Journal $Journal
        $secrets = Get-ProductionPostgreSqlCutoverSecrets -Config $Config
        Test-CandidateRelease -Config $Config -Release $release -AdditionalEnvironment @{
            APP_PERSISTENCE_BACKEND = 'postgresql'
            SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
            SPRING_DATASOURCE_USERNAME = 'christopherbell_app'
            SPRING_DATASOURCE_PASSWORD = [string]$secrets.Roles.App
        } | Out-Null
        $value = [pscustomobject][ordered]@{
            release = [string]$Journal.release
            database = 'christopherbell'
            role = 'christopherbell_app'
            port = [int]$Config.candidatePort
            backend = 'postgresql'
            verified = $true
            verifiedAt = [datetimeoffset]::UtcNow.ToString('o')
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'candidate' -Value $value
        return @{ digest=$digest }
    }
}

function Set-ProductionPostgreSqlCutoverEnvironment {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$AppPassword
    )
    $path = Join-Path $Config.programDataRoot 'config\app.env'
    $values = Read-ProductionEnvironment -Path $path
    $values.APP_PERSISTENCE_BACKEND = 'postgresql'
    $values.Remove('SPRING_MONGODB_URI')
    $values.SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
    $values.SPRING_DATASOURCE_USERNAME = 'christopherbell_app'
    $values.SPRING_DATASOURCE_PASSWORD = $AppPassword
    $order = @('APP_JWT_SECRET','APP_MAIL_ENABLED','RESEND_API_KEY','APP_MAIL_FROM',
        'APP_PERSISTENCE_BACKEND','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
        'SPRING_DATASOURCE_PASSWORD','CLIENT_IP_TRUSTED_PROXIES','APP_SHARED_FOLDER_ENABLED')
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $lines = foreach ($name in $order) {
            if ($values.ContainsKey($name)) { "$name=$($values[$name])" }
        }
        [IO.File]::WriteAllLines($temporary, $lines, [Text.UTF8Encoding]::new($false))
        Protect-ProductionPath -Path $temporary | Out-Null
        Assert-ProtectedProductionPath -Path $temporary | Out-Null
        Move-Item -LiteralPath $temporary -Destination $path -Force
        Protect-ProductionPath -Path $path | Out-Null
        Assert-ProtectedProductionPath -Path $path | Out-Null
        $readback = Read-ProductionEnvironment -Path $path
        if ([string]$readback.APP_PERSISTENCE_BACKEND -cne 'postgresql' -or
            [string]$readback.SPRING_DATASOURCE_URL -cne
                'jdbc:postgresql://127.0.0.1:5432/christopherbell' -or
            [string]$readback.SPRING_DATASOURCE_USERNAME -cne 'christopherbell_app' -or
            [string]$readback.SPRING_DATASOURCE_PASSWORD -cne $AppPassword -or
            $readback.ContainsKey('SPRING_MONGODB_URI')) {
            throw 'The PostgreSQL cutover application environment readback failed.'
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-ProductionPostgreSqlCutoverAuthorityPrerequisites {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    $reconciled = Read-ProductionPostgreSqlCutoverSidecar -Config $Config `
        -Name 'postgresql-reconciled' -ExpectedDigest (
            Get-ProductionPostgreSqlCutoverTransitionDigest `
                -Journal $Journal -Phase 'POSTGRESQL_RECONCILED')
    $backup = Read-ProductionPostgreSqlCutoverSidecar -Config $Config `
        -Name 'postgresql-backup' -ExpectedDigest (
            Get-ProductionPostgreSqlCutoverTransitionDigest `
                -Journal $Journal -Phase 'POSTGRESQL_BACKED_UP')
    $candidate = Read-ProductionPostgreSqlCutoverSidecar -Config $Config `
        -Name 'candidate' -ExpectedDigest (
            Get-ProductionPostgreSqlCutoverTransitionDigest `
                -Journal $Journal -Phase 'CANDIDATE_VERIFIED')
    if ([string]$reconciled.release -cne [string]$Journal.release -or
        [int]$reconciled.kindCount -ne 52 -or
        -not [bool]$reconciled.relationshipsVerified -or
        [string]$backup.release -cne [string]$Journal.release -or
        -not [bool]$backup.restoreVerified -or
        [string]$candidate.release -cne [string]$Journal.release -or
        -not [bool]$candidate.verified) {
        throw 'The PostgreSQL cutover authority prerequisites are invalid.'
    }
    return [pscustomobject]@{
        Reconciled = $reconciled
        Backup = $backup
        Candidate = $candidate
    }
}

function Publish-ProductionPostgreSqlCutoverAuthority {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
        $prerequisites = Get-ProductionPostgreSqlCutoverAuthorityPrerequisites `
            -Config $Config -Journal $Journal
        $reconciled = $prerequisites.Reconciled
        $backup = $prerequisites.Backup
        $secrets = Get-ProductionPostgreSqlCutoverSecrets -Config $Config
        Set-ProductionPostgreSqlCutoverEnvironment -Config $Config `
            -AppPassword ([string]$secrets.Roles.App)
        Ensure-ProductionWriterStartGuardUnderHeldLock -Config $Config
        Stop-Service -Name 'MongoDB' -ErrorAction Stop
        $mongo = Get-Service -Name 'MongoDB' -ErrorAction Stop
        $mongo.WaitForStatus(
            [System.ServiceProcess.ServiceControllerStatus]::Stopped,
            [timespan]::FromSeconds(30))
        $mongo.Refresh()
        if ([string]$mongo.Status -cne 'Stopped') {
            throw 'MongoDB did not stop before PostgreSQL authority publication.'
        }
        $value = [pscustomobject][ordered]@{
            version = 1
            state = 'POSTGRESQL_AUTHORITY'
            release = [string]$Journal.release
            lockToken = [string]$Journal.lockToken
            sourceDatabase = 'christopherbell'
            targetDatabase = 'christopherbell'
            catalogDigest = [string]$Journal.catalogDigest
            sourceDigest = [string]$reconciled.sourceDigest
            reconciliationDigest = [string]$reconciled.statusDigest
            mongoArchiveDigest = [string](
                (Read-ProductionPostgreSqlCutoverSidecar -Config $Config `
                    -Name 'mongo-archive' -ExpectedDigest (
                        Get-ProductionPostgreSqlCutoverTransitionDigest `
                            -Journal $Journal -Phase 'MONGO_ARCHIVED')).sha256)
            postgresqlBackupDigest = [string]$backup.sha256
            publishedAt = [datetimeoffset]::UtcNow.ToString('o')
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'authority' -Value $value
        return @{ digest=$digest }
    }
}

function New-ProductionPostgreSqlCutoverAuthorityIntent {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        Assert-ProductionPostgreSqlCutoverWriterStopped -Config $Config
        $prerequisites = Get-ProductionPostgreSqlCutoverAuthorityPrerequisites `
            -Config $Config -Journal $Journal
        $value = [pscustomobject][ordered]@{
            version = 1
            state = 'AUTHORITY_PUBLICATION_STARTED'
            release = [string]$Journal.release
            lockToken = [string]$Journal.lockToken
            catalogDigest = [string]$Journal.catalogDigest
            candidateDigest = Get-ProductionPostgreSqlCutoverTransitionDigest `
                -Journal $Journal -Phase 'CANDIDATE_VERIFIED'
            reconciliationDigest = [string]$prerequisites.Reconciled.statusDigest
            postgresqlBackupDigest = [string]$prerequisites.Backup.sha256
            startedAt = [datetimeoffset]::UtcNow.ToString('o')
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'authority-intent' -Value $value
        return @{ digest=$digest }
    }
}

function Start-ProductionPostgreSqlCutoverRelease {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        $authority = Read-ProductionPostgreSqlCutoverSidecar -Config $Config `
            -Name 'authority' -ExpectedDigest (
                Get-ProductionPostgreSqlCutoverTransitionDigest `
                    -Journal $Journal -Phase 'AUTHORITY_PUBLISHED')
        if ([string]$authority.state -cne 'POSTGRESQL_AUTHORITY' -or
            [string]$authority.release -cne [string]$Journal.release) {
            throw 'The PostgreSQL cutover authority marker is invalid.'
        }
        $release = Get-ProductionPostgreSqlCutoverRelease -Config $Config -Journal $Journal
        $current = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
        $website = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
        $alreadyActive = $current -and
            [IO.Path]::GetFullPath($current) -ceq [IO.Path]::GetFullPath($release) -and
            [string]$website.Status -ceq 'Running'
        if ($alreadyActive) {
            $mongo = Get-Service -Name 'MongoDB' -ErrorAction Stop
            $postgres = Get-Service -Name $Config.postgresqlServiceName -ErrorAction Stop
            if ([string]$mongo.Status -cne 'Stopped' -or
                [string]$postgres.Status -cne 'Running') {
                throw 'The resumed PostgreSQL cutover service authority is invalid.'
            }
            Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
            Test-ProductionPublicEndpoints -Config $Config | Out-Null
        } else {
            Switch-ProductionRelease -Config $Config -Release $release `
                -AuthorizationMarkerState 'TARGET_ACTIVE' `
                -AuthorizationPurpose 'TARGET_DEPLOY' `
                -AuthorizationRelease ([string]$Journal.release) `
                -KeepRecoverySuspended -WriterAlreadyStopped
            Set-ProductionWebsiteRecoveryPolicy -Policy Normal
        }
        $value = [pscustomobject][ordered]@{
            release = [string]$Journal.release
            port = [int]$Config.productionPort
            backend = 'postgresql'
            service = 'Running'
            activatedAt = [datetimeoffset]::UtcNow.ToString('o')
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'production-active' -Value $value
        return @{ digest=$digest }
    }
}

function Test-ProductionPostgreSqlCutoverProduction {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    $release = Get-ProductionPostgreSqlCutoverRelease -Config $Config -Journal $Journal
    $current = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    $website = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    $mongo = Get-Service -Name 'MongoDB' -ErrorAction Stop
    $postgres = Get-Service -Name $Config.postgresqlServiceName -ErrorAction Stop
    if ([IO.Path]::GetFullPath($current) -cne [IO.Path]::GetFullPath($release) -or
        [string]$website.Status -cne 'Running' -or
        [string]$mongo.Status -cne 'Stopped' -or
        [string]$postgres.Status -cne 'Running') {
        throw 'The PostgreSQL cutover service identity verification failed.'
    }
    Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
    Test-ProductionPublicEndpoints -Config $Config | Out-Null
    $status = Get-ProductionPostgreSqlStatus -Config $Config
    if ([string]$status.Database -cne 'christopherbell' -or
        [string]$status.Role -cne 'christopherbell_app' -or
        -not [bool]$status.ViewerReadOnly) {
        throw 'The PostgreSQL cutover database acceptance failed.'
    }
    $value = [pscustomobject][ordered]@{
        release = [string]$Journal.release
        backend = 'postgresql'
        database = 'christopherbell'
        role = 'christopherbell_app'
        viewerReadOnly = $true
        publicVerified = $true
        verifiedAt = [datetimeoffset]::UtcNow.ToString('o')
    }
    $digest = Write-ProductionPostgreSqlCutoverSidecar `
        -Config $Config -Name 'production-verified' -Value $value
    return @{ digest=$digest }
}

function Enter-ProductionPostgreSqlCutoverSoak {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        $productionDigest = Get-ProductionPostgreSqlCutoverTransitionDigest `
            -Journal $Journal -Phase 'PRODUCTION_VERIFIED'
        $production = Read-ProductionPostgreSqlCutoverSidecar -Config $Config `
            -Name 'production-verified' -ExpectedDigest $productionDigest
        if ([string]$production.backend -cne 'postgresql' -or
            -not [bool]$production.publicVerified) {
            throw 'The PostgreSQL cutover production proof is invalid.'
        }
        $now = [datetimeoffset]::UtcNow
        $value = [pscustomobject][ordered]@{
            version = 1
            state = 'SOAKING'
            release = [string]$Journal.release
            lockToken = [string]$Journal.lockToken
            startedAt = $now.ToString('o')
            requiredThrough = $now.AddDays(14).ToString('o')
            mongoArchiveRetainThrough = $now.AddDays(90).ToString('o')
            mongoService = 'Stopped'
        }
        $digest = Write-ProductionPostgreSqlCutoverSidecar `
            -Config $Config -Name 'soak' -Value $value
        return @{ digest=$digest }
    }
}

function Restore-ProductionPostgreSqlCutoverPreAuthority {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)]$Journal
    )
    Invoke-WithProductionPostgreSqlCutoverLock -Config $Config -Action {
        if (Test-Path -LiteralPath (
            Get-ProductionPostgreSqlCutoverSidecarPath -Config $Config -Name 'authority')) {
            throw 'PostgreSQL authority exists; Mongo recovery is forbidden.'
        }
        $mongo = Get-Service -Name 'MongoDB' -ErrorAction Stop
        if ([string]$mongo.Status -ne 'Running') {
            Start-Service -Name 'MongoDB' -ErrorAction Stop
            $mongo.WaitForStatus(
                [System.ServiceProcess.ServiceControllerStatus]::Running,
                [timespan]::FromSeconds(30))
        }
        Assert-ProductionPostgreSqlCutoverMongoUnlocked -Config $Config
        Set-ProductionWebsiteRecoveryPolicy -Policy Normal
        Start-Service -Name 'ChristopherBellDev' -ErrorAction Stop
        Test-ProductionEndpoints -Config $Config -Port $Config.productionPort
        Test-ProductionPublicEndpoints -Config $Config | Out-Null
        $text = "release=$($Journal.release);lockToken=$($Journal.lockToken);restored=true"
        $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))
        return @{ digest=[Convert]::ToHexString($hash).ToLowerInvariant() }
    }
}

function Assert-ProductionPostgreSqlCutoverMongoUnlocked {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction
    )
    $query = "const state=db.getSiblingDB('admin').runCommand({currentOp:1});" +
        "print(JSON.stringify({fsyncLock:state.fsyncLock === true}));"
    $output = & $ProcessAction $Config.mongoShellExe @(
        '--quiet','--norc','mongodb://127.0.0.1:27017/admin','--eval',$query) @{}
    try { $lockState = ([string]$output).Trim() | ConvertFrom-Json -ErrorAction Stop }
    catch {
        throw [IO.InvalidDataException]::new(
            'MongoDB lock state could not be verified before recovery.', $_.Exception)
    }
    if ($lockState.fsyncLock -isnot [bool] -or [bool]$lockState.fsyncLock) {
        throw 'MongoDB remains fsync-locked; authenticated manual unlock is required.'
    }
}

function New-ProductionPostgreSqlCutoverActions {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    $cutoverConfig = $Config
    $actions = @{
        Preflight = { param($Value,$Existing) New-ProductionPostgreSqlCutoverPreflight `
            -Config $Value -ExistingJournal $Existing }.GetNewClosure()
        StopWriters = {
            param($State) Stop-ProductionPostgreSqlCutoverWriters `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        ArchiveMongo = {
            param($State) New-ProductionPostgreSqlCutoverMongoArchive `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        FinalizePostgreSql = {
            param($State) Invoke-ProductionPostgreSqlCutoverFinalize `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        ReconcilePostgreSql = {
            param($State) Invoke-ProductionPostgreSqlCutoverReconcile `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        BackupPostgreSql = {
            param($State) New-ProductionPostgreSqlCutoverBackup `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        VerifyCandidate = {
            param($State) Test-ProductionPostgreSqlCutoverCandidate `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        PublishAuthority = {
            param($State) Publish-ProductionPostgreSqlCutoverAuthority `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        PrepareAuthority = {
            param($State) New-ProductionPostgreSqlCutoverAuthorityIntent `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        ActivateProduction = {
            param($State) Start-ProductionPostgreSqlCutoverRelease `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        VerifyProduction = {
            param($State) Test-ProductionPostgreSqlCutoverProduction `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        EnterSoak = {
            param($State) Enter-ProductionPostgreSqlCutoverSoak `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
        RestorePreAuthority = {
            param($State) Restore-ProductionPostgreSqlCutoverPreAuthority `
                -Config $cutoverConfig -Journal $State
        }.GetNewClosure()
    }
    return $actions
}

function Invoke-ProductionPostgreSqlCutover {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [switch]$ConfirmPostgreSqlCutover,
        [ValidateRange(1,30)][int]$MaintenanceBudgetMinutes = 30,
        [hashtable]$Actions,
        [scriptblock]$ReadJournalAction,
        [scriptblock]$WriteJournalAction,
        [scriptblock]$ClockAction = { [datetimeoffset]::UtcNow }
    )
    if (-not $WhatIfPreference -and -not $ConfirmPostgreSqlCutover) {
        throw 'PostgreSQL authority cutover requires explicit confirmation.'
    }
    if (-not $PSCmdlet.ShouldProcess('production persistence authority',
        'freeze MongoDB and transfer authority to PostgreSQL')) { return }
    if (-not $Config) { $Config = Read-ProductionConfig }
    if (-not $Actions) { $Actions = New-ProductionPostgreSqlCutoverActions -Config $Config }
    if (-not $ReadJournalAction) {
        $ReadJournalAction = { Read-ProductionPostgreSqlCutoverJournal -Config $Config }
    }
    if (-not $WriteJournalAction) {
        $WriteJournalAction = {
            param($Journal)
            Write-ProductionPostgreSqlCutoverJournal -Config $Config -Journal $Journal
        }
    }
    $requiredActions = @('Preflight','StopWriters','ArchiveMongo','FinalizePostgreSql',
        'ReconcilePostgreSql','BackupPostgreSql','VerifyCandidate','PrepareAuthority','PublishAuthority',
        'ActivateProduction','VerifyProduction','EnterSoak','RestorePreAuthority')
    if (@($requiredActions | Where-Object { -not $Actions.ContainsKey($_) }).Count -ne 0) {
        throw 'The PostgreSQL cutover action boundary is incomplete.'
    }

    $journal = & $ReadJournalAction
    if ($journal) {
        $journal = Assert-ProductionPostgreSqlCutoverJournal -Journal $journal
        if ([string]$journal.phase -in @('SOAKING','ROLLED_BACK','FORWARD_RECOVERY_REQUIRED')) {
            return $journal
        }
        $preflight = & $Actions.Preflight $Config $journal
        if ([string]$preflight.release -cne [string]$journal.release -or
            [string]$preflight.lockToken -cne [string]$journal.lockToken -or
            [string]$preflight.sourceDatabase -cne [string]$journal.sourceDatabase -or
            [string]$preflight.targetDatabase -cne [string]$journal.targetDatabase -or
            [string]$preflight.catalogDigest -cne [string]$journal.catalogDigest -or
            [string]$preflight.targetJdbcDigest -cne [string]$journal.targetJdbcDigest) {
            throw 'The PostgreSQL cutover resume identity is invalid.'
        }
    } else {
        $preflight = & $Actions.Preflight $Config $null
        $journal = New-ProductionPostgreSqlCutoverJournal -Preflight $preflight `
            -Now (& $ClockAction) -MaintenanceBudgetMinutes $MaintenanceBudgetMinutes
        & $WriteJournalAction $journal
    }

    $steps = [ordered]@{
        PLANNED = @('StopWriters','WRITERS_STOPPED')
        WRITERS_STOPPED = @('ArchiveMongo','MONGO_ARCHIVED')
        MONGO_ARCHIVED = @('FinalizePostgreSql','POSTGRESQL_FINALIZED')
        POSTGRESQL_FINALIZED = @('ReconcilePostgreSql','POSTGRESQL_RECONCILED')
        POSTGRESQL_RECONCILED = @('BackupPostgreSql','POSTGRESQL_BACKED_UP')
        POSTGRESQL_BACKED_UP = @('VerifyCandidate','CANDIDATE_VERIFIED')
        AUTHORITY_PUBLICATION_STARTED = @('PublishAuthority','AUTHORITY_PUBLISHED')
        AUTHORITY_PUBLISHED = @('ActivateProduction','PRODUCTION_ACTIVE')
        PRODUCTION_ACTIVE = @('VerifyProduction','PRODUCTION_VERIFIED')
        PRODUCTION_VERIFIED = @('EnterSoak','SOAKING')
    }
    try {
        while ([string]$journal.phase -ne 'SOAKING') {
            $now = & $ClockAction
            if ($now -gt [datetimeoffset][string]$journal.deadlineAt) {
                throw 'The PostgreSQL cutover maintenance budget was exceeded.'
            }
            if ([string]$journal.phase -eq 'CANDIDATE_VERIFIED') {
                $intent = & $Actions.PrepareAuthority $journal
                $intentDigest = Get-ProductionPostgreSqlCutoverEvidenceDigest -Evidence $intent
                $completedAt = & $ClockAction
                if ($completedAt -gt [datetimeoffset][string]$journal.deadlineAt) {
                    throw 'The PostgreSQL cutover maintenance budget was exceeded.'
                }
                $journal = Add-ProductionPostgreSqlCutoverTransition -Journal $journal `
                    -Next 'AUTHORITY_PUBLICATION_STARTED' -EvidenceDigest $intentDigest `
                    -Now $completedAt -AuthorityPublished $true
                & $WriteJournalAction $journal
                continue
            }
            if (-not $steps.Contains([string]$journal.phase)) {
                throw 'The PostgreSQL cutover journal phase is not resumable.'
            }
            $step = $steps[[string]$journal.phase]
            $evidence = & $Actions[$step[0]] $journal
            $digest = Get-ProductionPostgreSqlCutoverEvidenceDigest -Evidence $evidence
            $completedAt = & $ClockAction
            if ($completedAt -gt [datetimeoffset][string]$journal.deadlineAt) {
                throw 'The PostgreSQL cutover maintenance budget was exceeded.'
            }
            $journal = Add-ProductionPostgreSqlCutoverTransition -Journal $journal `
                -Next $step[1] -EvidenceDigest $digest -Now $completedAt
            & $WriteJournalAction $journal
        }
        return $journal
    } catch {
        $operationFailure = $_.Exception
        try {
            if ([bool]$journal.authorityPublished) {
                $failureDigest = Get-ProductionPostgreSqlCutoverCanonicalMapHash -Values ([ordered]@{
                    release = [string]$journal.release
                    lockToken = [string]$journal.lockToken
                    phase = [string]$journal.phase
                    failureType = $operationFailure.GetType().FullName
                    recovery = 'forward-only'
                })
                $journal = Add-ProductionPostgreSqlCutoverTransition -Journal $journal `
                    -Next 'FORWARD_RECOVERY_REQUIRED' -EvidenceDigest $failureDigest `
                    -Now (& $ClockAction) -AuthorityPublished $true
            } else {
                $phaseOrder = @('PLANNED','WRITERS_STOPPED','MONGO_ARCHIVED',
                    'POSTGRESQL_FINALIZED','POSTGRESQL_RECONCILED','POSTGRESQL_BACKED_UP',
                    'CANDIDATE_VERIFIED')
                $failureDigest = Get-ProductionPostgreSqlCutoverCanonicalMapHash -Values ([ordered]@{
                    release = [string]$journal.release
                    lockToken = [string]$journal.lockToken
                    phase = [string]$journal.phase
                    failureType = $operationFailure.GetType().FullName
                    recovery = 'no-writer-effect'
                })
                if ([array]::IndexOf($phaseOrder, [string]$journal.phase) -ge 1) {
                    $recovery = & $Actions.RestorePreAuthority $journal
                    $failureDigest = Get-ProductionPostgreSqlCutoverEvidenceDigest $recovery
                }
                $journal = Add-ProductionPostgreSqlCutoverTransition -Journal $journal `
                    -Next 'ROLLED_BACK' -EvidenceDigest $failureDigest -Now (& $ClockAction)
            }
            & $WriteJournalAction $journal
        } catch {
            throw [AggregateException]::new(
                'PostgreSQL cutover and durable failure handling both failed.',
                [Exception[]]@($operationFailure, $_.Exception))
        }
        throw $operationFailure
    }
}

Export-ModuleMember -Function Invoke-ProductionPostgreSqlShadow,
    Invoke-ProductionPostgreSqlReconcile,Invoke-ProductionPostgreSqlCutover
