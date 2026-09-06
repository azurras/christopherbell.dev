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

    It 'round trips signed journal timestamps through the actual disk reader' {
        InModuleScope Production.PostgreSqlMigration -Parameters @{ Root=$TestDrive } {
            Mock Protect-ProductionPath {}
            Mock Assert-ProtectedProductionPath {}
            $config = [pscustomobject]@{ programDataRoot=$Root }
            $preflight = [pscustomobject]@{
                release='a' * 40; lockToken='11111111-2222-4333-8444-555555555555'
                sourceDatabase='christopherbell'; targetDatabase='christopherbell'
                catalogDigest='b' * 64; targetJdbcDigest='c' * 64
            }
            $journal = New-ProductionPostgreSqlCutoverJournal -Preflight $preflight `
                -Now ([datetimeoffset]'2026-09-05T12:34:56.1234567Z') -MaintenanceBudgetMinutes 30
            $journal = Add-ProductionPostgreSqlCutoverTransition -Journal $journal `
                -Next WRITERS_STOPPED -EvidenceDigest ('d' * 64) `
                -Now ([datetimeoffset]'2026-09-05T12:35:00.7654321Z')
            Write-ProductionPostgreSqlCutoverJournal -Config $config -Journal $journal
            $loaded = Read-ProductionPostgreSqlCutoverJournal -Config $config
            $loaded.startedAt | Should -BeExactly '2026-09-05T12:34:56.1234567+00:00'
            $loaded.transitions[0].at | Should -BeExactly '2026-09-05T12:35:00.7654321+00:00'
            $loaded.journalDigest | Should -BeExactly $journal.journalDigest
        }
    }

    It 'encodes the writer lease as an ISO instant accepted by Java' {
        $text = & $script:Module {
            Get-ProductionPostgreSqlCutoverWriterLockText -Journal ([pscustomobject]@{
                release='a' * 40; lockToken='11111111-2222-4333-8444-555555555555'
                deadlineAt='2026-09-05T12:34:56.1234567+00:00'
            })
        }
        @($text -split "`n")[-1] |
            Should -BeExactly 'leaseExpiresAt=2026-09-05T12:34:56.1234567+00:00'
    }

    It 'records the activated release for subsequent writer starts without losing domain evidence' {
        InModuleScope Production.PostgreSqlMigration {
            Mock Read-ProductionMusicSchemaDirection { [pscustomobject]@{
                version=2; state='TARGET_ACTIVE'; targetRelease='b' * 40
                legacyRelease='c' * 40; evidenceDigest='d' * 64
                backupIdentity='e' * 64; legacyDropped=$true
            } }
            Mock Write-ProductionDomainSchemaDirection {}
            Update-ProductionPostgreSqlCutoverReleaseMarker -Config ([pscustomobject]@{}) `
                -Journal ([pscustomobject]@{release='a' * 40})
            Should -Invoke Write-ProductionDomainSchemaDirection -Times 1 -Exactly -ParameterFilter {
                $CurrentRelease -ceq ('a' * 40) -and $TargetRelease -ceq ('b' * 40) -and
                $LegacyRelease -ceq ('c' * 40) -and $EvidenceDigest -ceq ('d' * 64) -and
                $BackupIdentity -ceq ('e' * 64) -and $LegacyDropped
            }
        }
    }

    It 'uses a database-enforced read-only identity for pre-authority candidate acceptance' {
        InModuleScope Production.PostgreSqlMigration {
            Mock Invoke-WithProductionPostgreSqlCutoverLock { & $Action }
            Mock Assert-ProductionPostgreSqlCutoverWriterStopped {}
            Mock Get-ProductionPostgreSqlCutoverRelease { 'fixture-release' }
            Mock Get-ProductionPostgreSqlCutoverSecrets { [pscustomobject]@{
                Roles=@{ App='fixture-app'; Viewer='fixture-viewer' }
            } }
            Mock Test-CandidateRelease {}
            Mock Write-ProductionPostgreSqlCutoverSidecar { 'a' * 64 }
            $null = Test-ProductionPostgreSqlCutoverCandidate `
                -Config ([pscustomobject]@{candidatePort=8081}) `
                -Journal ([pscustomobject]@{release='b' * 40})
            Should -Invoke Test-CandidateRelease -Times 1 -Exactly -ParameterFilter {
                $AdditionalEnvironment.SPRING_DATASOURCE_USERNAME -ceq 'christopherbell_viewer' -and
                $AdditionalEnvironment.SPRING_DATASOURCE_PASSWORD -ceq 'fixture-viewer'
            }
        }
    }

    It 'replaces the Mongo service dependency and disables Mongo startup before activation' {
        InModuleScope Production.PostgreSqlMigration {
            Mock Assert-ProductionPostgreSqlCutoverWriterStopped {}
            Mock Invoke-CheckedProcess {}
            Mock Get-Service { [pscustomobject]@{
                ServicesDependedOn=@([pscustomobject]@{Name='postgresql-x64-18'})
            } }
            Mock Set-Service {}
            Set-ProductionPostgreSqlCutoverServiceDependency -Config ([pscustomobject]@{})
            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $FilePath -eq 'sc.exe' -and ($ArgumentList -join ' ') -eq
                    'config ChristopherBellDev depend= postgresql-x64-18'
            }
            Should -Invoke Set-Service -Times 1 -Exactly -ParameterFilter {
                $Name -eq 'MongoDB' -and $StartupType -eq 'Disabled'
            }
        }
    }

    It 'rejects dependency readback drift before disabling Mongo startup' {
        InModuleScope Production.PostgreSqlMigration {
            Mock Assert-ProductionPostgreSqlCutoverWriterStopped {}
            Mock Invoke-CheckedProcess {}
            Mock Get-Service { [pscustomobject]@{
                ServicesDependedOn=@([pscustomobject]@{Name='MongoDB'})
            } }
            Mock Set-Service {}
            { Set-ProductionPostgreSqlCutoverServiceDependency -Config ([pscustomobject]@{}) } |
                Should -Throw '*dependency*'
            Should -Invoke Set-Service -Times 0 -Exactly
        }
    }

    It 'writes the initial cutover journal through the production file boundary' {
        $root = Join-Path $TestDrive 'journal-program-data'
        $config = [pscustomobject]@{ programDataRoot=$root }
        $journal = [pscustomobject][ordered]@{
            version=1; phase='PLANNED'; release='a' * 40
        }

        InModuleScope Production.PostgreSqlMigration -Parameters @{
            Config=$config; Journal=$journal
        } {
            Mock Protect-ProductionPath {}
            Mock Assert-ProtectedProductionPath {}

            Write-ProductionPostgreSqlCutoverJournal -Config $Config -Journal $Journal

            $path = Join-Path $Config.programDataRoot `
                'migration\postgresql-cutover.json'
            $written = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            $written.phase | Should -BeExactly 'PLANNED'
            $written.release | Should -BeExactly ('a' * 40)
        }
    }

    It 'calls production path protection with declared parameters only' {
        $tokens = $null
        $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile(
            $script:ModulePath, [ref]$tokens, [ref]$parseErrors)
        $declared = (Get-Command Protect-ProductionPath -ErrorAction Stop).Parameters.Keys
        $invalid = @($ast.FindAll({
            param($node)
            $node -is [Management.Automation.Language.CommandAst] -and
                $node.GetCommandName() -ceq 'Protect-ProductionPath'
        }, $true) | ForEach-Object {
            $_.CommandElements | Where-Object {
                $_ -is [Management.Automation.Language.CommandParameterAst] -and
                    $declared -cnotcontains $_.ParameterName
            } | ForEach-Object ParameterName
        })

        $parseErrors | Should -BeNullOrEmpty
        $invalid | Should -BeNullOrEmpty
    }

    It 'hashes the exact migration catalog embedded in the release JAR' {
        $release = Join-Path $TestDrive 'catalog-release'
        $archiveRoot = Join-Path $TestDrive 'catalog-archive'
        $catalog = Join-Path $archiveRoot `
            'BOOT-INF\classes\db\migration\postgresql-migration-catalog.yml'
        New-Item -ItemType Directory -Path $release,(Split-Path $catalog) -Force |
            Out-Null
        $catalogBytes = [Text.Encoding]::UTF8.GetBytes("catalog-version: 1`n")
        [IO.File]::WriteAllBytes($catalog, $catalogBytes)
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::CreateFromDirectory(
            $archiveRoot, (Join-Path $release 'app.jar'))
        $expected = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($catalogBytes)).ToLowerInvariant()

        $actual = & $script:Module {
            param($Release)
            Get-ProductionPostgreSqlCutoverCatalogDigest -Release $Release
        } $release

        $actual | Should -BeExactly $expected
    }

    It 'keeps private default action helpers resolvable after module import' {
        $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' }

        $actions = InModuleScope Production.PostgreSqlMigration -Parameters @{
            Config=$config
        } {
            New-ProductionPostgreSqlCutoverActions -Config $Config
        }

        $preflightAction = $actions['Preflight']
        { & $preflightAction $config $null } |
            Should -Throw '*Missing PostgreSQL configuration value: javaExe*'
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

    It 'extracts one exact <Command> evidence line from Java stdout logs' -TestCases @(
        @{
            Command='snapshot'
            Evidence='catalogDigest=' + ('b' * 64) +
                ' sourceDigest=' + ('c' * 64) + ' kinds=52'
        }
        @{
            Command='finalize'
            Evidence='command=finalize kinds=52 statusDigest=' + ('d' * 64)
        }
        @{
            Command='reconcile'
            Evidence='command=reconcile kinds=52 statusDigest=' + ('e' * 64)
        }
    ) {
        $root = Join-Path $TestDrive 'logged-java-output'
        $release = Join-Path $root ('releases\' + ('a' * 40))
        New-Item -ItemType Directory -Path $release -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $release 'app.jar') -Value 'fixture'
        $config = [pscustomobject]@{ programDataRoot=$root; javaExe='java.exe' }
        $journal = [pscustomobject]@{
            release='a' * 40; lockToken='11111111-2222-4333-8444-555555555555'
        }
        $process = {
            param($FilePath,$Arguments,$Environment)
            "2026-08-29 INFO MongoClient - initialized`r`n$Evidence`r`n" +
                '2026-08-29 INFO MongoClient - closed'
        }.GetNewClosure()

        $actual = & $script:Module {
            param($Config,$Journal,$Process,$Command)
            Invoke-ProductionPostgreSqlCutoverJava -Config $Config `
                -Journal $Journal -Command $Command -BridgePassword 'fixture-secret' `
                -ProcessAction $Process
        } $config $journal $process $Command

        $actual | Should -BeExactly $Evidence
    }

    It 'rejects <Label> <Command> evidence in Java stdout' -TestCases @(
        @{ Label='missing'; Command='snapshot'; Output='INFO MongoClient - initialized' }
        @{
            Label='malformed alongside valid'
            Command='snapshot'
            Output="catalogDigest=malformed`n" + ('catalogDigest=' + ('b' * 64) +
                ' sourceDigest=' + ('c' * 64) + ' kinds=52')
        }
        @{
            Label='wrong command alongside valid'
            Command='finalize'
            Output=('command=reconcile kinds=52 statusDigest=' + ('e' * 64)) +
                "`n" + ('command=finalize kinds=52 statusDigest=' + ('d' * 64))
        }
        @{
            Label='malformed'
            Command='finalize'
            Output='command=finalize kinds=51 statusDigest=' + ('d' * 64)
        }
        @{
            Label='duplicated'
            Command='reconcile'
            Output=('command=reconcile kinds=52 statusDigest=' + ('e' * 64)) +
                "`r`n" + ('command=reconcile kinds=52 statusDigest=' + ('e' * 64))
        }
    ) {
        $root = Join-Path $TestDrive 'invalid-java-output'
        $release = Join-Path $root ('releases\' + ('a' * 40))
        New-Item -ItemType Directory -Path $release -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $release 'app.jar') -Value 'fixture'
        $config = [pscustomobject]@{ programDataRoot=$root; javaExe='java.exe' }
        $journal = [pscustomobject]@{
            release='a' * 40; lockToken='11111111-2222-4333-8444-555555555555'
        }
        $process = {
            param($FilePath,$Arguments,$Environment)
            $Output
        }.GetNewClosure()

        { & $script:Module {
            param($Config,$Journal,$Process,$Command)
            Invoke-ProductionPostgreSqlCutoverJava -Config $Config `
                -Journal $Journal -Command $Command -BridgePassword 'fixture-secret' `
                -ProcessAction $Process
        } $config $journal $process $Command } |
            Should -Throw "*Java $Command evidence is invalid*"
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
            Mock Update-ProductionPostgreSqlCutoverReleaseMarker {}
            Mock Set-ProductionWebsiteRecoveryPolicy {}
            Mock Switch-ProductionRelease { throw 'must not switch an already-active release' }

            $result = Start-ProductionPostgreSqlCutoverRelease `
                -Config $Config -Journal $Journal

            $result.digest | Should -BeExactly ('a' * 64)
            Should -Invoke Switch-ProductionRelease -Times 0 -Exactly
        }
    }
}
