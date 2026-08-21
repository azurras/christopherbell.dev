BeforeAll {
    $script:ModulePath = Join-Path $PSScriptRoot '..\modules\Production.PostgreSqlMigration.psm1'
    Import-Module $script:ModulePath -Force
}

Describe 'PostgreSQL shadow migration operations' {
    BeforeEach {
        $script:OwnershipToken = [guid]'22222222-3333-4444-8555-666666666666'
        $script:Config = [pscustomobject]@{
            programDataRoot = 'C:\ProgramData\christopherbell.dev'
            javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-25\bin\java.exe'
            postgresqlBinPath = 'C:\Program Files\PostgreSQL\18\bin'
            candidatePort = 18081
            migrationSourceUri = 'mongodb://127.0.0.1:27017/christopherbell'
            migrationSourceDatabase = 'christopherbell'
            migrationTargetJdbcUrl = 'jdbc:postgresql://127.0.0.1:55433/christopherbell'
            migrationTargetDatabase = 'christopherbell'
            migrationTargetRole = 'christopherbell_bridge'
            migrationTargetUsername = 'christopherbell_bridge'
            migrationTargetServerVersion = '18.4'
            migrationTargetDatabaseOwner = 'christopherbell_owner'
            migrationTargetOwnershipToken = $script:OwnershipToken.ToString()
            migrationSchemaPrefix = ''
            migrationCleanupTarget = '127.0.0.1:55433/christopherbell'
            migrationCleanupUsername = 'christopherbell_migrator'
            migrationCandidateRole = 'christopherbell_app'
            migrationCandidateUsername = 'christopherbell_app'
            migrationCandidateCleanupPort = 18081
        }
        $script:Release = 'C:\ProgramData\christopherbell.dev\releases\' + ('a' * 40)
        $script:Token = [guid]'11111111-2222-4333-8444-555555555555'
        $script:Calls = [Collections.Generic.List[object]]::new()
        $script:LockCalls = 0
        $script:IdentityCalls = 0
        $script:AppIdentityCalls = 0
        $script:CandidateCalls = 0
        $script:CleanupCalls = 0
        $script:CandidateEnvironments = [Collections.Generic.List[hashtable]]::new()
        $script:LockAction = {
            param($Path)
            $script:LockCalls++
            return [IO.MemoryStream]::new()
        }
        $script:ProcessAction = {
            param($FilePath,$Arguments,$Environment)
            $script:Calls.Add([pscustomobject]@{
                FilePath = $FilePath
                Arguments = @($Arguments)
                Environment = @{} + $Environment
            })
            return 'command=shadow kinds=52 statusDigest=' + ('a' * 64)
        }
        $script:IdentityAction = {
            param($Config,$Password)
            $script:IdentityCalls++
            [pscustomobject]@{
                Endpoint = '127.0.0.1:55433'
                Database = 'christopherbell'
                Role = 'christopherbell_bridge'
                ServerVersion = '18.4'
                DatabaseOwner = 'christopherbell_owner'
                OwnershipToken = $script:OwnershipToken.ToString()
                OwnedSchemaCount = 10
                OwnedHistoryCount = 1
            }
        }
        $script:AppIdentityAction = {
            param($Config,$Password)
            $script:AppIdentityCalls++
            [pscustomobject]@{
                Endpoint = '127.0.0.1:55433'
                Database = 'christopherbell'
                Role = 'christopherbell_app'
                ServerVersion = '18.4'
                DatabaseOwner = 'christopherbell_owner'
                OwnershipToken = $script:OwnershipToken.ToString()
                OwnedSchemaCount = 10
                OwnedHistoryCount = 1
            }
        }
        $script:CandidateAction = {
            param($Config,$Release,$Environment)
            $script:CandidateCalls++
            $script:CandidateEnvironments.Add(@{} + $Environment)
            [pscustomobject]@{
                Pid = 4242
                StartTimeUtcTicks = 638914080000000000
                Port = $Config.candidatePort
                CleanupVerified = $true
            }
        }
        $script:CleanupAction = {
            param($Config,$Password)
            $script:CleanupCalls++
            [pscustomobject]@{
                Endpoint = '127.0.0.1:55433'
                Database = 'christopherbell'
                DatabaseOwner = 'christopherbell_owner'
                OwnershipToken = $script:OwnershipToken.ToString()
                OwnedSchemaCount = 0
                OwnedHistoryCount = 0
                Removed = $true
            }
        }
    }

    It 'exports only guarded shadow and reconcile operations' {
        foreach ($name in 'Invoke-ProductionPostgreSqlShadow',
            'Invoke-ProductionPostgreSqlReconcile') {
            Get-Command $name -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
        Get-Command Invoke-ProductionPostgreSqlFinalize -ErrorAction SilentlyContinue |
            Should -BeNullOrEmpty
    }

    It 'runs shadow then reconcile with one production identity and no secret argument' {
        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
            -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction

        $script:LockCalls | Should -Be 1
        $script:Calls | Should -HaveCount 2
        $script:IdentityCalls | Should -Be 1
        $script:AppIdentityCalls | Should -Be 1
        $script:CandidateCalls | Should -Be 1
        $script:CleanupCalls | Should -Be 0
        @($script:Calls[0].Arguments)[-1] | Should -Be 'shadow'
        @($script:Calls[1].Arguments)[-1] | Should -Be 'reconcile'
        foreach ($call in $script:Calls) {
            ($call.Arguments -join ' ') | Should -Not -Match 'bridge-secret-value'
            $call.Environment.POSTGRESQL_MIGRATION_SOURCE_URI |
                Should -Be 'mongodb://127.0.0.1:27017/christopherbell'
            $call.Environment.POSTGRESQL_MIGRATION_TARGET_JDBC_URL |
                Should -Be 'jdbc:postgresql://127.0.0.1:55433/christopherbell'
            $call.Environment.POSTGRESQL_MIGRATION_TARGET_ROLE |
                Should -Be 'christopherbell_bridge'
            $call.Environment.POSTGRESQL_MIGRATION_TARGET_USERNAME |
                Should -Be 'christopherbell_bridge'
            $call.Environment.POSTGRESQL_MIGRATION_TARGET_PASSWORD |
                Should -Be 'bridge-secret-value'
            $call.Environment.POSTGRESQL_MIGRATION_LOCK_TOKEN |
                Should -Be $script:Token.ToString()
            $call.Environment.POSTGRESQL_MIGRATION_RELEASE | Should -Be ('a' * 40)
            $call.Environment.POSTGRESQL_MIGRATION_BRIDGE_RELEASE | Should -Be '1'
        }
        $script:CandidateEnvironments | Should -HaveCount 1
        $candidateEnvironment = $script:CandidateEnvironments[0]
        $candidateEnvironment.SPRING_DATASOURCE_USERNAME | Should -Be 'christopherbell_app'
        $candidateEnvironment.SPRING_DATASOURCE_PASSWORD | Should -Be 'app-secret-value'
        ($candidateEnvironment.Values -join "`n") | Should -Not -Match 'bridge-secret-value|migrator-secret-value'
    }

    It 'queries canonical server address and shared database ownership without exposing bridge secret' {
        $script:IdentityProcessCalls = [Collections.Generic.List[object]]::new()
        Mock Invoke-CheckedProcess -ModuleName Production.PostgreSqlMigration {
            param($FilePath,$ArgumentList,$Environment)
            $script:IdentityProcessCalls.Add([pscustomobject]@{
                Arguments = @($ArgumentList)
                Environment = @{} + $Environment
            })
            return '127.0.0.1:55433|christopherbell|christopherbell_bridge|18.4|christopherbell_owner|22222222-3333-4444-8555-666666666666|10|1'
        }

        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -AppIdentityAction $script:AppIdentityAction `
            -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction

        $script:IdentityProcessCalls | Should -HaveCount 1
        $identity = $script:IdentityProcessCalls[0]
        ($identity.Arguments -join "`n") | Should -Match 'host\(inet_server_addr\(\)\)'
        ($identity.Arguments -join "`n") | Should -Match "shobj_description\(database\.oid, 'pg_database'\)"
        ($identity.Arguments -join "`n") | Should -Not -Match 'bridge-secret-value'
        $identity.Environment.PGPASSWORD | Should -Be 'bridge-secret-value'
    }

    It 'resolves the active release identity when prod.ps1 supplies no release arguments' {
        Mock Get-JunctionTarget { $script:Release } -ModuleName Production.PostgreSqlMigration
        Mock Get-Content { '{"sha":"' + ('a' * 40) + '"}' } `
            -ModuleName Production.PostgreSqlMigration `
            -ParameterFilter { $LiteralPath -eq (Join-Path $script:Release 'release.json') }

        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
            -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction

        $script:Calls | Should -HaveCount 2
        foreach ($call in $script:Calls) {
            $call.Environment.POSTGRESQL_MIGRATION_RELEASE | Should -Be ('a' * 40)
        }
    }

    It 'performs no lock state or process effect under WhatIf' {
        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
            -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction -WhatIf

        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
        $script:IdentityCalls | Should -Be 0
        $script:AppIdentityCalls | Should -Be 0
        $script:CandidateCalls | Should -Be 0
        $script:CleanupCalls | Should -Be 0
    }

    It 'rejects endpoint release and secret drift before process I/O' {
        foreach ($case in @(
            @{ Sha = 'not-a-sha'; Password = 'bridge-secret-value' },
            @{ Sha = ('a' * 40); Password = 'replace-with-secret' },
            @{ Sha = ('a' * 40); Password = 'short' })) {
            { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha $case.Sha `
                -BridgePassword $case.Password -AppPassword 'app-secret-value' `
                -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $script:IdentityAction `
                -AppIdentityAction $script:AppIdentityAction `
                -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction } |
                Should -Throw
        }
        $script:Calls | Should -HaveCount 0
    }

    It 'rejects every observed target identity drift before migration process I/O' {
        foreach ($case in @(
            @{ Field = 'Endpoint'; Value = '127.0.0.1:55434' },
            @{ Field = 'Database'; Value = 'other' },
            @{ Field = 'Role'; Value = 'other_role' },
            @{ Field = 'ServerVersion'; Value = '18.3' },
            @{ Field = 'DatabaseOwner'; Value = 'caller_owner' },
            @{ Field = 'OwnershipToken'; Value = [guid]::NewGuid().ToString() },
            @{ Field = 'OwnedSchemaCount'; Value = 9 },
            @{ Field = 'OwnedHistoryCount'; Value = 0 })) {
            $field = $case.Field
            $value = $case.Value
            $driftedIdentity = {
                param($Config,$Password)
                $identity = [ordered]@{
                    Endpoint = '127.0.0.1:55433'
                    Database = 'christopherbell'
                    Role = 'christopherbell_bridge'
                    ServerVersion = '18.4'
                    DatabaseOwner = 'christopherbell_owner'
                    OwnershipToken = $script:OwnershipToken.ToString()
                    OwnedSchemaCount = 10
                    OwnedHistoryCount = 1
                }
                $identity[$field] = $value
                [pscustomobject]$identity
            }
            { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
                -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
                -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $driftedIdentity -AppIdentityAction $script:AppIdentityAction `
                -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction } |
                Should -Throw '*preflight identity drift*'
        }
        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
        $script:CandidateCalls | Should -Be 0
        $script:CleanupCalls | Should -Be 0
    }

    It 'rejects unsafe source target candidate and cleanup configuration before process I/O' {
        foreach ($case in @(
            @{ Field = 'migrationSourceUri'; Value = 'mongodb://127.0.0.1:27017/other' },
            @{ Field = 'migrationTargetJdbcUrl'; Value = 'jdbc:postgresql://127.0.0.1:5432/christopherbell' },
            @{ Field = 'migrationTargetServerVersion'; Value = '18.3' },
            @{ Field = 'migrationTargetOwnershipToken'; Value = [guid]::Empty.ToString() },
            @{ Field = 'migrationCandidateUsername'; Value = 'christopherbell_bridge' },
            @{ Field = 'migrationCleanupUsername'; Value = 'christopherbell_bridge' },
            @{ Field = 'candidatePort'; Value = 8080 },
            @{ Field = 'migrationCleanupTarget'; Value = '127.0.0.1:55434/christopherbell' },
            @{ Field = 'migrationCandidateCleanupPort'; Value = 18082 })) {
            $properties = [ordered]@{}
            foreach ($property in $script:Config.PSObject.Properties) {
                $properties[$property.Name] = $property.Value
            }
            $properties[$case.Field] = $case.Value
            $driftedConfig = [pscustomobject]$properties
            { Invoke-ProductionPostgreSqlShadow -Config $driftedConfig `
                -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
                -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
                -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $script:IdentityAction `
                -AppIdentityAction $script:AppIdentityAction `
                -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction } |
                Should -Throw
        }
        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
        $script:CandidateCalls | Should -Be 0
        $script:CleanupCalls | Should -Be 0
    }

    It 'rejects non-distinct bridge app and migrator secrets before process I/O' {
        foreach ($case in @(
            @{ App = 'bridge-secret-value'; Migrator = 'migrator-secret-value' },
            @{ App = 'app-secret-value'; Migrator = 'bridge-secret-value' },
            @{ App = 'app-secret-value'; Migrator = 'app-secret-value' })) {
            { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
                -BridgePassword 'bridge-secret-value' -AppPassword $case.App `
                -MigratorPassword $case.Migrator -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
                -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction } |
                Should -Throw '*distinct*'
        }
        $script:Calls | Should -HaveCount 0
        $script:IdentityCalls | Should -Be 0
        $script:CleanupCalls | Should -Be 0
    }

    It 'rejects candidate app-role identity drift then cleans only the verified owned target' {
        $driftedAppIdentity = {
            param($Config,$Password)
            [pscustomobject]@{
                Endpoint = '127.0.0.1:55433'
                Database = 'christopherbell'
                Role = 'christopherbell_bridge'
                ServerVersion = '18.4'
                DatabaseOwner = 'christopherbell_owner'
                OwnershipToken = $script:OwnershipToken.ToString()
                OwnedSchemaCount = 10
                OwnedHistoryCount = 1
            }
        }

        { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -AppIdentityAction $driftedAppIdentity `
            -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction } |
            Should -Throw '*candidate identity drift*'

        $script:Calls | Should -HaveCount 2
        $script:CandidateCalls | Should -Be 0
        $script:CleanupCalls | Should -Be 1
    }

    It 'cleans and reads back the exact verified owned target after migration failure' {
        $failingProcess = {
            param($FilePath,$Arguments,$Environment)
            throw 'synthetic migration failure'
        }

        { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $failingProcess -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
            -CandidateAction $script:CandidateAction -CleanupAction $script:CleanupAction } |
            Should -Throw '*synthetic migration failure*'

        $script:CleanupCalls | Should -Be 1
        $script:CandidateCalls | Should -Be 0
    }

    It 'uses only the migrator environment secret for exact default schema cleanup' {
        $script:CleanupProcessCalls = [Collections.Generic.List[object]]::new()
        Mock Invoke-CheckedProcess -ModuleName Production.PostgreSqlMigration {
            param($FilePath,$ArgumentList,$Environment)
            $script:CleanupProcessCalls.Add([pscustomobject]@{
                FilePath = $FilePath
                Arguments = @($ArgumentList)
                Environment = @{} + $Environment
            })
            return '0|0'
        }
        $failingProcess = {
            param($FilePath,$Arguments,$Environment)
            throw 'synthetic migration failure'
        }

        { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
            -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
            -ProcessAction $failingProcess -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
            -CandidateAction $script:CandidateAction } |
            Should -Throw '*synthetic migration failure*'

        $script:CleanupProcessCalls | Should -HaveCount 1
        $cleanup = $script:CleanupProcessCalls[0]
        $cleanup.FilePath | Should -Be 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
        $cleanup.Arguments | Should -Contain '--dbname=postgresql://127.0.0.1:55433/christopherbell'
        $cleanup.Arguments | Should -Contain '--username=christopherbell_migrator'
        $cleanup.Arguments -join "`n" | Should -Match 'DROP SCHEMA.*identity.*platform'
        $cleanup.Arguments -join "`n" | Should -Match 'DROP TABLE public\.flyway_schema_history'
        $cleanup.Arguments -join "`n" | Should -Match 'host\(inet_server_addr\(\)\)'
        $cleanup.Arguments -join "`n" | Should -Match "shobj_description\(database\.oid, 'pg_database'\)"
        ($cleanup.Arguments -join "`n") | Should -Not -Match `
            'bridge-secret-value|app-secret-value|migrator-secret-value'
        $cleanup.Environment.PGPASSWORD | Should -Be 'migrator-secret-value'
        ($cleanup.Environment.Values -join "`n") | Should -Not -Match `
            'bridge-secret-value|app-secret-value'
    }

    It 'does not hide the original failure when exact owned cleanup readback fails' {
        $failingProcess = {
            param($FilePath,$Arguments,$Environment)
            throw 'synthetic migration failure'
        }
        $incompleteCleanup = {
            param($Config,$Password)
            [pscustomobject]@{
                Endpoint = '127.0.0.1:55433'
                Database = 'christopherbell'
                DatabaseOwner = 'christopherbell_owner'
                OwnershipToken = $script:OwnershipToken.ToString()
                OwnedSchemaCount = 1
                OwnedHistoryCount = 1
                Removed = $false
            }
        }

        $failure = try {
            Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
                -BridgePassword 'bridge-secret-value' -AppPassword 'app-secret-value' `
                -MigratorPassword 'migrator-secret-value' -LockToken $script:Token `
                -ProcessAction $failingProcess -LockAction $script:LockAction `
                -IdentityAction $script:IdentityAction -AppIdentityAction $script:AppIdentityAction `
                -CandidateAction $script:CandidateAction -CleanupAction $incompleteCleanup
            $null
        } catch { $_.Exception }

        $failure | Should -BeOfType ([AggregateException])
        @($failure.InnerExceptions | ForEach-Object Message) -join "`n" |
            Should -Match 'synthetic migration failure'
        @($failure.InnerExceptions | ForEach-Object Message) -join "`n" |
            Should -Match 'cleanup readback'
    }
}
