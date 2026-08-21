BeforeAll {
    $script:ModulePath = Join-Path $PSScriptRoot '..\modules\Production.PostgreSqlMigration.psm1'
    Import-Module $script:ModulePath -Force
}

Describe 'guarded PostgreSQL production authority cutover' {
    BeforeEach {
        $script:Events = [Collections.Generic.List[string]]::new()
        $script:Journal = $null
        $script:Now = [datetimeoffset]'2026-08-21T18:00:00Z'
        $script:Config = [pscustomobject]@{
            programDataRoot = 'C:\ProgramData\christopherbell.dev'
            productionPort = 8080
        }
        $script:Preflight = [pscustomobject][ordered]@{
            release = 'a' * 40
            lockToken = '11111111-2222-4333-8444-555555555555'
            sourceDatabase = 'christopherbell'
            targetDatabase = 'christopherbell'
            catalogDigest = 'b' * 64
            targetJdbcDigest = 'c' * 64
        }
        $script:ReadJournal = { $script:Journal }
        $script:WriteJournal = {
            param($Journal)
            $script:Journal = $Journal | ConvertTo-Json -Depth 30 -Compress |
                ConvertFrom-Json -Depth 30
            [void]$script:Events.Add("journal:$($Journal.phase)")
        }
        $script:Clock = { $script:Now }
        $script:Actions = @{
            Preflight = {
                param($Config)
                [void]$script:Events.Add('preflight')
                $script:Preflight
            }
            StopWriters = { param($State) [void]$script:Events.Add('stop-writers'); @{ digest='1' * 64 } }
            ArchiveMongo = { param($State) [void]$script:Events.Add('archive-mongo'); @{ digest='2' * 64 } }
            FinalizePostgreSql = { param($State) [void]$script:Events.Add('finalize'); @{ digest='3' * 64 } }
            ReconcilePostgreSql = { param($State) [void]$script:Events.Add('reconcile'); @{ digest='4' * 64 } }
            BackupPostgreSql = { param($State) [void]$script:Events.Add('backup-postgresql'); @{ digest='5' * 64 } }
            VerifyCandidate = { param($State) [void]$script:Events.Add('candidate'); @{ digest='6' * 64 } }
            PrepareAuthority = { param($State) [void]$script:Events.Add('prepare-authority'); @{ digest='b' * 64 } }
            PublishAuthority = { param($State) [void]$script:Events.Add('publish-authority'); @{ digest='7' * 64 } }
            ActivateProduction = { param($State) [void]$script:Events.Add('activate-production'); @{ digest='8' * 64 } }
            VerifyProduction = { param($State) [void]$script:Events.Add('verify-production'); @{ digest='9' * 64 } }
            EnterSoak = { param($State) [void]$script:Events.Add('enter-soak'); @{ digest='a' * 64 } }
            RestorePreAuthority = { param($State) [void]$script:Events.Add('restore-mongo'); @{ digest='f' * 64 } }
        }
    }

    It 'exports one cutover boundary without exposing a lower-level finalizer' {
        Get-Command Invoke-ProductionPostgreSqlCutover -ErrorAction SilentlyContinue |
            Should -Not -BeNullOrEmpty
        Get-Command Invoke-ProductionPostgreSqlFinalize -ErrorAction SilentlyContinue |
            Should -BeNullOrEmpty
    }

    It 'requires explicit confirmation before any effect' {
        { Invoke-ProductionPostgreSqlCutover -Config $script:Config `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*explicit confirmation*'
        $script:Events | Should -HaveCount 0
    }

    It 'performs no preflight lock journal or process effect under WhatIf' {
        Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock -WhatIf
        $script:Events | Should -HaveCount 0
        $script:Journal | Should -BeNullOrEmpty
    }

    It 'journals every exact transition and enters the forward-only soak' {
        Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock

        @($script:Events) | Should -Be @(
            'preflight','journal:PLANNED',
            'stop-writers','journal:WRITERS_STOPPED',
            'archive-mongo','journal:MONGO_ARCHIVED',
            'finalize','journal:POSTGRESQL_FINALIZED',
            'reconcile','journal:POSTGRESQL_RECONCILED',
            'backup-postgresql','journal:POSTGRESQL_BACKED_UP',
            'candidate','journal:CANDIDATE_VERIFIED',
            'prepare-authority','journal:AUTHORITY_PUBLICATION_STARTED',
            'publish-authority','journal:AUTHORITY_PUBLISHED',
            'activate-production','journal:PRODUCTION_ACTIVE',
            'verify-production','journal:PRODUCTION_VERIFIED',
            'enter-soak','journal:SOAKING')
        $script:Journal.authorityPublished | Should -BeTrue
        $script:Journal.phase | Should -BeExactly 'SOAKING'
        @($script:Journal.transitions) | Should -HaveCount 11
        $script:Journal.journalDigest | Should -Match '^[0-9a-f]{64}$'
    }

    It 'restores Mongo only when failure occurs before authority publication' {
        $script:Actions.VerifyCandidate = { throw 'synthetic candidate failure' }
        { Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*synthetic candidate failure*'

        @($script:Events) | Should -Contain 'restore-mongo'
        $script:Journal.phase | Should -BeExactly 'ROLLED_BACK'
        $script:Journal.authorityPublished | Should -BeFalse
    }

    It 'never restores Mongo after authority publication and journals forward repair' {
        $script:Actions.ActivateProduction = { throw 'synthetic activation failure' }
        { Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*synthetic activation failure*'

        @($script:Events) | Should -Not -Contain 'restore-mongo'
        $script:Journal.phase | Should -BeExactly 'FORWARD_RECOVERY_REQUIRED'
        $script:Journal.authorityPublished | Should -BeTrue
    }

    It 'rejects tampered durable state before resuming an effect' {
        Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock
        $script:Journal.release = 'd' * 40
        $script:Events.Clear()

        { Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*journal*invalid*'
        $script:Events | Should -HaveCount 0
    }

    It 'rejects a reordered transition even when its outer digest is recomputed' {
        Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock
        $script:Journal.transitions[3].prior = 'WRITERS_STOPPED'
        $module = Get-Module Production.PostgreSqlMigration -ErrorAction Stop
        $script:Journal.journalDigest = & $module {
            param($Journal) Get-ProductionPostgreSqlCutoverDigest -Journal $Journal
        } $script:Journal
        $script:Events.Clear()

        { Invoke-ProductionPostgreSqlCutover -Config $script:Config `
            -ConfirmPostgreSqlCutover -Actions $script:Actions `
            -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*journal*invalid*'
        $script:Events | Should -HaveCount 0
    }

    It 'matches the Java canonical authority and writer-lock hash fixture' {
        $module = Get-Module Production.PostgreSqlMigration -ErrorAction Stop
        $hashes = & $module {
            $values = [ordered]@{
                release='a' * 40; catalogDigest='b' * 64
                sourceDatabase='christopherbell'; targetDatabase='christopherbell'
                sourceDigest='c' * 64; backupDigest='d' * 64
                lockToken='11111111-2222-4333-8444-555555555555'
                sourceUri='mongodb://127.0.0.1:27017/christopherbell'
                targetJdbcUrl='jdbc:postgresql://127.0.0.1:5432/christopherbell'
                targetRole='christopherbell_bridge'
                writerLockPath='C:\ProgramData\christopherbell.dev\postgresql-migration-authority\writer.lock'
                writerLockDigest='e' * 64
            }
            $lock = "lockToken=11111111-2222-4333-8444-555555555555`n" +
                "release=$('a' * 40)`nstate=frozen`n" +
                'leaseExpiresAt=2026-08-21T18:30:00.0000000+00:00'
            @(
                Get-ProductionPostgreSqlCutoverCanonicalMapHash -Values $values
                Get-ProductionPostgreSqlCutoverCanonicalStringHash -Value $lock
            )
        }
        $hashes[0] | Should -BeExactly `
            '6c98a5d8435a6cb53f29b4a3c70c6b55cd8ad7822cd226edd0c757eb75045d1f'
        $hashes[1] | Should -BeExactly `
            '608e59ad770228d7293259c3a8d73b7a281bba434b72d4bd7fe7f02ec62ec52b'
    }

    It 'fails closed when the maintenance deadline is exceeded before authority' {
        $script:Actions.ArchiveMongo = {
            param($State)
            $script:Now = $script:Now.AddMinutes(31)
            @{ digest='2' * 64 }
        }
        { Invoke-ProductionPostgreSqlCutover -Config $script:Config -ConfirmPostgreSqlCutover `
            -Actions $script:Actions -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*maintenance budget*'
        $script:Journal.phase | Should -BeExactly 'ROLLED_BACK'
    }

    It 'fails forward when the final action exceeds the maintenance deadline' {
        $script:Actions.EnterSoak = {
            param($State)
            $script:Now = $script:Now.AddMinutes(31)
            @{ digest='a' * 64 }
        }

        { Invoke-ProductionPostgreSqlCutover -Config $script:Config `
            -ConfirmPostgreSqlCutover -Actions $script:Actions `
            -ReadJournalAction $script:ReadJournal `
            -WriteJournalAction $script:WriteJournal -ClockAction $script:Clock } |
            Should -Throw '*maintenance budget*'
        $script:Journal.phase | Should -BeExactly 'FORWARD_RECOVERY_REQUIRED'
        $script:Journal.authorityPublished | Should -BeTrue
    }
}

Describe 'postgres-cutover dispatcher boundary' {
    It 'requires the exact command and confirmation switch in prod.ps1' {
        $dispatcher = Get-Content (Join-Path $PSScriptRoot '..\prod.ps1') -Raw
        $dispatcher | Should -Match "'postgres-cutover'"
        $dispatcher | Should -Match '\[switch\]\$ConfirmPostgreSqlCutover'
        $dispatcher | Should -Match 'Invoke-ProductionPostgreSqlCutover'
    }
}

Describe 'PostgreSQL cutover default command boundaries' {
    BeforeEach {
        $script:Module = Get-Module Production.PostgreSqlMigration -ErrorAction Stop
    }

    It 'passes the bridge secret only through the child environment' {
        $root = Join-Path $TestDrive 'program-data'
        $release = Join-Path $root ('releases\' + ('a' * 40))
        New-Item -ItemType Directory -Path $release -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $release 'app.jar') -Value 'fixture'
        $config = [pscustomobject]@{ programDataRoot=$root; javaExe='java.exe' }
        $journal = [pscustomobject]@{
            release='a' * 40; lockToken='11111111-2222-4333-8444-555555555555'
        }
        $secret = 'task9-bridge-secret-value'
        $observed = & $script:Module {
            param($Config,$Journal,$Secret)
            $capture = [ordered]@{}
            $process = {
                param($FilePath,$Arguments,$Environment)
                $capture.FilePath = $FilePath
                $capture.Arguments = @($Arguments)
                $capture.Environment = @{} + $Environment
                'catalogDigest=' + ('b' * 64) + ' sourceDigest=' + ('c' * 64) + ' kinds=52'
            }.GetNewClosure()
            $output = Invoke-ProductionPostgreSqlCutoverJava -Config $Config `
                -Journal $Journal -Command snapshot -BridgePassword $Secret `
                -ProcessAction $process
            [pscustomobject]@{ Capture=$capture; Output=$output }
        } $config $journal $secret

        $observed.Output | Should -Match '^catalogDigest='
        ($observed.Capture.Arguments -join ' ') | Should -Not -Match ([regex]::Escape($secret))
        $observed.Capture.Environment.POSTGRESQL_MIGRATION_TARGET_PASSWORD |
            Should -BeExactly $secret
        $observed.Capture.Arguments[-1] | Should -BeExactly 'snapshot'
    }

    It 'fails closed when MongoDB remains fsync locked before recovery' {
        $config = [pscustomobject]@{ mongoShellExe='mongosh.exe' }
        $process = { param($FilePath,$Arguments,$Environment) '{"fsyncLock":true}' }

        { & $script:Module {
            param($Config,$Process)
            Assert-ProductionPostgreSqlCutoverMongoUnlocked `
                -Config $Config -ProcessAction $Process
        } $config $process } | Should -Throw '*authenticated manual unlock*'
    }

    It 'accepts only an explicit unlocked MongoDB currentOp result' {
        $config = [pscustomobject]@{ mongoShellExe='mongosh.exe' }
        $capture = [ordered]@{}
        $process = {
            param($FilePath,$Arguments,$Environment)
            $capture.FilePath = $FilePath
            $capture.Arguments = @($Arguments)
            '{"fsyncLock":false}'
        }.GetNewClosure()

        & $script:Module {
            param($Config,$Process)
            Assert-ProductionPostgreSqlCutoverMongoUnlocked `
                -Config $Config -ProcessAction $Process
        } $config $process

        $capture.FilePath | Should -BeExactly 'mongosh.exe'
        $capture.Arguments | Should -Contain 'mongodb://127.0.0.1:27017/admin'
        ($capture.Arguments -join ' ') | Should -Match 'currentOp'
    }

    It 'resumes after activation completed but its journal transition did not' {
        $root = Join-Path $TestDrive 'resume-program-data'
        $release = Join-Path $root ('releases\' + ('a' * 40))
        New-Item -ItemType Directory -Path $release -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $release 'app.jar') -Value 'fixture'
        $config = [pscustomobject]@{
            programDataRoot=$root; productionPort=8080; postgresqlServiceName='postgresql-x64-18'
        }
        $journal = [pscustomobject]@{
            release='a' * 40
            transitions=@([pscustomobject]@{
                next='AUTHORITY_PUBLISHED'; evidenceDigest='b' * 64
            })
        }

        InModuleScope Production.PostgreSqlMigration -Parameters @{
            Config=$config; Journal=$journal; Release=$release
        } {
            Mock Invoke-WithProductionPostgreSqlCutoverLock {
                param($Config,$Action)
                & $Action
            }
            Mock Get-JunctionTarget { $Release }
            Mock Read-ProductionPostgreSqlCutoverSidecar {
                [pscustomobject]@{ state='POSTGRESQL_AUTHORITY'; release='a' * 40 }
            }
            Mock Get-Service {
                param($Name)
                [pscustomobject]@{ Status = if ($Name -eq 'MongoDB') { 'Stopped' } else { 'Running' } }
            }
            Mock Test-ProductionEndpoints {}
            Mock Test-ProductionPublicEndpoints { $true }
            Mock Write-ProductionPostgreSqlCutoverSidecar { 'a' * 64 }
            Mock Switch-ProductionRelease { throw 'must not switch an already-active release' }

            $result = Start-ProductionPostgreSqlCutoverRelease `
                -Config $Config -Journal $Journal

            $result.digest | Should -BeExactly ('a' * 64)
            Should -Invoke Switch-ProductionRelease -Times 0 -Exactly
        }
    }
}
