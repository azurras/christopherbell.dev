BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\modules\Production.PostgreSql.psm1'
    if (Test-Path -LiteralPath $modulePath -PathType Leaf) {
        Import-Module $modulePath -Force
    }
}

Describe 'native PostgreSQL production operations' {
    BeforeEach {
        $script:bin = Join-Path $TestDrive 'postgres-bin'
        $script:backupRoot = Join-Path $TestDrive 'postgres-backups'
        $script:programData = Join-Path $TestDrive (
            'program-data-' + [guid]::NewGuid().ToString('N'))
        $script:pgAdminRoot = Join-Path $TestDrive 'pgAdmin 4'
        $script:pgAdminRuntime = Join-Path $pgAdminRoot 'runtime'
        $script:pgAdminWeb = Join-Path $pgAdminRoot 'web'
        $script:pgAdmin = Join-Path $pgAdminRuntime 'pgAdmin4.exe'
        $script:dataPath = Join-Path $TestDrive 'postgres-data'
        $script:java = Join-Path $TestDrive 'java.exe'
        $script:current = Join-Path $programData 'current'
        $script:release = Join-Path $programData 'releases\1111111111111111111111111111111111111111'
        $script:jar = Join-Path $release 'app.jar'
        New-Item -ItemType Directory -Force $bin,$backupRoot,$programData,
            (Join-Path $programData 'locks'),(Join-Path $programData 'config'),
            $pgAdminRuntime,$pgAdminWeb,$dataPath,$release | Out-Null
        foreach ($name in 'psql.exe','pg_dump.exe','pg_restore.exe','postgres.exe') {
            New-Item -ItemType File -Force (Join-Path $bin $name) | Out-Null
        }
        New-Item -ItemType File -Force $pgAdmin | Out-Null
        New-Item -ItemType File -Force $java,$jar | Out-Null
        New-Item -ItemType Junction -Path $current -Target $release | Out-Null
        New-Item -ItemType File -Force (Join-Path $pgAdminRuntime 'python.exe'),
            (Join-Path $pgAdminWeb 'setup.py') | Out-Null
        "# base config" | Set-Content (Join-Path $dataPath 'postgresql.conf')
        "# base hba" | Set-Content (Join-Path $dataPath 'pg_hba.conf')
        $script:config = [pscustomobject]@{
            programDataRoot = $programData
            postgresqlVersion = '18.4'
            postgresqlBinPath = $bin
            postgresqlServiceName = 'postgresql-x64-18'
            postgresqlBackupRoot = $backupRoot
            postgresqlDataPath = $dataPath
            pgAdminExe = $pgAdmin
            javaExe = $java
            smokeAccountEmail = 'admin@christopherbell.dev'
        }
        $script:packageIdentity = [pscustomobject]@{
            DisplayName='PostgreSQL 18 '
            DisplayVersion='18.4-1'
            Publisher='PostgreSQL Global Development Group'
            InstallLocation=(Split-Path -Parent $bin)
        }
        $script:serviceIdentity = [pscustomobject]@{
            Name='postgresql-x64-18'
            PathName="`"$(Join-Path $bin 'pg_ctl.exe')`" runservice " +
                "-N `"postgresql-x64-18`" -D `"$dataPath`" -w"
            StartName='NT AUTHORITY\NetworkService'
        }
    }

    It 'holds the protected deployment lock across bootstrap process effects' {
        $secrets = [ordered]@{
            Test = 'test-secret-value'; Migrator = 'migrator-secret-value'
            App = 'app-secret-value'; Bridge = 'bridge-secret-value'
            Viewer = 'viewer-secret-value'; Backup = 'backup-secret-value'
        }
        $action = {
            param($FilePath,$Arguments,$Environment)
            { Enter-DeploymentLock -LockPath (Join-Path $config.programDataRoot 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            return ''
        }

        Initialize-ProductionPostgreSql -Config $config -RoleSecrets $secrets `
            -AdministratorPassword 'administrator-secret-value' -ProcessAction $action `
            -ServiceAction { param($Name) }
    }

    It 'writes an exact loopback-only SCRAM server and client authentication policy' {
        Set-ProductionPostgreSqlNetworkConfig -Config $config

        $server = Get-Content (Join-Path $dataPath 'conf.d\christopherbell-production.conf') -Raw
        $hba = Get-Content (Join-Path $dataPath 'pg_hba.conf') -Raw
        $server | Should -Match "listen_addresses\s*=\s*'localhost'"
        $server | Should -Match "password_encryption\s*=\s*'scram-sha-256'"
        $hba | Should -Match 'host\s+all\s+all\s+127\.0\.0\.1/32\s+scram-sha-256'
        $hba | Should -Match 'host\s+all\s+all\s+::1/128\s+scram-sha-256'
        $hba | Should -Not -Match '0\.0\.0\.0/0|::/0|\btrust\b'
    }

    It 'restarts the exact PostgreSQL service under the deployment lock before bootstrap I/O' {
        $events = [Collections.Generic.List[string]]::new()
        $secrets = [ordered]@{
            Test = 'test-secret-value'; Migrator = 'migrator-secret-value'
            App = 'app-secret-value'; Bridge = 'bridge-secret-value'
            Viewer = 'viewer-secret-value'; Backup = 'backup-secret-value'
        }
        $serviceAction = {
            param($Name)
            { Enter-DeploymentLock -LockPath (Join-Path $config.programDataRoot 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            $events.Add("service:$Name")
        }
        $processAction = {
            param($FilePath,$Arguments,$Environment)
            $events.Add("process:$([IO.Path]::GetFileName($FilePath))")
            return ''
        }

        Initialize-ProductionPostgreSql -Config $config -RoleSecrets $secrets `
            -AdministratorPassword 'administrator-secret-value' `
            -ServiceAction $serviceAction -ProcessAction $processAction

        $events | Should -Be @('service:postgresql-x64-18','process:psql.exe','process:java.exe')
    }

    It 'exports the guarded PostgreSQL operation boundary' {
        Test-Path -LiteralPath $modulePath -PathType Leaf | Should -BeTrue
        foreach ($name in 'Initialize-ProductionPostgreSqlPreparation',
            'Install-ProductionPostgreSql','Initialize-ProductionPostgreSql',
            'Get-ProductionPostgreSqlStatus','New-ProductionPostgreSqlBackup',
            'Test-ProductionPostgreSqlRestore','Install-ProductionPgAdmin') {
            Get-Command $name -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
    }

    It 'creates seven independent protected credentials without returning their values' {
        $path = Join-Path $programData 'config\postgresql.env'
        New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
        $protection = [Collections.Generic.List[object]]::new()
        $protectDirectory = {
            param($directory)
            $protection.Add([pscustomobject]@{
                Stage = 'directory'; Path = $directory
                SecretExists = Test-Path -LiteralPath $path -PathType Leaf
            })
        }
        $protectFile = {
            param($file)
            $protection.Add([pscustomobject]@{
                Stage = 'file'; Path = $file
                Length = (Get-Item -LiteralPath $file).Length
            })
        }

        InModuleScope Production.PostgreSql -Parameters @{
            SecretPath=$path; ProtectDirectory=$protectDirectory; ProtectFile=$protectFile
        } {
            $result = New-ProductionPostgreSqlSecretFile -Path $SecretPath `
                -ProtectDirectoryAction $ProtectDirectory -ProtectPathAction $ProtectFile `
                -AssertPathAction { param($file) }

            $result.Created | Should -BeTrue
            $result.SecretCount | Should -Be 7
            ($result | ConvertTo-Json -Compress) | Should -Not -Match 'PASSWORD|[A-Za-z0-9_-]{32}'
        }

        $protection | Should -HaveCount 3
        $protection[0].Stage | Should -Be 'directory'
        $protection[0].SecretExists | Should -BeFalse
        $protection[1].Stage | Should -Be 'file'
        $protection[1].Length | Should -Be 0
        $protection[2].Stage | Should -Be 'file'
        $values = Get-Content -LiteralPath $path | ForEach-Object { ($_ -split '=',2)[1] }
        $values | Should -HaveCount 7
        @($values | Select-Object -Unique) | Should -HaveCount 7
        foreach ($value in $values) { $value | Should -Match '^[A-Za-z0-9_-]{43}$' }
    }

    It 'holds the deployment lock across configuration protection and credential preparation' {
        $events = [Collections.Generic.List[string]]::new()
        $configuration = {
            param($root)
            { Enter-DeploymentLock -LockPath (Join-Path $root 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            $events.Add('configuration')
            return $config
        }
        $protect = {
            param($root)
            { Enter-DeploymentLock -LockPath (Join-Path $root 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            $events.Add('protect')
        }
        $secrets = {
            param($path)
            { Enter-DeploymentLock -LockPath (Join-Path $programData 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            $events.Add('secrets')
            [pscustomobject]@{ Created=$true; SecretCount=7 }
        }

        InModuleScope Production.PostgreSql -Parameters @{
            Root=$programData; Configuration=$configuration; Protect=$protect; Secrets=$secrets
        } {
            $result = Invoke-ProductionPostgreSqlPreparationCore -Root $Root `
                -ConfigurationAction $Configuration -ProtectSecretsAction $Protect `
                -SecretFileAction $Secrets
            $result.Prepared | Should -BeTrue
            $result.Configuration | Should -Be 'Validated'
            $result.Credentials | Should -Be 'Created'
            $result.CredentialCount | Should -Be 7
            ($result | ConvertTo-Json -Compress) | Should -Not -Match '(?i)password|secret'
        }

        $events | Should -Be @('protect','configuration','protect','secrets')
    }

    It 'preserves a complete credential file and rejects a mixed placeholder file' {
        $path = Join-Path $programData 'config\postgresql.env'
        New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
        $complete = @(
            'POSTGRES_ADMIN_PASSWORD=admin-existing-secret-value',
            'CB_MIGRATOR_PASSWORD=migrator-existing-secret-value',
            'CB_APP_PASSWORD=app-existing-secret-value',
            'CB_BRIDGE_PASSWORD=bridge-existing-secret-value',
            'CB_VIEWER_PASSWORD=viewer-existing-secret-value',
            'CB_BACKUP_PASSWORD=backup-existing-secret-value',
            'CB_TEST_PASSWORD=test-existing-secret-value') -join "`n"
        [IO.File]::WriteAllText($path, $complete)
        $before = [IO.File]::ReadAllBytes($path)

        InModuleScope Production.PostgreSql -Parameters @{ SecretPath=$path } {
            $result = New-ProductionPostgreSqlSecretFile -Path $SecretPath `
                -ProtectDirectoryAction { param($directory) } `
                -ProtectPathAction { param($file) } -AssertPathAction { param($file) }
            $result.Created | Should -BeFalse
        }
        [Convert]::ToHexString([IO.File]::ReadAllBytes($path)) |
            Should -Be ([Convert]::ToHexString($before))

        $mixed = $complete.Replace('app-existing-secret-value',
            'replace-with-protected-app-role-secret')
        [IO.File]::WriteAllText($path, $mixed)
        InModuleScope Production.PostgreSql -Parameters @{ SecretPath=$path } {
            { New-ProductionPostgreSqlSecretFile -Path $SecretPath `
                -ProtectDirectoryAction { param($directory) } `
                -ProtectPathAction { param($file) } -AssertPathAction { param($file) } } |
                Should -Throw '*partially configured*'
        }
        [IO.File]::ReadAllText($path) | Should -Be $mixed
    }

    It 'installs with a protected option file and removes it without exposing the administrator secret' {
        Remove-Item -LiteralPath (Join-Path $bin 'postgres.exe') -Force
        $administratorSecret = 'administrator-secret-never-in-arguments'
        $calls = [Collections.Generic.List[object]]::new()
        $protected = [Collections.Generic.List[object]]::new()
        $optionPath = $null
        $process = {
            param($FilePath,$Arguments,$Environment)
            $calls.Add([pscustomobject]@{ FilePath=$FilePath; Arguments=@($Arguments) })
            if ($FilePath -eq 'winget.exe') {
                $override = $Arguments[([array]::IndexOf($Arguments,'--override') + 1)]
                $optionMatch = [regex]::Match($override, '--optionfile\s+"([^"]+)"')
                $optionMatch.Success | Should -BeTrue
                $script:optionPath = $optionMatch.Groups[1].Value
                (Get-Content -LiteralPath $script:optionPath -Raw) |
                    Should -Match ([regex]::Escape("superpassword=$administratorSecret"))
                New-Item -ItemType File -Force (Join-Path $bin 'postgres.exe') | Out-Null
                return ''
            }
            return 'postgres (PostgreSQL) 18.4'
        }
        $protect = {
            param($Path)
            $protected.Add([pscustomobject]@{ Path=$Path; Length=(Get-Item $Path).Length })
        }

        $result = Install-ProductionPostgreSql -Config $config `
            -AdministratorPassword $administratorSecret -ProcessAction $process `
            -PackageIdentityAction { $packageIdentity } `
            -ServiceIdentityAction { param($Name) $serviceIdentity } `
            -ProtectOptionFileAction $protect `
            -AssertOptionFileAction { param($Path) } `
            -PrepareLegacyAction { [pscustomobject]@{ WasRunning=$false } } `
            -CommitLegacyAction { param($state) } -RollbackLegacyAction { param($state) }

        $result.Installed | Should -BeTrue
        $protected | Should -HaveCount 1
        $protected[0].Length | Should -Be 0
        Test-Path -LiteralPath $script:optionPath | Should -BeFalse
        ($calls | ForEach-Object { $_.Arguments -join ' ' }) -join "`n" |
            Should -Not -Match ([regex]::Escape($administratorSecret))
        ($calls[0].Arguments -join ' ') | Should -Match '--mode unattended'
        ($calls[0].Arguments -join ' ') | Should -Match '--optionfile'
        ($result | ConvertTo-Json -Compress) | Should -Not -Match 'administrator-secret'
    }

    It 'restores the preserved PostgreSQL 16 service and removes the option file when installation fails' {
        Remove-Item -LiteralPath (Join-Path $bin 'postgres.exe') -Force
        $events = [Collections.Generic.List[string]]::new()
        $optionPath = $null
        $process = {
            param($FilePath,$Arguments,$Environment)
            $override = $Arguments[([array]::IndexOf($Arguments,'--override') + 1)]
            $optionMatch = [regex]::Match($override, '--optionfile\s+"([^"]+)"')
            $optionMatch.Success | Should -BeTrue
            $script:optionPath = $optionMatch.Groups[1].Value
            $events.Add('installer')
            throw 'synthetic installer failure'
        }

        { Install-ProductionPostgreSql -Config $config `
            -AdministratorPassword 'administrator-secret-value' -ProcessAction $process `
            -ProtectOptionFileAction { param($Path) } `
            -AssertOptionFileAction { param($Path) } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add('legacy-commit') } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") } } |
            Should -Throw '*synthetic installer failure*'

        $events | Should -Be @('legacy-stop','installer','legacy-restore:legacy-state')
        Test-Path -LiteralPath $script:optionPath | Should -BeFalse
    }

    It 'removes the empty installer option file when ACL protection fails before returning its path' {
        Remove-Item -LiteralPath (Join-Path $bin 'postgres.exe') -Force
        $optionPath = Join-Path $programData 'config\.postgresql-18-install.options'
        $events = [Collections.Generic.List[string]]::new()

        { Install-ProductionPostgreSql -Config $config `
            -AdministratorPassword 'administrator-secret-value' `
            -ProcessAction { param($FilePath,$Arguments,$Environment) throw 'process must not run' } `
            -ProtectOptionFileAction { param($Path) throw 'synthetic ACL failure' } `
            -AssertOptionFileAction { param($Path) } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add('legacy-commit') } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") } } |
            Should -Throw '*synthetic ACL failure*'

        $events | Should -Be @('legacy-stop','legacy-restore:legacy-state')
        Test-Path -LiteralPath $optionPath | Should -BeFalse
    }

    It 'rejects an option-file-unsafe administrator password before filesystem or process effects' {
        Remove-Item -LiteralPath (Join-Path $bin 'postgres.exe') -Force
        $optionPath = Join-Path $programData 'config\.postgresql-18-install.options'
        $events = [Collections.Generic.List[string]]::new()

        { Install-ProductionPostgreSql -Config $config `
            -AdministratorPassword "administrator-secret`noptionfile-injection=true" `
            -ProcessAction { param($FilePath,$Arguments,$Environment) $events.Add('process') } `
            -ProtectOptionFileAction { param($Path) $events.Add('protect') } `
            -AssertOptionFileAction { param($Path) $events.Add('assert') } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add('legacy-commit') } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") } } |
            Should -Throw '*administrator password*option-file-safe*'

        $events | Should -Be @('legacy-stop','legacy-restore:legacy-state')
        Test-Path -LiteralPath $optionPath | Should -BeFalse
    }

    It 'completes the legacy service transition when retry finds PostgreSQL 18 already installed' {
        $events = [Collections.Generic.List[string]]::new()
        $process = {
            param($FilePath,$Arguments,$Environment)
            $events.Add("process:$([IO.Path]::GetFileName($FilePath)):$($Arguments -join ',')")
            return 'postgres (PostgreSQL) 18.4'
        }

        $result = Install-ProductionPostgreSql -Config $config -ProcessAction $process `
            -PackageIdentityAction { $packageIdentity } `
            -ServiceIdentityAction { param($Name) $serviceIdentity } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add("legacy-commit:$state") } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") }

        $result.Installed | Should -BeTrue
        $result.Version | Should -Be '18.4'
        $events | Should -Be @('legacy-stop','process:postgres.exe:--version',
            'legacy-commit:legacy-state')
    }

    It 'accepts the exact registered EDB package when the PostgreSQL runtime is unsigned' {
        $events = [Collections.Generic.List[string]]::new()
        $package = {
            [pscustomobject]@{
                DisplayName='PostgreSQL 18 '
                DisplayVersion='18.4-1'
                Publisher='PostgreSQL Global Development Group'
                InstallLocation=(Split-Path -Parent $bin)
            }
        }

        $result = Install-ProductionPostgreSql -Config $config `
            -ProcessAction { param($FilePath,$Arguments,$Environment)
                return 'postgres (PostgreSQL) 18.4' } `
            -PackageIdentityAction $package `
            -ServiceIdentityAction { param($Name) $serviceIdentity } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add("legacy-commit:$state") } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") }

        $result.Installed | Should -BeTrue
        $events | Should -Be @('legacy-stop','legacy-commit:legacy-state')
    }

    It 'reruns the installer when runtime files exist without the PostgreSQL 18 service' {
        $events = [Collections.Generic.List[string]]::new()
        $script:serviceLookupCount = 0
        $serviceLookup = {
            param($Name)
            $script:serviceLookupCount++
            if ($script:serviceLookupCount -eq 1) { return $null }
            return $serviceIdentity
        }
        $process = {
            param($FilePath,$Arguments,$Environment)
            $events.Add("process:$([IO.Path]::GetFileName($FilePath))")
            if ($FilePath -eq 'winget.exe') { return '' }
            return 'postgres (PostgreSQL) 18.4'
        }

        $result = Install-ProductionPostgreSql -Config $config `
            -AdministratorPassword 'administrator-partial-retry-secret' `
            -ProcessAction $process -PackageIdentityAction { $packageIdentity } `
            -ServiceIdentityAction $serviceLookup `
            -ProtectOptionFileAction { param($Path) } `
            -AssertOptionFileAction { param($Path) } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add("legacy-commit:$state") } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") }

        $result.Installed | Should -BeTrue
        $events | Should -Be @('legacy-stop','process:winget.exe','process:postgres.exe',
            'legacy-commit:legacy-state')
        $script:serviceLookupCount | Should -Be 2
    }

    It 'rejects a mismatched registered package and restores the legacy service state' {
        $events = [Collections.Generic.List[string]]::new()
        $wrongPackage = [pscustomobject]@{
            DisplayName='PostgreSQL 18'
            DisplayVersion='18.6-1'
            Publisher='PostgreSQL Global Development Group'
            InstallLocation=(Split-Path -Parent $bin)
        }

        { Install-ProductionPostgreSql -Config $config `
            -ProcessAction { param($FilePath,$Arguments,$Environment)
                return 'postgres (PostgreSQL) 18.4' } `
            -PackageIdentityAction { $wrongPackage } `
            -ServiceIdentityAction { param($Name) $serviceIdentity } `
            -PrepareLegacyAction { $events.Add('legacy-stop'); 'legacy-state' } `
            -CommitLegacyAction { param($state) $events.Add("legacy-commit:$state") } `
            -RollbackLegacyAction { param($state) $events.Add("legacy-restore:$state") } } |
            Should -Throw '*package identity*18.4-1*'

        $events | Should -Be @('legacy-stop','legacy-restore:legacy-state')
    }

    It 'restores the legacy startup mode when stopping PostgreSQL 16 fails inside preparation' {
        InModuleScope Production.PostgreSql {
            Mock Get-Service { [pscustomobject]@{ Status='Running' } }
            Mock Get-CimInstance { [pscustomobject]@{ StartMode='Auto' } }
            Mock Get-NetTCPConnection { @() }
            Mock Set-Service { }
            Mock Stop-Service { throw 'synthetic legacy stop failure' }
            Mock Start-Service { }

            { Enter-ProductionPostgreSqlLegacyReplacement } |
                Should -Throw '*synthetic legacy stop failure*'

            Should -Invoke Set-Service -Times 1 -Exactly -ParameterFilter {
                $Name -eq 'postgresql-x64-16' -and $StartupType -eq 'Disabled'
            }
            Should -Invoke Set-Service -Times 1 -Exactly -ParameterFilter {
                $Name -eq 'postgresql-x64-16' -and $StartupType -eq 'Automatic'
            }
            Should -Invoke Start-Service -Times 0
        }
    }

    It 'builds least-privilege bootstrap SQL without embedding role secrets' {
        $secrets = [ordered]@{
            Test = 'test-secret-value'; Migrator = 'migrator-secret-value'
            App = 'app-secret-value'; Bridge = 'bridge-secret-value'
            Viewer = 'viewer-secret-value'; Backup = 'backup-secret-value'
        }

        $sql = Get-ProductionPostgreSqlBootstrapSql -RoleSecrets $secrets

        foreach ($secret in $secrets.Values) { $sql | Should -Not -Match ([regex]::Escape($secret)) }
        $sql | Should -Match 'christopherbell_owner'
        $sql | Should -Match 'christopherbell_migrator'
        $sql | Should -Match 'christopherbell_app'
        $sql | Should -Match 'christopherbell_bridge'
        $sql | Should -Match 'christopherbell_viewer'
        $sql | Should -Match 'christopherbell_backup'
        $sql | Should -Match 'christopherbell_test'
        $sql | Should -Match 'default_transaction_read_only'
        $sql | Should -Match 'GRANT\s+CONNECT,\s+CREATE\s+ON\s+DATABASE\s+test\s+TO\s+christopherbell_test'
        $sql | Should -Match 'REVOKE\s+CREATE\s+ON\s+SCHEMA\s+public\s+FROM\s+christopherbell_app'
        $sql | Should -Match 'GRANT\s+USAGE,\s+CREATE\s+ON\s+SCHEMA\s+public\s+TO\s+christopherbell_test'
        $sql | Should -Not -Match 'CB_OWNER_PASSWORD|owner_password'
    }

    It 'keeps secrets in a protected process environment instead of command arguments' {
        $captured = [Collections.Generic.List[object]]::new()
        $action = {
            param($FilePath,$Arguments,$Environment)
            $captured.Add([pscustomobject]@{
                FilePath=$FilePath; Arguments=@($Arguments); Environment=@{} + $Environment
            })
            return ''
        }
        $secrets = [ordered]@{
            Test = 'test-secret-value'; Migrator = 'migrator-secret-value'
            App = 'app-secret-value'; Bridge = 'bridge-secret-value'
            Viewer = 'viewer-secret-value'; Backup = 'backup-secret-value'
        }

        Initialize-ProductionPostgreSql -Config $config -RoleSecrets $secrets `
            -AdministratorPassword 'administrator-secret-value' -ProcessAction $action `
            -ServiceAction { param($Name) }

        $captured | Should -HaveCount 2
        ($captured[0].Arguments -join ' ') | Should -Not -Match 'secret-value'
        $captured[0].Environment.PGPASSWORD | Should -Be 'administrator-secret-value'
        $captured[0].Environment.CB_APP_PASSWORD | Should -Be 'app-secret-value'
        $captured[0].Environment.CB_TEST_PASSWORD | Should -Be 'test-secret-value'
        $captured[1].FilePath | Should -Be $java
        ($captured[1].Arguments -join ' ') | Should -Match 'PropertiesLauncher'
        ($captured[1].Arguments -join ' ') | Should -Match 'ProductionPostgresqlSchemaMigrator'
        ($captured[1].Arguments -join ' ') | Should -Not -Match 'secret-value'
        $captured[1].Environment.SPRING_DATASOURCE_URL |
            Should -Be 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
        $captured[1].Environment.SPRING_DATASOURCE_USERNAME |
            Should -Be 'christopherbell_migrator'
        $captured[1].Environment.SPRING_DATASOURCE_PASSWORD |
            Should -Be 'migrator-secret-value'
    }

    It 'registers only test read-write and production read-only pgAdmin servers without passwords' {
        $registration = New-ProductionPgAdminServerRegistration -Config $config
        $json = $registration | ConvertTo-Json -Depth 10 -Compress

        @($registration.Servers.PSObject.Properties.Value.Name) |
            Should -Be @('christopherbell-test','christopherbell-production-viewer')
        $json | Should -Not -Match '(?i)password|passfile|owner|migrator|bridge|backup'
        $registration.Servers.'2'.Username | Should -Be 'christopherbell_viewer'
        $registration.Servers.'2'.MaintenanceDB | Should -Be 'christopherbell'
    }

    It 'imports pgAdmin registrations with the desktop runtime and stores no password' {
        $calls = [Collections.Generic.List[object]]::new()
        $action = {
            param($FilePath,$Arguments,$Environment)
            $calls.Add([pscustomobject]@{ FilePath=$FilePath; Arguments=@($Arguments) })
            return ''
        }

        $registrationPath = Install-ProductionPgAdmin -Config $config -ProcessAction $action `
            -ProtectRegistrationAction { param($Path) }

        $calls | Should -HaveCount 1
        $calls[0].FilePath | Should -Be (Join-Path $pgAdminRuntime 'python.exe')
        $calls[0].Arguments | Should -Contain (Join-Path $pgAdminWeb 'setup.py')
        $calls[0].Arguments | Should -Contain 'load-servers'
        $calls[0].Arguments | Should -Contain '--user'
        $calls[0].Arguments | Should -Contain 'admin@christopherbell.dev'
        (Get-Content -LiteralPath $registrationPath -Raw) |
            Should -Not -Match '(?i)password|passfile|owner|migrator|bridge|backup'
    }

    It 'creates a checksummed custom archive and proves an isolated dry restore' {
        $calls = [Collections.Generic.List[object]]::new()
        $protected = [Collections.Generic.List[string]]::new()
        $action = {
            param($FilePath,$Arguments,$Environment)
            { Enter-DeploymentLock -LockPath (Join-Path $config.programDataRoot 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            $calls.Add([pscustomobject]@{
                FilePath=$FilePath; Arguments=@($Arguments); Environment=@{} + $Environment
            })
            $fileArgument = @($Arguments | Where-Object { $_ -like '--file=*' }) | Select-Object -First 1
            if ($fileArgument) {
                [IO.File]::WriteAllText($fileArgument.Substring(7), 'custom-format-backup')
            }
            return ''
        }
        $protect = {
            param($Path)
            { Enter-DeploymentLock -LockPath (Join-Path $config.programDataRoot 'locks\deploy.lock') } |
                Should -Throw '*already running*'
            $protected.Add($Path)
        }

        $result = New-ProductionPostgreSqlBackup -Config $config `
            -BackupPassword 'backup-secret-value' `
            -AdministratorPassword 'administrator-secret-value' -ProcessAction $action `
            -ProtectBackupAction $protect `
            -UtcNow ([datetime]'2026-08-20T12:34:56Z')

        $result.Archive | Should -Match 'christopherbell-20260820T123456Z\.dump$'
        $result.Sha256 | Should -Match '^[A-F0-9]{64}$'
        Test-Path -LiteralPath $result.Evidence -PathType Leaf | Should -BeTrue
        $calls | Should -HaveCount 4
        ($calls[0].Arguments -join ' ') | Should -Match '--format=custom'
        $calls[0].Environment.PGPASSWORD | Should -Be 'backup-secret-value'
        ($calls[1].Arguments -join ' ') | Should -Match 'OWNER christopherbell_backup'
        $calls[1].Environment.PGPASSWORD | Should -Be 'administrator-secret-value'
        ($calls[2].Arguments -join ' ') | Should -Match '--exit-on-error'
        $calls[2].Environment.PGPASSWORD | Should -Be 'backup-secret-value'
        $calls[3].Environment.PGPASSWORD | Should -Be 'administrator-secret-value'
        $protected | Should -Be @($backupRoot)
        ($calls | ForEach-Object { $_.Arguments -join ' ' }) -join "`n" |
            Should -Not -Match 'backup-secret-value'
    }

    It 'drops the isolated restore database when pg_restore fails' {
        $archive = Join-Path $backupRoot 'failure.dump'
        'custom-format-backup' | Set-Content -LiteralPath $archive
        $digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        $calls = [Collections.Generic.List[object]]::new()
        $action = {
            param($FilePath,$Arguments,$Environment)
            $calls.Add([pscustomobject]@{ FilePath=$FilePath; Arguments=@($Arguments) })
            if ([IO.Path]::GetFileName($FilePath) -eq 'pg_restore.exe') {
                throw 'synthetic restore failure'
            }
            return ''
        }

        { Test-ProductionPostgreSqlRestore -Config $config -Archive $archive `
            -ExpectedDigest $digest -BackupPassword 'backup-secret-value' `
            -AdministratorPassword 'administrator-secret-value' -ProcessAction $action } |
            Should -Throw '*synthetic restore failure*'

        $calls | Should -HaveCount 3
        ($calls[2].Arguments -join ' ') | Should -Match 'DROP DATABASE IF EXISTS'
    }

    It 'rejects a restore archive outside the configured backup root before process I/O' {
        $archive = Join-Path $TestDrive 'outside.dump'
        'custom-format-backup' | Set-Content -LiteralPath $archive
        $digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        $calls = [Collections.Generic.List[string]]::new()
        $action = { param($FilePath,$Arguments,$Environment) $calls.Add($FilePath); return '' }

        { Test-ProductionPostgreSqlRestore -Config $config -Archive $archive `
            -ExpectedDigest $digest -BackupPassword 'backup-secret-value' `
            -AdministratorPassword 'administrator-secret-value' -ProcessAction $action } |
            Should -Throw '*backup root*'

        $calls | Should -HaveCount 0
    }

    It 'treats an exact registered PostgreSQL 18.4 runtime as idempotent' {
        $calls = [Collections.Generic.List[object]]::new()
        $action = {
            param($FilePath,$Arguments,$Environment)
            $calls.Add([pscustomobject]@{ FilePath=$FilePath; Arguments=@($Arguments) })
            return 'postgres (PostgreSQL) 18.4'
        }
        $result = Install-ProductionPostgreSql -Config $config -ProcessAction $action `
            -PackageIdentityAction { $packageIdentity } `
            -ServiceIdentityAction { param($Name) $serviceIdentity } `
            -PrepareLegacyAction { 'legacy-state' } `
            -CommitLegacyAction { param($state) } `
            -RollbackLegacyAction { param($state) }

        $result.Installed | Should -BeTrue
        $result.Version | Should -Be '18.4'
        $calls | Should -HaveCount 1
        $calls[0].FilePath | Should -Be (Join-Path $bin 'postgres.exe')
        $calls[0].Arguments | Should -Be @('--version')
    }

    It 'reports redacted listener service database and role capability state' {
        $probe = {
            param($FilePath,$Arguments,$Environment)
            return '{"database":"christopherbell","role":"christopherbell_app","serverVersion":"18.4","listenAddresses":"localhost","passwordEncryption":"scram-sha-256","canCreateSchema":false,"viewerReadOnly":true}'
        }

        $status = Get-ProductionPostgreSqlStatus -Config $config `
            -AppPassword 'app-secret-value' -ProcessAction $probe

        $status.Database | Should -Be 'christopherbell'
        $status.ServerVersion | Should -Be '18.4'
        $status.ListenAddresses | Should -Be 'localhost'
        $status.PasswordEncryption | Should -Be 'scram-sha-256'
        $status.AppCanCreateSchema | Should -BeFalse
        $status.ViewerReadOnly | Should -BeTrue
        ($status | ConvertTo-Json -Compress) | Should -Not -Match 'secret-value'
    }

    It 'rejects string-shaped capability observations instead of coercing false to true' {
        $probe = {
            param($FilePath,$Arguments,$Environment)
            return '{"database":"christopherbell","role":"christopherbell_app","serverVersion":"18.4","listenAddresses":"localhost","passwordEncryption":"scram-sha-256","canCreateSchema":false,"viewerReadOnly":"false"}'
        }

        { Get-ProductionPostgreSqlStatus -Config $config `
            -AppPassword 'app-secret-value' -ProcessAction $probe } |
            Should -Throw '*unsafe production identity or capability*'
    }

    It 'performs no installer bootstrap or pgAdmin process effects under WhatIf' {
        $calls = [Collections.Generic.List[string]]::new()
        $action = { param($FilePath,$Arguments,$Environment) $calls.Add($FilePath); return '' }

        Install-ProductionPostgreSql -Config $config -WhatIf -ProcessAction $action
        Initialize-ProductionPostgreSql -Config $config -WhatIf -ProcessAction $action
        Install-ProductionPgAdmin -Config $config -WhatIf -ProcessAction $action

        $calls | Should -BeNullOrEmpty
    }
}
