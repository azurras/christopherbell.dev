Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Production.Common.psm1') -Global -Force

$script:ExpectedVersion = '18.4'
$script:ExpectedServiceName = 'postgresql-x64-18'
$script:RoleNames = [ordered]@{
    Migrator = 'christopherbell_migrator'
    App = 'christopherbell_app'
    Bridge = 'christopherbell_bridge'
    Viewer = 'christopherbell_viewer'
    Backup = 'christopherbell_backup'
    Test = 'christopherbell_test'
}
$script:DefaultProcessAction = {
    param($FilePath,$Arguments,$Environment)
    Invoke-CheckedProcess -FilePath $FilePath -ArgumentList @($Arguments) `
        -Environment (@{} + $Environment)
}

function Assert-ProductionPostgreSqlConfig {
    [CmdletBinding()]
    param([Parameter(Mandatory)][pscustomobject]$Config, [switch]$RequireInstalled)

    foreach ($name in 'programDataRoot','javaExe','postgresqlVersion','postgresqlBinPath',
        'postgresqlDataPath','postgresqlServiceName','postgresqlBackupRoot','pgAdminExe') {
        if (-not $Config.PSObject.Properties[$name] -or
            [string]::IsNullOrWhiteSpace([string]$Config.$name)) {
            throw "Missing PostgreSQL configuration value: $name"
        }
    }
    if ([string]$Config.postgresqlVersion -cne $script:ExpectedVersion) {
        throw "PostgreSQL version must be exactly $($script:ExpectedVersion)."
    }
    if ([string]$Config.postgresqlServiceName -cne $script:ExpectedServiceName) {
        throw "PostgreSQL service must be exactly $($script:ExpectedServiceName)."
    }
    foreach ($name in 'programDataRoot','javaExe','postgresqlBinPath','postgresqlDataPath',
        'postgresqlBackupRoot','pgAdminExe') {
        if (-not (Test-ProductionAbsolutePath -Path ([string]$Config.$name))) {
            throw "$name must be an absolute path."
        }
    }
    if ($RequireInstalled) {
        foreach ($name in 'psql.exe','pg_dump.exe','pg_restore.exe','postgres.exe') {
            if (-not (Test-Path -LiteralPath (Join-Path $Config.postgresqlBinPath $name) -PathType Leaf)) {
                throw "The configured PostgreSQL 18.4 runtime is incomplete: $name"
            }
        }
    }
    return $Config
}

function Invoke-WithProductionPostgreSqlLock {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    $lock = Enter-DeploymentLock -LockPath (
        Join-Path $Config.programDataRoot 'locks\deploy.lock')
    try { return & $Action }
    finally { $lock.Dispose() }
}

function Set-ProductionPostgreSqlNetworkConfigCore {
    param([Parameter(Mandatory)][pscustomobject]$Config)
    if (-not (Test-Path -LiteralPath $Config.postgresqlDataPath -PathType Container)) {
        throw 'The configured PostgreSQL data directory is missing.'
    }
    Assert-ProductionPathNotReparse -Path $Config.postgresqlDataPath | Out-Null
    $postgresqlConf = Join-Path $Config.postgresqlDataPath 'postgresql.conf'
    $hba = Join-Path $Config.postgresqlDataPath 'pg_hba.conf'
    if (-not (Test-Path -LiteralPath $postgresqlConf -PathType Leaf) -or
        -not (Test-Path -LiteralPath $hba -PathType Leaf)) {
        throw 'The PostgreSQL data directory does not contain its required configuration files.'
    }
    $confDirectory = Join-Path $Config.postgresqlDataPath 'conf.d'
    if (-not (Test-Path -LiteralPath $confDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $confDirectory | Out-Null
    }
    $base = Get-Content -LiteralPath $postgresqlConf -Raw
    if ($base -notmatch "(?m)^\s*include_dir\s*=\s*'conf\.d'\s*$") {
        Add-Content -LiteralPath $postgresqlConf -Value "`ninclude_dir = 'conf.d'"
    }
    @"
# Managed by christopherbell.dev production operations.
listen_addresses = 'localhost'
port = 5432
password_encryption = 'scram-sha-256'
"@ | Set-Content -LiteralPath (
        Join-Path $confDirectory 'christopherbell-production.conf') -Encoding ascii
    @'
# Managed by christopherbell.dev production operations.
local   all   postgres                                  scram-sha-256
host    all   all        127.0.0.1/32                   scram-sha-256
host    all   all        ::1/128                        scram-sha-256
'@ | Set-Content -LiteralPath $hba -Encoding ascii
}

function Set-ProductionPostgreSqlNetworkConfig {
    [CmdletBinding(SupportsShouldProcess)]
    param([Parameter(Mandatory)][pscustomobject]$Config)
    Assert-ProductionPostgreSqlConfig -Config $Config | Out-Null
    if (-not $PSCmdlet.ShouldProcess($Config.postgresqlDataPath,
        'enforce loopback-only SCRAM PostgreSQL configuration')) { return }
    Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
        Set-ProductionPostgreSqlNetworkConfigCore -Config $Config
    }
}

function Assert-ProductionPostgreSqlRoleSecrets {
    [CmdletBinding()]
    param([Parameter(Mandatory)][Collections.IDictionary]$RoleSecrets)

    foreach ($name in $script:RoleNames.Keys) {
        if (-not $RoleSecrets.Contains($name) -or
            [string]::IsNullOrWhiteSpace([string]$RoleSecrets[$name]) -or
            [string]$RoleSecrets[$name] -match '(?i)replace|placeholder' -or
            ([string]$RoleSecrets[$name]).Length -lt 16) {
            throw "The protected PostgreSQL $name role secret is missing or invalid."
        }
    }
}

function Read-ProductionPostgreSqlSecrets {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'The protected PostgreSQL role secret file is missing.'
    }
    Assert-ProductionPathNotReparse -Path $Path | Out-Null
    Assert-ProtectedProductionPath -Path $Path | Out-Null
    $allowed = @('POSTGRES_ADMIN_PASSWORD','CB_MIGRATOR_PASSWORD','CB_APP_PASSWORD',
        'CB_BRIDGE_PASSWORD','CB_VIEWER_PASSWORD','CB_BACKUP_PASSWORD','CB_TEST_PASSWORD')
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z_]+)=(.+)$' -or $allowed -notcontains $Matches[1]) {
            throw 'The protected PostgreSQL role secret file is malformed.'
        }
        if ($values.ContainsKey($Matches[1])) {
            throw 'The protected PostgreSQL role secret file contains a duplicate key.'
        }
        $values[$Matches[1]] = $Matches[2]
    }
    foreach ($key in $allowed) {
        if (-not $values.ContainsKey($key) -or [string]$values[$key] -match '(?i)replace|placeholder' -or
            ([string]$values[$key]).Length -lt 16) {
            throw 'The protected PostgreSQL role secret file is incomplete.'
        }
    }
    return [pscustomobject]@{
        Administrator = [string]$values.POSTGRES_ADMIN_PASSWORD
        Roles = [ordered]@{
            Migrator = [string]$values.CB_MIGRATOR_PASSWORD
            App = [string]$values.CB_APP_PASSWORD
            Bridge = [string]$values.CB_BRIDGE_PASSWORD
            Viewer = [string]$values.CB_VIEWER_PASSWORD
            Backup = [string]$values.CB_BACKUP_PASSWORD
            Test = [string]$values.CB_TEST_PASSWORD
        }
    }
}

function Get-ProductionPostgreSqlBootstrapSql {
    [CmdletBinding()]
    param([Parameter(Mandatory)][Collections.IDictionary]$RoleSecrets)

    Assert-ProductionPostgreSqlRoleSecrets -RoleSecrets $RoleSecrets
    @'
\set ON_ERROR_STOP on
\getenv migrator_password CB_MIGRATOR_PASSWORD
\getenv app_password CB_APP_PASSWORD
\getenv bridge_password CB_BRIDGE_PASSWORD
\getenv viewer_password CB_VIEWER_PASSWORD
\getenv backup_password CB_BACKUP_PASSWORD
\getenv test_password CB_TEST_PASSWORD

SELECT 'CREATE ROLE christopherbell_owner NOLOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_owner') \gexec
SELECT format('CREATE ROLE christopherbell_migrator LOGIN PASSWORD %L', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_migrator') \gexec
SELECT format('CREATE ROLE christopherbell_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_app') \gexec
SELECT format('CREATE ROLE christopherbell_bridge LOGIN PASSWORD %L', :'bridge_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_bridge') \gexec
SELECT format('CREATE ROLE christopherbell_viewer LOGIN PASSWORD %L', :'viewer_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_viewer') \gexec
SELECT format('CREATE ROLE christopherbell_backup LOGIN PASSWORD %L', :'backup_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_backup') \gexec
SELECT format('CREATE ROLE christopherbell_test LOGIN PASSWORD %L', :'test_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'christopherbell_test') \gexec

ALTER ROLE christopherbell_migrator LOGIN PASSWORD :'migrator_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE christopherbell_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE christopherbell_bridge LOGIN PASSWORD :'bridge_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE christopherbell_viewer LOGIN PASSWORD :'viewer_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE christopherbell_backup LOGIN PASSWORD :'backup_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE christopherbell_test LOGIN PASSWORD :'test_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE christopherbell_viewer SET default_transaction_read_only = on;

GRANT christopherbell_owner TO christopherbell_migrator;
GRANT pg_read_all_data TO christopherbell_viewer;
GRANT pg_read_all_data TO christopherbell_backup;
SELECT 'CREATE DATABASE christopherbell OWNER christopherbell_owner'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'christopherbell') \gexec
SELECT 'CREATE DATABASE test OWNER christopherbell_owner'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'test') \gexec

\connect christopherbell
REVOKE CONNECT ON DATABASE christopherbell FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM christopherbell_app;
GRANT CONNECT ON DATABASE christopherbell TO christopherbell_migrator, christopherbell_app,
    christopherbell_bridge, christopherbell_viewer, christopherbell_backup;
GRANT USAGE ON SCHEMA public TO christopherbell_app, christopherbell_bridge, christopherbell_viewer;
ALTER DEFAULT PRIVILEGES FOR ROLE christopherbell_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO christopherbell_app, christopherbell_bridge;
ALTER DEFAULT PRIVILEGES FOR ROLE christopherbell_owner IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO christopherbell_app, christopherbell_bridge;
ALTER DEFAULT PRIVILEGES FOR ROLE christopherbell_owner IN SCHEMA public
    GRANT SELECT ON TABLES TO christopherbell_viewer, christopherbell_backup;

\connect test
REVOKE CONNECT ON DATABASE test FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE test TO christopherbell_test;
GRANT USAGE, CREATE ON SCHEMA public TO christopherbell_test;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO christopherbell_test;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO christopherbell_test;
ALTER DEFAULT PRIVILEGES FOR ROLE christopherbell_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO christopherbell_test;
ALTER DEFAULT PRIVILEGES FOR ROLE christopherbell_owner IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO christopherbell_test;
'@
}

function Get-ProductionPostgreSqlProcessEnvironment {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$RoleSecrets,
        [Parameter(Mandatory)][string]$AdministratorPassword
    )
    Assert-ProductionPostgreSqlRoleSecrets -RoleSecrets $RoleSecrets
    if ([string]::IsNullOrWhiteSpace($AdministratorPassword)) {
        throw 'The protected PostgreSQL administrator secret is missing.'
    }
    return @{
        PGPASSWORD = $AdministratorPassword
        CB_MIGRATOR_PASSWORD = [string]$RoleSecrets.Migrator
        CB_APP_PASSWORD = [string]$RoleSecrets.App
        CB_BRIDGE_PASSWORD = [string]$RoleSecrets.Bridge
        CB_VIEWER_PASSWORD = [string]$RoleSecrets.Viewer
        CB_BACKUP_PASSWORD = [string]$RoleSecrets.Backup
        CB_TEST_PASSWORD = [string]$RoleSecrets.Test
    }
}

function Invoke-ProductionPostgreSqlSchemaMigrationCore {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$MigratorPassword,
        [Parameter(Mandatory)][scriptblock]$ProcessAction
    )
    if ([string]::IsNullOrWhiteSpace($MigratorPassword)) {
        throw 'The protected PostgreSQL migrator secret is missing.'
    }
    if (-not (Test-Path -LiteralPath $Config.javaExe -PathType Leaf)) {
        throw 'The configured Java runtime is missing.'
    }
    $release = Get-JunctionTarget (Join-Path $Config.programDataRoot 'current')
    if (-not $release) { throw 'The active production release is missing.' }
    $release = Assert-ReleasePath -Config $Config -Path $release
    $jar = Join-Path $release 'app.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw 'The active production application archive is missing.'
    }
    $environment = @{
        SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
        SPRING_DATASOURCE_USERNAME = 'christopherbell_migrator'
        SPRING_DATASOURCE_PASSWORD = $MigratorPassword
    }
    & $ProcessAction $Config.javaExe @(
        '-Dloader.main=dev.christopherbell.configuration.persistence.migration.ProductionPostgresqlSchemaMigrator',
        '-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher') $environment | Out-Null
}

function Install-ProductionPostgreSql {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$SignatureAction = {
            param($Path) Get-AuthenticodeSignature -LiteralPath $Path
        }
    )
    Assert-ProductionPostgreSqlConfig -Config $Config | Out-Null
    $postgres = Join-Path $Config.postgresqlBinPath 'postgres.exe'
    $alreadyInstalled = Test-Path -LiteralPath $postgres -PathType Leaf
    $operation = if ($alreadyInstalled) { 'validate exact signed installation' } else { 'install' }
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL 18.4 native Windows runtime', $operation)) {
        return
    }
    if (-not $alreadyInstalled) {
        Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
            & $ProcessAction 'winget.exe' @('install','--id','PostgreSQL.PostgreSQL.18',
                '--version','18.4-1','--exact','--interactive','--accept-package-agreements',
                '--accept-source-agreements') @{} | Out-Null
        }
    }
    Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
    $signature = & $SignatureAction $postgres
    $subject = if ($signature.SignerCertificate) {
        [string]$signature.SignerCertificate.Subject
    } else { '' }
    if ([string]$signature.Status -cne 'Valid' -or
        $subject -notmatch '(?i)(EnterpriseDB Corporation|PostgreSQL Global Development Group)') {
        throw 'The PostgreSQL runtime must have a valid trusted publisher signature.'
    }
    $versionOutput = [string](& $ProcessAction $postgres @('--version') @{})
    if ($versionOutput -notmatch '(?i)PostgreSQL\)\s+18\.4(?:\s|$)') {
        throw 'The installed PostgreSQL runtime is not exact version 18.4.'
    }
    return [pscustomobject][ordered]@{ Installed = $true; Version = '18.4'; Path = $postgres }
}

function Initialize-ProductionPostgreSql {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [Collections.IDictionary]$RoleSecrets,
        [string]$AdministratorPassword,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$ServiceAction = {
            param($Name)
            Restart-Service -Name $Name -Force -ErrorAction Stop
            $service = Get-Service -Name $Name -ErrorAction Stop
            if ($service.Status -ne 'Running') {
                throw 'The PostgreSQL service did not return to Running after configuration.'
            }
        }
    )
    if (-not $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL role and database authority', 'bootstrap')) { return }
    if (-not $RoleSecrets -or [string]::IsNullOrWhiteSpace($AdministratorPassword)) {
        $protected = Read-ProductionPostgreSqlSecrets `
            -Path (Join-Path $Config.programDataRoot 'config\postgresql.env')
        if (-not $RoleSecrets) { $RoleSecrets = $protected.Roles }
        if ([string]::IsNullOrWhiteSpace($AdministratorPassword)) {
            $AdministratorPassword = $protected.Administrator
        }
    }
    Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
        Set-ProductionPostgreSqlNetworkConfigCore -Config $Config
        & $ServiceAction ([string]$Config.postgresqlServiceName)
        $environment = Get-ProductionPostgreSqlProcessEnvironment `
            -RoleSecrets $RoleSecrets -AdministratorPassword $AdministratorPassword
        $stateRoot = Join-Path $Config.programDataRoot 'state'
        if (-not (Test-Path -LiteralPath $stateRoot -PathType Container)) {
            New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
        }
        $sqlPath = Join-Path $stateRoot 'postgresql-bootstrap.sql'
        try {
            Get-ProductionPostgreSqlBootstrapSql -RoleSecrets $RoleSecrets |
                Set-Content -LiteralPath $sqlPath -Encoding utf8
            & $ProcessAction (Join-Path $Config.postgresqlBinPath 'psql.exe') @(
                '--host=127.0.0.1','--port=5432','--username=postgres','--dbname=postgres',
                '--no-password',"--file=$sqlPath") $environment | Out-Null
            Invoke-ProductionPostgreSqlSchemaMigrationCore -Config $Config `
                -MigratorPassword ([string]$RoleSecrets.Migrator) `
                -ProcessAction $ProcessAction
        } finally {
            if (Test-Path -LiteralPath $sqlPath -PathType Leaf) {
                Remove-Item -LiteralPath $sqlPath -Force
            }
        }
    }
}

function New-ProductionPgAdminServerRegistration {
    [CmdletBinding()]
    param([Parameter(Mandatory)][pscustomobject]$Config)
    Assert-ProductionPostgreSqlConfig -Config $Config | Out-Null
    [pscustomobject][ordered]@{
        Servers = [pscustomobject][ordered]@{
            '1' = [pscustomobject][ordered]@{
                Name = 'christopherbell-test'; Group = 'ChristopherBell'; Host = '127.0.0.1'
                Port = 5432; MaintenanceDB = 'test'; Username = 'christopherbell_test'
                SSLMode = 'prefer'
            }
            '2' = [pscustomobject][ordered]@{
                Name = 'christopherbell-production-viewer'; Group = 'ChristopherBell'
                Host = '127.0.0.1'; Port = 5432; MaintenanceDB = 'christopherbell'
                Username = 'christopherbell_viewer'; SSLMode = 'prefer'
            }
        }
    }
}

function Install-ProductionPgAdmin {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$ProtectRegistrationAction = {
            param($Path) Protect-ProductionPath -Path $Path
        }
    )
    if (-not $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionPostgreSqlConfig -Config $Config | Out-Null
    if (-not $PSCmdlet.ShouldProcess('pgAdmin 4 Desktop and unprivileged server registrations', 'install')) {
        return
    }
    if (-not (Test-Path -LiteralPath $Config.pgAdminExe -PathType Leaf)) {
        & $ProcessAction 'winget.exe' @('install','--id','PostgreSQL.pgAdmin',
            '--exact','--interactive','--accept-package-agreements','--accept-source-agreements') @{} |
            Out-Null
    }
    if (-not (Test-Path -LiteralPath $Config.pgAdminExe -PathType Leaf)) {
        throw 'pgAdmin 4 Desktop was not installed at the configured path.'
    }
    return Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
        $configRoot = Join-Path $Config.programDataRoot 'config'
        if (-not (Test-Path -LiteralPath $configRoot -PathType Container)) {
            New-Item -ItemType Directory -Path $configRoot -Force | Out-Null
        }
        $registrationPath = Join-Path $configRoot 'pgadmin-servers.json'
        New-ProductionPgAdminServerRegistration -Config $Config |
            ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $registrationPath -Encoding utf8
        & $ProtectRegistrationAction $registrationPath
        $runtime = Split-Path -Parent $Config.pgAdminExe
        $root = Split-Path -Parent $runtime
        $python = Join-Path $runtime 'python.exe'
        $setup = Join-Path $root 'web\setup.py'
        if (-not (Test-Path -LiteralPath $python -PathType Leaf) -or
            -not (Test-Path -LiteralPath $setup -PathType Leaf)) {
            throw 'The pgAdmin setup runtime is incomplete.'
        }
        if ([string]::IsNullOrWhiteSpace([string]$Config.smokeAccountEmail)) {
            throw 'pgAdmin registration requires the configured production operator email.'
        }
        & $ProcessAction $python @($setup,'load-servers',$registrationPath,'--replace',
            '--user',[string]$Config.smokeAccountEmail) @{} | Out-Null
        return $registrationPath
    }
}

function Invoke-ProductionPostgreSqlRestoreCore {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$Archive,
        [Parameter(Mandatory)][string]$ActualDigest,
        [Parameter(Mandatory)][string]$BackupPassword,
        [Parameter(Mandatory)][string]$AdministratorPassword,
        [Parameter(Mandatory)][scriptblock]$ProcessAction,
        [Parameter(Mandatory)][string]$Database
    )
    $backupEnvironment = @{ PGPASSWORD = $BackupPassword }
    $administratorEnvironment = @{ PGPASSWORD = $AdministratorPassword }
    $psql = Join-Path $Config.postgresqlBinPath 'psql.exe'
    $restore = Join-Path $Config.postgresqlBinPath 'pg_restore.exe'
    $created = $false
    try {
        & $ProcessAction $psql @('--host=127.0.0.1','--port=5432',
            '--username=postgres','--dbname=postgres','--no-password',
            "--command=CREATE DATABASE `"$Database`" OWNER christopherbell_backup TEMPLATE template0") `
            $administratorEnvironment | Out-Null
        $created = $true
        & $ProcessAction $restore @('--host=127.0.0.1','--port=5432',
            '--username=christopherbell_backup',"--dbname=$Database",'--no-owner',
            '--no-privileges','--exit-on-error',$Archive) $backupEnvironment | Out-Null
    } finally {
        if ($created) {
            & $ProcessAction $psql @('--host=127.0.0.1','--port=5432',
                '--username=postgres','--dbname=postgres','--no-password',
                "--command=DROP DATABASE IF EXISTS `"$Database`" WITH (FORCE)") `
                $administratorEnvironment |
                Out-Null
        }
    }
    return [pscustomobject][ordered]@{
        Database = $Database; Sha256 = $ActualDigest; Restored = $true
    }
}

function Assert-ProductionPostgreSqlBackupPath {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$Path
    )
    if (-not (Test-ProductionAbsolutePath -Path $Path)) {
        throw 'The PostgreSQL backup archive must be an absolute path below the backup root.'
    }
    $root = [IO.Path]::GetFullPath([string]$Config.postgresqlBackupRoot).
        TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
    $candidate = [IO.Path]::GetFullPath($Path)
    if (-not $candidate.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The PostgreSQL backup archive must remain below the configured backup root.'
    }
    return $candidate
}

function Test-ProductionPostgreSqlRestore {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [string]$Archive,
        [string]$ExpectedDigest,
        [string]$BackupPassword,
        [string]$AdministratorPassword,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [datetime]$UtcNow = [datetime]::UtcNow
    )
    if (-not $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
    if ([string]::IsNullOrWhiteSpace($Archive) -or
        [string]::IsNullOrWhiteSpace($ExpectedDigest)) {
        $evidenceFile = Get-ChildItem -LiteralPath $Config.postgresqlBackupRoot `
            -Filter 'christopherbell-*.dump.sha256.json' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if (-not $evidenceFile) { throw 'No PostgreSQL backup evidence is available for restore check.' }
        try { $evidence = Get-Content -LiteralPath $evidenceFile.FullName -Raw | ConvertFrom-Json }
        catch { throw 'The PostgreSQL backup evidence is invalid.' }
        $Archive = [string]$evidence.archive
        $ExpectedDigest = [string]$evidence.sha256
    }
    if ([string]::IsNullOrWhiteSpace($BackupPassword) -or
        [string]::IsNullOrWhiteSpace($AdministratorPassword)) {
        $protected = Read-ProductionPostgreSqlSecrets `
            -Path (Join-Path $Config.programDataRoot 'config\postgresql.env')
        if ([string]::IsNullOrWhiteSpace($BackupPassword)) {
            $BackupPassword = [string]$protected.Roles.Backup
        }
        if ([string]::IsNullOrWhiteSpace($AdministratorPassword)) {
            $AdministratorPassword = [string]$protected.Administrator
        }
    }
    $Archive = Assert-ProductionPostgreSqlBackupPath -Config $Config -Path $Archive
    if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) {
        throw 'The PostgreSQL backup archive is missing.'
    }
    Assert-ProductionPathNotReparse -Path $Archive | Out-Null
    $actualDigest = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash
    if ($actualDigest -cne $ExpectedDigest) { throw 'The PostgreSQL backup checksum does not match.' }
    $database = 'cbrestore_' + $UtcNow.ToUniversalTime().ToString('yyyyMMddHHmmss')
    if ($database -notmatch '^cbrestore_[0-9]{14}$') { throw 'Invalid restore-check database identity.' }
    if (-not $PSCmdlet.ShouldProcess($database, 'create, restore, verify, and drop isolated database')) {
        return
    }
    return Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
        Invoke-ProductionPostgreSqlRestoreCore -Config $Config -Archive $Archive `
            -ActualDigest $actualDigest -BackupPassword $BackupPassword `
            -AdministratorPassword $AdministratorPassword -ProcessAction $ProcessAction `
            -Database $database
    }
}

function New-ProductionPostgreSqlBackup {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [pscustomobject]$Config,
        [string]$BackupPassword,
        [string]$AdministratorPassword,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$ProtectBackupAction = {
            param($Path) Protect-ProductionPath -Path $Path
        },
        [datetime]$UtcNow = [datetime]::UtcNow
    )
    if (-not $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
    if ([string]::IsNullOrWhiteSpace($BackupPassword) -or
        [string]::IsNullOrWhiteSpace($AdministratorPassword)) {
        $protected = Read-ProductionPostgreSqlSecrets `
            -Path (Join-Path $Config.programDataRoot 'config\postgresql.env')
        if ([string]::IsNullOrWhiteSpace($BackupPassword)) {
            $BackupPassword = [string]$protected.Roles.Backup
        }
        if ([string]::IsNullOrWhiteSpace($AdministratorPassword)) {
            $AdministratorPassword = [string]$protected.Administrator
        }
    }
    $stamp = $UtcNow.ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $archive = Join-Path $Config.postgresqlBackupRoot "christopherbell-$stamp.dump"
    if (-not $PSCmdlet.ShouldProcess($archive, 'create checksummed PostgreSQL backup and dry restore')) {
        return
    }
    return Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
        if (-not (Test-Path -LiteralPath $Config.postgresqlBackupRoot -PathType Container)) {
            New-Item -ItemType Directory -Path $Config.postgresqlBackupRoot -Force | Out-Null
        }
        & $ProtectBackupAction ([string]$Config.postgresqlBackupRoot)
        & $ProcessAction (Join-Path $Config.postgresqlBinPath 'pg_dump.exe') @(
            '--host=127.0.0.1','--port=5432','--username=christopherbell_backup',
            '--dbname=christopherbell','--no-password','--format=custom','--no-owner',
            '--no-privileges',"--file=$archive") @{ PGPASSWORD = $BackupPassword } | Out-Null
        if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or
            (Get-Item -LiteralPath $archive).Length -eq 0) {
            throw 'PostgreSQL backup did not create a non-empty custom archive.'
        }
        $digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        $database = 'cbrestore_' + $UtcNow.ToUniversalTime().ToString('yyyyMMddHHmmss')
        $restore = Invoke-ProductionPostgreSqlRestoreCore -Config $Config `
            -Archive $archive -ActualDigest $digest -BackupPassword $BackupPassword `
            -AdministratorPassword $AdministratorPassword -ProcessAction $ProcessAction `
            -Database $database
        $evidence = "$archive.sha256.json"
        [pscustomobject][ordered]@{
            database = 'christopherbell'; archive = $archive; sha256 = $digest
            createdAtUtc = $UtcNow.ToUniversalTime().ToString('o')
            restoreDatabase = $restore.Database; restoreVerified = $restore.Restored
        } | ConvertTo-Json | Set-Content -LiteralPath $evidence -Encoding utf8
        return [pscustomobject][ordered]@{
            Archive = $archive; Sha256 = $digest; Evidence = $evidence; Restore = $restore
        }
    }
}

function Get-ProductionPostgreSqlStatus {
    [CmdletBinding()]
    param(
        [pscustomobject]$Config,
        [string]$AppPassword,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction
    )
    if (-not $Config) { $Config = Read-ProductionConfig }
    Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
    if ([string]::IsNullOrWhiteSpace($AppPassword)) {
        $protected = Read-ProductionPostgreSqlSecrets `
            -Path (Join-Path $Config.programDataRoot 'config\postgresql.env')
        $AppPassword = [string]$protected.Roles.App
    }
    $query = @"
select json_build_object(
  'database', current_database(),
  'role', current_user,
  'serverVersion', current_setting('server_version'),
  'listenAddresses', current_setting('listen_addresses'),
  'passwordEncryption', current_setting('password_encryption'),
  'canCreateSchema', has_schema_privilege(current_user, 'public', 'CREATE'),
  'viewerReadOnly', coalesce((select 'default_transaction_read_only=on'=any(rolconfig)
    from pg_roles where rolname='christopherbell_viewer'), false));
"@
    $raw = & $ProcessAction (Join-Path $Config.postgresqlBinPath 'psql.exe') @(
        '--host=127.0.0.1','--port=5432','--username=christopherbell_app',
        '--dbname=christopherbell','--no-password','--tuples-only','--no-align',
        "--command=$query") @{ PGPASSWORD = $AppPassword }
    try { $observed = ([string]$raw).Trim() | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'PostgreSQL status probe returned invalid redacted output.' }
    $version = [string]$observed.serverVersion
    if (-not $version.StartsWith('18.4', [StringComparison]::Ordinal)) {
        throw 'PostgreSQL status probe observed an unsupported server version.'
    }
    if ($observed.canCreateSchema -isnot [bool] -or $observed.viewerReadOnly -isnot [bool]) {
        throw 'PostgreSQL status probe observed an unsafe production identity or capability.'
    }
    if ([string]$observed.database -cne 'christopherbell' -or
        [string]$observed.role -cne 'christopherbell_app' -or
        [string]$observed.listenAddresses -cne 'localhost' -or
        [string]$observed.passwordEncryption -cne 'scram-sha-256' -or
        [bool]$observed.canCreateSchema -or -not [bool]$observed.viewerReadOnly) {
        throw 'PostgreSQL status probe observed an unsafe production identity or capability.'
    }
    $service = Get-Service -Name $Config.postgresqlServiceName -ErrorAction SilentlyContinue
    return [pscustomobject][ordered]@{
        Service = if ($service) { [string]$service.Status } else { 'NotInstalled' }
        Database = [string]$observed.database
        Role = [string]$observed.role
        ServerVersion = $version
        ListenAddresses = [string]$observed.listenAddresses
        PasswordEncryption = [string]$observed.passwordEncryption
        AppCanCreateSchema = [bool]$observed.canCreateSchema
        ViewerReadOnly = [bool]$observed.viewerReadOnly
    }
}

Export-ModuleMember -Function Assert-ProductionPostgreSqlConfig,
    Read-ProductionPostgreSqlSecrets,Get-ProductionPostgreSqlBootstrapSql,
    Install-ProductionPostgreSql,Set-ProductionPostgreSqlNetworkConfig,
    Initialize-ProductionPostgreSql,
    New-ProductionPgAdminServerRegistration,Install-ProductionPgAdmin,
    Test-ProductionPostgreSqlRestore,New-ProductionPostgreSqlBackup,
    Get-ProductionPostgreSqlStatus
