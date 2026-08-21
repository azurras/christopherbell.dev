BeforeAll {
    $script:ModulePath = Join-Path $PSScriptRoot '..\modules\Production.PostgreSqlMigration.psm1'
    Import-Module $script:ModulePath -Force
}

Describe 'PostgreSQL shadow migration operations' {
    BeforeEach {
        $script:Config = [pscustomobject]@{
            programDataRoot = 'C:\ProgramData\christopherbell.dev'
            javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-25\bin\java.exe'
            candidatePort = 18081
            migrationSourceUri = 'mongodb://127.0.0.1:27017/christopherbell'
            migrationSourceDatabase = 'christopherbell'
            migrationTargetJdbcUrl = 'jdbc:postgresql://127.0.0.1:55433/christopherbell'
            migrationTargetDatabase = 'christopherbell'
            migrationTargetRole = 'christopherbell_bridge'
            migrationTargetUsername = 'christopherbell_bridge'
            migrationTargetServerVersion = '18.4'
            migrationSchemaPrefix = ''
            migrationCleanupTarget = '127.0.0.1:55433/christopherbell'
            migrationCandidateCleanupPort = 18081
        }
        $script:Release = 'C:\ProgramData\christopherbell.dev\releases\' + ('a' * 40)
        $script:Token = [guid]'11111111-2222-4333-8444-555555555555'
        $script:Calls = [Collections.Generic.List[object]]::new()
        $script:LockCalls = 0
        $script:IdentityCalls = 0
        $script:CandidateCalls = 0
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
            }
        }
        $script:CandidateAction = {
            param($Config,$Release,$Environment)
            $script:CandidateCalls++
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
            -BridgePassword 'bridge-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -CandidateAction $script:CandidateAction

        $script:LockCalls | Should -Be 1
        $script:Calls | Should -HaveCount 2
        $script:IdentityCalls | Should -Be 1
        $script:CandidateCalls | Should -Be 1
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
    }

    It 'resolves the active release identity when prod.ps1 supplies no release arguments' {
        Mock Get-JunctionTarget { $script:Release } -ModuleName Production.PostgreSqlMigration
        Mock Get-Content { '{"sha":"' + ('a' * 40) + '"}' } `
            -ModuleName Production.PostgreSqlMigration `
            -ParameterFilter { $LiteralPath -eq (Join-Path $script:Release 'release.json') }

        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -BridgePassword 'bridge-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -CandidateAction $script:CandidateAction

        $script:Calls | Should -HaveCount 2
        foreach ($call in $script:Calls) {
            $call.Environment.POSTGRESQL_MIGRATION_RELEASE | Should -Be ('a' * 40)
        }
    }

    It 'performs no lock state or process effect under WhatIf' {
        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
            -IdentityAction $script:IdentityAction -CandidateAction $script:CandidateAction -WhatIf

        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
        $script:IdentityCalls | Should -Be 0
        $script:CandidateCalls | Should -Be 0
    }

    It 'rejects endpoint release and secret drift before process I/O' {
        foreach ($case in @(
            @{ Sha = 'not-a-sha'; Password = 'bridge-secret-value' },
            @{ Sha = ('a' * 40); Password = 'replace-with-secret' },
            @{ Sha = ('a' * 40); Password = 'short' })) {
            { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha $case.Sha `
                -BridgePassword $case.Password -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $script:IdentityAction `
                -CandidateAction $script:CandidateAction } |
                Should -Throw
        }
        $script:Calls | Should -HaveCount 0
    }

    It 'rejects every observed target identity drift before migration process I/O' {
        foreach ($case in @(
            @{ Field = 'Endpoint'; Value = '127.0.0.1:55434' },
            @{ Field = 'Database'; Value = 'other' },
            @{ Field = 'Role'; Value = 'other_role' },
            @{ Field = 'ServerVersion'; Value = '18.3' })) {
            $field = $case.Field
            $value = $case.Value
            $driftedIdentity = {
                param($Config,$Password)
                $identity = [ordered]@{
                    Endpoint = '127.0.0.1:55433'
                    Database = 'christopherbell'
                    Role = 'christopherbell_bridge'
                    ServerVersion = '18.4'
                }
                $identity[$field] = $value
                [pscustomobject]$identity
            }
            { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
                -BridgePassword 'bridge-secret-value' -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $driftedIdentity -CandidateAction $script:CandidateAction } |
                Should -Throw '*preflight identity drift*'
        }
        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
        $script:CandidateCalls | Should -Be 0
    }

    It 'rejects unsafe source target candidate and cleanup configuration before process I/O' {
        foreach ($case in @(
            @{ Field = 'migrationSourceUri'; Value = 'mongodb://127.0.0.1:27017/other' },
            @{ Field = 'migrationTargetJdbcUrl'; Value = 'jdbc:postgresql://127.0.0.1:5432/christopherbell' },
            @{ Field = 'migrationTargetServerVersion'; Value = '18.3' },
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
                -BridgePassword 'bridge-secret-value' -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction `
                -IdentityAction $script:IdentityAction `
                -CandidateAction $script:CandidateAction } | Should -Throw
        }
        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
        $script:CandidateCalls | Should -Be 0
    }
}
