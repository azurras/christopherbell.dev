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

Export-ModuleMember -Function Invoke-ProductionPostgreSqlShadow,
    Invoke-ProductionPostgreSqlReconcile
