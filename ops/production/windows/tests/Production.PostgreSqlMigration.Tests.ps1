BeforeAll {
    $script:ModulePath = Join-Path $PSScriptRoot '..\modules\Production.PostgreSqlMigration.psm1'
    Import-Module $script:ModulePath -Force
}

Describe 'PostgreSQL shadow migration operations' {
    BeforeEach {
        $script:Config = [pscustomobject]@{
            programDataRoot = 'C:\ProgramData\christopherbell.dev'
            javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-25\bin\java.exe'
        }
        $script:Release = 'C:\ProgramData\christopherbell.dev\releases\' + ('a' * 40)
        $script:Token = [guid]'11111111-2222-4333-8444-555555555555'
        $script:Calls = [Collections.Generic.List[object]]::new()
        $script:LockCalls = 0
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
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction

        $script:LockCalls | Should -Be 1
        $script:Calls | Should -HaveCount 2
        @($script:Calls[0].Arguments)[-1] | Should -Be 'shadow'
        @($script:Calls[1].Arguments)[-1] | Should -Be 'reconcile'
        foreach ($call in $script:Calls) {
            ($call.Arguments -join ' ') | Should -Not -Match 'bridge-secret-value'
            $call.Environment.POSTGRESQL_MIGRATION_SOURCE_URI |
                Should -Be 'mongodb://127.0.0.1:27017/christopherbell'
            $call.Environment.POSTGRESQL_MIGRATION_TARGET_JDBC_URL |
                Should -Be 'jdbc:postgresql://127.0.0.1:5432/christopherbell'
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
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction

        $script:Calls | Should -HaveCount 2
        foreach ($call in $script:Calls) {
            $call.Environment.POSTGRESQL_MIGRATION_RELEASE | Should -Be ('a' * 40)
        }
    }

    It 'performs no lock state or process effect under WhatIf' {
        Invoke-ProductionPostgreSqlShadow -Config $script:Config `
            -ReleasePath $script:Release -ReleaseSha ('a' * 40) `
            -BridgePassword 'bridge-secret-value' -LockToken $script:Token `
            -ProcessAction $script:ProcessAction -LockAction $script:LockAction -WhatIf

        $script:Calls | Should -HaveCount 0
        $script:LockCalls | Should -Be 0
    }

    It 'rejects endpoint release and secret drift before process I/O' {
        foreach ($case in @(
            @{ Sha = 'not-a-sha'; Password = 'bridge-secret-value' },
            @{ Sha = ('a' * 40); Password = 'replace-with-secret' },
            @{ Sha = ('a' * 40); Password = 'short' })) {
            { Invoke-ProductionPostgreSqlShadow -Config $script:Config `
                -ReleasePath $script:Release -ReleaseSha $case.Sha `
                -BridgePassword $case.Password -LockToken $script:Token `
                -ProcessAction $script:ProcessAction -LockAction $script:LockAction } |
                Should -Throw
        }
        $script:Calls | Should -HaveCount 0
    }
}
