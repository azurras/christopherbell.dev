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

function Read-ProductionPostgreSqlSecretValuesCore {
    param([Parameter(Mandatory)][string]$Path)

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
    if (@($values.Values | Select-Object -Unique).Count -ne $allowed.Count) {
        throw 'The protected PostgreSQL role secrets must be distinct.'
    }
    return $values
}

function ConvertTo-ProductionPostgreSqlSecrets {
    param([Parameter(Mandatory)][Collections.IDictionary]$Values)

    return [pscustomobject]@{
        Administrator = [string]$Values.POSTGRES_ADMIN_PASSWORD
        Roles = [ordered]@{
            Migrator = [string]$Values.CB_MIGRATOR_PASSWORD
            App = [string]$Values.CB_APP_PASSWORD
            Bridge = [string]$Values.CB_BRIDGE_PASSWORD
            Viewer = [string]$Values.CB_VIEWER_PASSWORD
            Backup = [string]$Values.CB_BACKUP_PASSWORD
            Test = [string]$Values.CB_TEST_PASSWORD
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
    $values = Read-ProductionPostgreSqlSecretValuesCore -Path $Path
    return ConvertTo-ProductionPostgreSqlSecrets -Values $values
}

function New-ProductionPostgreSqlRandomSecret {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function New-ProductionPostgreSqlSecretFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [scriptblock]$ProtectDirectoryAction = {
            param($Directory) Protect-ProductionTree -Path $Directory
        },
        [scriptblock]$ProtectPathAction = {
            param($SecretPath) Protect-ProductionPath -Path $SecretPath
        },
        [scriptblock]$AssertPathAction = {
            param($SecretPath) Assert-ProtectedProductionPath -Path $SecretPath | Out-Null
        }
    )

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw 'The protected PostgreSQL configuration directory is missing.'
    }
    & $ProtectDirectoryAction $directory
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        Assert-ProductionPathNotReparse -Path $Path | Out-Null
        & $ProtectPathAction $Path
        & $AssertPathAction $Path
        $lines = @(Get-Content -LiteralPath $Path | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_) -and -not $_.TrimStart().StartsWith('#')
        })
        $placeholders = @($lines | Where-Object { $_ -match '=(?i:replace-with|placeholder)' })
        if ($placeholders.Count -eq 0) {
            Read-ProductionPostgreSqlSecretValuesCore -Path $Path | Out-Null
            return [pscustomobject][ordered]@{ Created=$false; SecretCount=7 }
        }
        if ($placeholders.Count -ne 7 -or $lines.Count -ne 7) {
            throw 'The protected PostgreSQL role secret file is partially configured.'
        }
    }

    $keys = @('POSTGRES_ADMIN_PASSWORD','CB_MIGRATOR_PASSWORD','CB_APP_PASSWORD',
        'CB_BRIDGE_PASSWORD','CB_VIEWER_PASSWORD','CB_BACKUP_PASSWORD','CB_TEST_PASSWORD')
    $generated = foreach ($key in $keys) { "$key=$(New-ProductionPostgreSqlRandomSecret)" }
    $temporary = Join-Path $directory ('.postgresql.env.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Dispose()
        & $ProtectPathAction $temporary
        & $AssertPathAction $temporary
        [IO.File]::WriteAllText($temporary, ($generated -join "`n") + "`n",
            [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $Path -Force
        & $ProtectPathAction $Path
        & $AssertPathAction $Path
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    return [pscustomobject][ordered]@{ Created=$true; SecretCount=7 }
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

function Invoke-ProductionPostgreSqlPreparationCore {
    param(
        [Parameter(Mandatory)][string]$Root,
        [scriptblock]$ConfigurationAction = {
            param($PreparationRoot)
            Install-ConfigurationExamples $PreparationRoot
            Read-ProductionConfig (Join-Path $PreparationRoot 'config\deploy.json')
        },
        [scriptblock]$ProtectSecretsAction = {
            param($PreparationRoot) Protect-ProductionSecrets $PreparationRoot
        },
        [scriptblock]$SecretFileAction = {
            param($Path) New-ProductionPostgreSqlSecretFile -Path $Path
        }
    )

    $lock = Enter-DeploymentLock -LockPath (Join-Path $Root 'locks\deploy.lock')
    try {
        & $ProtectSecretsAction $Root
        $config = & $ConfigurationAction $Root
        & $ProtectSecretsAction $Root
        Assert-ProductionPostgreSqlConfig -Config $config | Out-Null
        $secretResult = & $SecretFileAction (Join-Path $Root 'config\postgresql.env')
        return [pscustomobject][ordered]@{
            Prepared = $true
            Configuration = 'Validated'
            Credentials = if ($secretResult.Created) { 'Created' } else { 'Preserved' }
            CredentialCount = [int]$secretResult.SecretCount
        }
    } finally {
        $lock.Dispose()
    }
}

function Initialize-ProductionPostgreSqlPreparation {
    [CmdletBinding(SupportsShouldProcess)]
    param()

    $root = 'C:\ProgramData\christopherbell.dev'
    if (-not $PSCmdlet.ShouldProcess($root,
        'merge PostgreSQL defaults and create protected credentials')) {
        return
    }
    Assert-Administrator
    return Invoke-ProductionPostgreSqlPreparationCore -Root $root
}

function Enter-ProductionPostgreSqlLegacyReplacement {
    $serviceName = 'postgresql-x64-16'
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if (-not $service) {
        return [pscustomobject][ordered]@{
            Exists=$false; WasRunning=$false; StartMode=$null
        }
    }
    $native = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction Stop
    $state = [pscustomobject][ordered]@{
        Exists=$true
        WasRunning=[string]$service.Status -ceq 'Running'
        StartMode=[string]$native.StartMode
    }
    if ($state.WasRunning) {
        $connections = @(Get-NetTCPConnection -LocalPort 5432 -State Established `
            -ErrorAction SilentlyContinue)
        if ($connections.Count -ne 0) {
            throw 'PostgreSQL 16 has active client connections and cannot be replaced.'
        }
        try {
            Set-Service -Name $serviceName -StartupType Disabled -ErrorAction Stop
            Stop-Service -Name $serviceName -Force -ErrorAction Stop
            (Get-Service -Name $serviceName -ErrorAction Stop).WaitForStatus(
                [ServiceProcess.ServiceControllerStatus]::Stopped,
                [timespan]::FromSeconds(30))
        } catch {
            $stopFailure = $_.Exception
            try {
                $startup = if ($state.StartMode -ceq 'Auto') { 'Automatic' } else {
                    [string]$state.StartMode
                }
                Set-Service -Name $serviceName -StartupType $startup -ErrorAction Stop
                $observed = Get-Service -Name $serviceName -ErrorAction Stop
                if ($state.WasRunning -and [string]$observed.Status -cne 'Running') {
                    Start-Service -Name $serviceName -ErrorAction Stop
                }
            } catch {
                throw [AggregateException]::new(
                    'PostgreSQL 16 stop and startup-mode restoration both failed.',
                    [Exception[]]@($stopFailure,$_.Exception))
            }
            throw $stopFailure
        }
    }
    return $state
}

function Complete-ProductionPostgreSqlLegacyReplacement {
    param($State)
    if ($State -and $State.Exists) {
        Set-Service -Name 'postgresql-x64-16' -StartupType Manual -ErrorAction Stop
    }
}

function Restore-ProductionPostgreSqlLegacyReplacement {
    param($State)
    if (-not $State -or -not $State.Exists) { return }
    $postgres18 = Get-Service -Name $script:ExpectedServiceName -ErrorAction SilentlyContinue
    if ($postgres18 -and [string]$postgres18.Status -ceq 'Running') {
        Stop-Service -Name $script:ExpectedServiceName -Force -ErrorAction SilentlyContinue
    }
    $startup = switch ([string]$State.StartMode) {
        'Auto' { 'Automatic' }
        'Disabled' { 'Disabled' }
        default { 'Manual' }
    }
    Set-Service -Name 'postgresql-x64-16' -StartupType $startup -ErrorAction Stop
    if ($State.WasRunning) {
        Start-Service -Name 'postgresql-x64-16' -ErrorAction Stop
    }
}

function Write-ProductionPostgreSqlInstallerOptionFile {
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [Parameter(Mandatory)][string]$AdministratorPassword,
        [Parameter(Mandatory)][scriptblock]$ProtectAction,
        [Parameter(Mandatory)][scriptblock]$AssertAction
    )
    if ($AdministratorPassword -cnotmatch '^[A-Za-z0-9_-]{16,128}$') {
        throw 'The PostgreSQL administrator password is not option-file-safe.'
    }
    $path = Join-Path $Config.programDataRoot 'config\.postgresql-18-install.options'
    if (Test-Path -LiteralPath $path) {
        throw 'A PostgreSQL installer option file already exists.'
    }
    $completed = $false
    try {
        $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Dispose()
        & $ProtectAction $path
        & $AssertAction $path
        $prefix = Split-Path -Parent ([string]$Config.postgresqlBinPath)
        $lines = @(
            'mode=unattended',
            'unattendedmodeui=none',
            "prefix=$prefix",
            "datadir=$($Config.postgresqlDataPath)",
            'serverport=5432',
            "servicename=$($Config.postgresqlServiceName)",
            'superaccount=postgres',
            "superpassword=$AdministratorPassword",
            "servicepassword=$AdministratorPassword")
        [IO.File]::WriteAllText($path, ($lines -join "`n") + "`n",
            [Text.UTF8Encoding]::new($false))
        $completed = $true
        return $path
    } finally {
        if (-not $completed -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

function Get-ProductionPostgreSqlPackageIdentity {
    $registryPaths = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*')
    $matches = @(Get-ItemProperty $registryPaths -ErrorAction SilentlyContinue |
        Where-Object {
            $displayName = $_.PSObject.Properties['DisplayName']
            $displayName -and ([string]$displayName.Value).Trim() -ceq 'PostgreSQL 18'
        })
    if ($matches.Count -ne 1) {
        throw 'Exactly one registered PostgreSQL 18 package is required.'
    }
    return $matches[0]
}

function Assert-ProductionPostgreSqlPackageIdentity {
    param(
        [Parameter(Mandatory)]$Identity,
        [Parameter(Mandatory)][pscustomobject]$Config
    )
    $expectedRoot = Split-Path -Parent ([string]$Config.postgresqlBinPath)
    if (([string]$Identity.DisplayName).Trim() -cne 'PostgreSQL 18' -or
        [string]$Identity.DisplayVersion -cne '18.4-1' -or
        [string]$Identity.Publisher -cne 'PostgreSQL Global Development Group' -or
        -not [string]::Equals(
            [IO.Path]::GetFullPath([string]$Identity.InstallLocation).TrimEnd('\'),
            [IO.Path]::GetFullPath($expectedRoot).TrimEnd('\'),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The registered PostgreSQL package identity is not exact version 18.4-1.'
    }
}

function Assert-ProductionPostgreSqlServiceIdentity {
    param(
        [Parameter(Mandatory)]$Identity,
        [Parameter(Mandatory)][pscustomobject]$Config
    )
    $pgCtl = Join-Path $Config.postgresqlBinPath 'pg_ctl.exe'
    $expectedPath = "`"$pgCtl`" runservice -N `"$($Config.postgresqlServiceName)`" " +
        "-D `"$($Config.postgresqlDataPath)`" -w"
    if ([string]$Identity.Name -cne $script:ExpectedServiceName -or
        -not [string]::Equals([string]$Identity.PathName,$expectedPath,
            [StringComparison]::OrdinalIgnoreCase) -or
        [string]$Identity.StartName -cne 'NT AUTHORITY\NetworkService') {
        throw 'The PostgreSQL 18 service identity or executable binding is invalid.'
    }
}

function Install-ProductionPostgreSql {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [Parameter(Mandatory)][pscustomobject]$Config,
        [string]$AdministratorPassword,
        [scriptblock]$ProcessAction = $script:DefaultProcessAction,
        [scriptblock]$PackageIdentityAction = {
            Get-ProductionPostgreSqlPackageIdentity
        },
        [scriptblock]$ServiceIdentityAction = {
            param($Name)
            Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction SilentlyContinue
        },
        [scriptblock]$ProtectOptionFileAction = {
            param($Path) Protect-ProductionPath -Path $Path
        },
        [scriptblock]$AssertOptionFileAction = {
            param($Path) Assert-ProtectedProductionPath -Path $Path | Out-Null
        },
        [scriptblock]$PrepareLegacyAction = { Enter-ProductionPostgreSqlLegacyReplacement },
        [scriptblock]$CommitLegacyAction = {
            param($State) Complete-ProductionPostgreSqlLegacyReplacement -State $State
        },
        [scriptblock]$RollbackLegacyAction = {
            param($State) Restore-ProductionPostgreSqlLegacyReplacement -State $State
        }
    )
    Assert-ProductionPostgreSqlConfig -Config $Config | Out-Null
    $postgres = Join-Path $Config.postgresqlBinPath 'postgres.exe'
    $serviceIdentity = & $ServiceIdentityAction $script:ExpectedServiceName
    $alreadyInstalled = (Test-Path -LiteralPath $postgres -PathType Leaf) -and
        $null -ne $serviceIdentity
    $operation = if ($alreadyInstalled) { 'validate exact registered installation' } else { 'install' }
    if (-not $PSCmdlet.ShouldProcess('PostgreSQL 18.4 native Windows runtime', $operation)) {
        return
    }
    return Invoke-WithProductionPostgreSqlLock -Config $Config -Action {
        $legacyState = $null
        $legacyPrepared = $false
        $optionPath = $null
        try {
            $legacyState = & $PrepareLegacyAction
            $legacyPrepared = $true
            if (-not $alreadyInstalled) {
            if ([string]::IsNullOrWhiteSpace($AdministratorPassword)) {
                $protected = Read-ProductionPostgreSqlSecrets -Path (
                    Join-Path $Config.programDataRoot 'config\postgresql.env')
                $AdministratorPassword = [string]$protected.Administrator
            }
            $optionPath = Write-ProductionPostgreSqlInstallerOptionFile `
                -Config $Config -AdministratorPassword $AdministratorPassword `
                -ProtectAction $ProtectOptionFileAction -AssertAction $AssertOptionFileAction
            $override = "--mode unattended --unattendedmodeui none --optionfile `"$optionPath`""
            & $ProcessAction 'winget.exe' @('install','--id','PostgreSQL.PostgreSQL.18',
                '--version','18.4-1','--exact','--silent','--accept-package-agreements',
                '--accept-source-agreements','--override',$override) @{} | Out-Null
            }
            Assert-ProductionPostgreSqlConfig -Config $Config -RequireInstalled | Out-Null
            Assert-ProductionPostgreSqlPackageIdentity -Identity (& $PackageIdentityAction) `
                -Config $Config
            $serviceIdentity = & $ServiceIdentityAction $script:ExpectedServiceName
            if ($null -eq $serviceIdentity) {
                throw 'The PostgreSQL 18 service registration is missing.'
            }
            Assert-ProductionPostgreSqlServiceIdentity -Identity $serviceIdentity -Config $Config
            $versionOutput = [string](& $ProcessAction $postgres @('--version') @{})
            if ($versionOutput -notmatch '(?i)PostgreSQL\)\s+18\.4(?:\s|$)') {
                throw 'The installed PostgreSQL runtime is not exact version 18.4.'
            }
            & $CommitLegacyAction $legacyState
            return [pscustomobject][ordered]@{
                Installed=$true; Version='18.4'; Path=$postgres
            }
        } catch {
            if ($legacyPrepared) { & $RollbackLegacyAction $legacyState }
            throw
        } finally {
            if ($optionPath -and (Test-Path -LiteralPath $optionPath -PathType Leaf)) {
                Remove-Item -LiteralPath $optionPath -Force
            }
        }
    }
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
    Initialize-ProductionPostgreSqlPreparation,
    Install-ProductionPostgreSql,Set-ProductionPostgreSqlNetworkConfig,
    Initialize-ProductionPostgreSql,
    New-ProductionPgAdminServerRegistration,Install-ProductionPgAdmin,
    Test-ProductionPostgreSqlRestore,New-ProductionPostgreSqlBackup,
    Get-ProductionPostgreSqlStatus
