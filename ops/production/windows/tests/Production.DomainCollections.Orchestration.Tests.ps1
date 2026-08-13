Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.MusicRuntime.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.AutoDeploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.DomainCollections.psm1') -Force

Describe 'guarded domain collection cutover orchestration' {
    InModuleScope Production.DomainCollections {
        BeforeEach {
            $script:events = [Collections.Generic.List[string]]::new()
            $script:exerciseRealCutoverContext = $false
            $script:exerciseRealRetryResolver = $false
            $script:prepareTargetCutoverImplementation =
                (Get-Command Prepare-ProductionDomainCollectionTargetCutover).
                    ScriptBlock
            $script:completeCutoverImplementation =
                (Get-Command Complete-ProductionDomainCollectionCutover).
                    ScriptBlock
            $script:config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
                candidatePort = 8081
                repositoryPath = 'A:\Projects\christopherbell.dev'
                remote = 'origin'
                branch = 'main'
            }
            $script:releaseMetadataConfig = [pscustomobject]@{
                programDataRoot = $TestDrive
                remote = 'origin'
                branch = 'main'
            }
            $script:context = [pscustomobject][ordered]@{
                config = $script:config
                targetRelease = '2' * 40
                targetPath = 'C:\ProgramData\christopherbell.dev\releases\' + ('2' * 40)
                legacyRelease = '1' * 40
                archive = 'A:\backups\verified.archive.gz'
                backupIdentity = 'a' * 64
                evidenceDigest = 'b' * 64
                ownerToken = 'c' * 32
                evidence = [pscustomobject]@{ version = 1 }
                dropStarted = $false
                writerStopped = $false
            }
            Mock Read-ProductionConfig { $script:config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [void]$script:events.Add('lock:acquire')
                $lock = [pscustomobject]@{}
                $lock | Add-Member ScriptMethod Dispose {
                    [void]$script:events.Add('lock:release')
                }
                [pscustomobject]@{ Lock = $lock; Boundary = [pscustomobject]@{} }
            }
            Mock Assert-ProductionFixedRootBoundary {
                [void]$script:events.Add('root:recheck')
                [pscustomobject]@{}
            }
            Mock Resolve-ProductionDomainCollectionPrepublicationForCutoverRetry { } `
                -ParameterFilter { -not $script:exerciseRealRetryResolver }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                $markerPath = Get-ProductionMusicSchemaDirectionPath `
                    -Config $State.config
                New-Item -ItemType Directory `
                    -Path (Split-Path -Parent $markerPath) -Force | Out-Null
                [ordered]@{
                    version = 2
                    state = $MarkerState
                    updatedAtEpochMillis = 1
                    targetRelease = [string]$State.targetRelease
                    currentRelease = [string]$State.currentRelease
                    legacyRelease = [string]$State.legacyRelease
                    manifestDigest = $script:ManifestDigest
                    evidenceDigest = [string]$State.evidenceDigest
                    backupIdentity = [string]$State.backupIdentity
                    legacyDropped = $false
                } | ConvertTo-Json |
                    Set-Content -LiteralPath $markerPath -Encoding utf8
            }
            Mock New-ProductionDomainCollectionCutoverContext {
                Stop-ProductionDomainCollectionWriter -Context $script:context
                [void]$script:events.Add('backup-and-evidence')
                $script:context
            } -ParameterFilter { -not $script:exerciseRealCutoverContext }
            Mock Invoke-ProductionDomainCollectionCandidateProof {
                [void]$script:events.Add('candidate')
            }
            Mock Stop-ProductionDomainCollectionWriter {
                $Context.writerStopped = $true
                [void]$script:events.Add('stop-suspended')
            }
            Mock Invoke-ProductionDomainCollectionStageAndPublish {
                [void]$script:events.Add('stage-publish')
            }
            Mock Prepare-ProductionDomainCollectionTargetCutover {
                [void]$script:events.Add('target-cutover-prepare')
            }
            Mock Invoke-ProductionDomainCollectionEngine {
                if ($Action -cne 'verify-live') {
                    throw "unexpected engine action $Action"
                }
                [void]$script:events.Add('stopped-target-proof')
                [pscustomobject]@{ complete = $true }
            }
            Mock Invoke-ProductionDomainCollectionDropLegacy {
                $Context.dropStarted = $true
                [void]$script:events.Add('drop-legacy')
            }
            Mock Complete-ProductionDomainCollectionCutover {
                [void]$script:events.Add('marker-recovery-auto')
            }
            Mock Invoke-ProductionDomainCollectionFailureRecovery {
                [void]$script:events.Add("recover:$PostDrop")
            }
        }

        function script:New-DomainCollectionReleaseMetadataFixture {
            param(
                [Parameter(Mandatory)][string]$Name,
                [Parameter(Mandatory)][string]$Sha,
                [AllowNull()][object]$DomainSchema = $null,
                [string]$Metadata = ''
            )
            $releaseRoot = Join-Path (Join-Path $TestDrive 'releases') $Name
            $release = Join-Path $releaseRoot $Sha
            New-Item -ItemType Directory -Path $release -Force | Out-Null
            $metadataPath = Join-Path $release 'release.json'
            if ([string]::IsNullOrEmpty($Metadata)) {
                $base = '{{"sha":"{0}","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY"' -f $Sha
                $Metadata = if ($null -eq $DomainSchema) {
                    $base + '}'
                } else {
                    $base + (' ,"domainSchema":"{0}"}}' -f $DomainSchema)
                }
            }
            [IO.File]::WriteAllText($metadataPath, $Metadata, [Text.UTF8Encoding]::new($false))
            [pscustomobject]@{ release = $release; metadataPath = $metadataPath }
        }

        function script:New-DomainCollectionReleaseJarFixture {
            param(
                [Parameter(Mandatory)][string]$Release,
                [Parameter(Mandatory)][bool]$IncludesV015
            )
            Add-Type -AssemblyName System.IO.Compression
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $archive = [IO.Compression.ZipFile]::Open(
                (Join-Path $Release 'app.jar'), [IO.Compression.ZipArchiveMode]::Create)
            try {
                $application = $archive.CreateEntry(
                    'BOOT-INF/classes/dev/christopherbell/Application.class')
                $applicationStream = $application.Open()
                try { $applicationStream.WriteByte(0) } finally { $applicationStream.Dispose() }
                if ($IncludesV015) {
                    $migration = $archive.CreateEntry(
                        'BOOT-INF/classes/dev/christopherbell/configuration/mongo/migration/V015RequireDomainCollectionSchema.class')
                    $migrationStream = $migration.Open()
                    try { $migrationStream.WriteByte(0) } finally { $migrationStream.Dispose() }
                }
            } finally {
                $archive.Dispose()
            }
        }

        function script:Enable-DomainCollectionMetadataPublication {
            Mock Write-ProductionDomainCollectionProtectedJson {
                param($Config, $Path, $Value, $Depth)
                $jsonDepth = if ($null -eq $Depth) { 20 } else { [int]$Depth }
                $Value | ConvertTo-Json -Depth $jsonDepth -Compress |
                    Set-Content -LiteralPath $Path -Encoding utf8 -NoNewline
            }
        }

        It 'refreshes the writer-start guard before starting the historical legacy release' {
            Mock Ensure-ProductionWriterStartGuardUnderHeldLock {
                [void]$script:events.Add('guard:refresh')
            }
            Mock Switch-ProductionRelease {
                [void]$script:events.Add('release:start')
            }
            $script:context | Add-Member NoteProperty legacyPath `
                ('C:\ProgramData\christopherbell.dev\releases\' + ('1' * 40))

            Start-ProductionDomainCollectionLegacy -State $script:context

            $script:events | Should -Be @('guard:refresh','release:start')
        }

        It 'backfills a historical target release from the exact V015 JAR entry' {
            $sha = '2' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'historical-target' -Sha $sha
            New-DomainCollectionReleaseJarFixture -Release $fixture.release -IncludesV015 $true
            Enable-DomainCollectionMetadataPublication

            (Get-ProductionDomainCollectionReleaseSchema `
                -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha) |
                Should -BeExactly 'TARGET'
            @((Get-Content -LiteralPath $fixture.metadataPath -Raw | ConvertFrom-Json).
                PSObject.Properties.Name) | Should -Be @(
                'sha','source','builtAt','musicSchema','domainSchema')
            (Get-Content -LiteralPath $fixture.metadataPath -Raw | ConvertFrom-Json).
                domainSchema | Should -BeExactly 'TARGET'
        }

        It 'backfills a historical legacy release without the V015 JAR entry' {
            $sha = '1' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'historical-legacy' -Sha $sha
            New-DomainCollectionReleaseJarFixture -Release $fixture.release -IncludesV015 $false
            Enable-DomainCollectionMetadataPublication

            (Get-ProductionDomainCollectionReleaseSchema `
                -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha) |
                Should -BeExactly 'LEGACY'
            @((Get-Content -LiteralPath $fixture.metadataPath -Raw | ConvertFrom-Json).
                PSObject.Properties.Name) | Should -Be @(
                'sha','source','builtAt','musicSchema','domainSchema')
            (Get-Content -LiteralPath $fixture.metadataPath -Raw | ConvertFrom-Json).
                domainSchema | Should -BeExactly 'LEGACY'
        }

        It 'preserves the exact historical metadata scalar strings during backfill' {
            $sha = '8' * 40
            $builtAt = '2026-08-12T00:00:00.1200000Z'
            $metadata = ('{{"sha":"{0}","source":"origin/main","builtAt":"{1}","musicSchema":"LEGACY"}}' -f $sha,$builtAt)
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'preserve-metadata-scalars' -Sha $sha -Metadata $metadata
            New-DomainCollectionReleaseJarFixture -Release $fixture.release -IncludesV015 $true
            Enable-DomainCollectionMetadataPublication

            (Get-ProductionDomainCollectionReleaseSchema `
                -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha) |
                Should -BeExactly 'TARGET'
            $backfilled = Get-Content -LiteralPath $fixture.metadataPath -Raw
            $backfilled | Should -Match ([regex]::Escape('"sha":"' + $sha + '"'))
            $backfilled | Should -Match ([regex]::Escape('"source":"origin/main"'))
            $backfilled | Should -Match ([regex]::Escape('"builtAt":"' + $builtAt + '"'))
            $backfilled | Should -Match ([regex]::Escape('"musicSchema":"LEGACY"'))
        }

        It 'rejects a matching-SHA release outside the configured releases root without mutation' {
            $sha = '9' * 40
            $outside = Join-Path (Join-Path $TestDrive 'outside') $sha
            New-Item -ItemType Directory -Path $outside -Force | Out-Null
            $metadataPath = Join-Path $outside 'release.json'
            $metadata = ('{{"sha":"{0}","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY","domainSchema":"TARGET"}}' -f $sha)
            [IO.File]::WriteAllText($metadataPath, $metadata, [Text.UTF8Encoding]::new($false))
            $original = [IO.File]::ReadAllBytes($metadataPath)
            Mock Get-ProductionDomainCollectionHistoricalReleaseSchema {
                throw 'outside release JAR must not be opened'
            }
            Mock Write-ProductionDomainCollectionProtectedJson {
                throw 'outside release metadata must not be published'
            }

            { Get-ProductionDomainCollectionReleaseSchema `
                    -Config $script:releaseMetadataConfig -Release $outside -Sha $sha } |
                Should -Throw '*Domain collection release metadata is invalid*'
            [IO.File]::ReadAllBytes($metadataPath) | Should -Be $original
            Should -Invoke Get-ProductionDomainCollectionHistoricalReleaseSchema -Times 0 -Exactly
            Should -Invoke Write-ProductionDomainCollectionProtectedJson -Times 0 -Exactly
        }

        It 'rejects an object-valued top-level builtAt before JAR access or publication' {
            $sha = 'a' * 40
            $metadata = ('{{"sha":"{0}","source":"origin/main","builtAt":{{"builtAt":"2026-08-12T00:00:00.0000000Z"}},"musicSchema":"LEGACY","domainSchema":"TARGET"}}' -f $sha)
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'object-built-at' -Sha $sha -Metadata $metadata
            $original = [IO.File]::ReadAllBytes($fixture.metadataPath)
            Mock Get-ProductionDomainCollectionHistoricalReleaseSchema {
                throw 'object-valued builtAt JAR must not be opened'
            }
            Mock Write-ProductionDomainCollectionProtectedJson {
                throw 'object-valued builtAt metadata must not be published'
            }

            { Get-ProductionDomainCollectionReleaseSchema `
                    -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha } |
                Should -Throw '*Domain collection release metadata is invalid*'
            [IO.File]::ReadAllBytes($fixture.metadataPath) | Should -Be $original
            Should -Invoke Get-ProductionDomainCollectionHistoricalReleaseSchema -Times 0 -Exactly
            Should -Invoke Write-ProductionDomainCollectionProtectedJson -Times 0 -Exactly
        }

        It 'preserves modern release metadata bytes without requiring an executable JAR' {
            $sha = '3' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'modern-target' -Sha $sha -DomainSchema 'TARGET'
            $original = [IO.File]::ReadAllBytes($fixture.metadataPath)
            Mock Write-ProductionDomainCollectionProtectedJson {
                throw 'modern metadata must not be republished'
            }

            (Get-ProductionDomainCollectionReleaseSchema `
                -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha) |
                Should -BeExactly 'TARGET'
            [IO.File]::ReadAllBytes($fixture.metadataPath) | Should -Be $original
            Should -Invoke Write-ProductionDomainCollectionProtectedJson -Times 0 -Exactly
        }

        It 'fails closed for invalid ordered metadata without changing original bytes' -ForEach @(
            @{ Name='wrong-sha'; Metadata='{"sha":"ffffffffffffffffffffffffffffffffffffffff","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY"}' }
            @{ Name='wrong-source'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"upstream/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY"}' }
            @{ Name='null-source'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":null,"builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY"}' }
            @{ Name='mistyped-source'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":1,"builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY"}' }
            @{ Name='wrong-timestamp'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"later","musicSchema":"LEGACY"}' }
            @{ Name='null-timestamp'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":null,"musicSchema":"LEGACY"}' }
            @{ Name='mistyped-timestamp'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":1,"musicSchema":"LEGACY"}' }
            @{ Name='wrong-music-schema'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"UNKNOWN"}' }
            @{ Name='null-music-schema'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":null}' }
            @{ Name='mistyped-music-schema'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":1}' }
            @{ Name='extra-property'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY","domainSchema":"LEGACY","extra":"x"}' }
            @{ Name='reordered-property'; Metadata='{"source":"origin/main","sha":"4444444444444444444444444444444444444444","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY"}' }
            @{ Name='null-domain-schema'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY","domainSchema":null}' }
            @{ Name='mistyped-domain-schema'; Metadata='{"sha":"4444444444444444444444444444444444444444","source":"origin/main","builtAt":"2026-08-12T00:00:00.0000000Z","musicSchema":"LEGACY","domainSchema":1}' }
        ) {
            $sha = '4' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name $Name -Sha $sha -Metadata $Metadata
            $original = [IO.File]::ReadAllBytes($fixture.metadataPath)

            { Get-ProductionDomainCollectionReleaseSchema `
                    -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha } |
                Should -Throw '*Domain collection release metadata is invalid*'
            [IO.File]::ReadAllBytes($fixture.metadataPath) | Should -Be $original
        }

        It 'fails closed when the historical executable JAR is missing corrupt or lacks the application class' -ForEach @(
            @{ Name='missing-jar'; Setup={} }
            @{ Name='corrupt-jar'; Setup={ param($Release) [IO.File]::WriteAllText((Join-Path $Release 'app.jar'), 'not-a-jar') } }
            @{ Name='missing-application-class'; Setup={ param($Release)
                Add-Type -AssemblyName System.IO.Compression
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                $archive = [IO.Compression.ZipFile]::Open((Join-Path $Release 'app.jar'), [IO.Compression.ZipArchiveMode]::Create)
                try { $archive.CreateEntry('BOOT-INF/classes/other.class') | Out-Null } finally { $archive.Dispose() }
            } }
        ) {
            $sha = '5' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture -Name $Name -Sha $sha
            & $Setup $fixture.release
            $original = [IO.File]::ReadAllBytes($fixture.metadataPath)

            { Get-ProductionDomainCollectionReleaseSchema `
                    -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha } |
                Should -Throw '*Domain collection release metadata is invalid*'
            [IO.File]::ReadAllBytes($fixture.metadataPath) | Should -Be $original
        }

        It 'preserves historical metadata when protected publication faults' {
            $sha = '6' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'publication-fault' -Sha $sha
            New-DomainCollectionReleaseJarFixture -Release $fixture.release -IncludesV015 $true
            $original = [IO.File]::ReadAllBytes($fixture.metadataPath)
            Mock Write-ProductionDomainCollectionProtectedJson { throw 'publication fault' }

            { Get-ProductionDomainCollectionReleaseSchema `
                    -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha } |
                Should -Throw '*Domain collection release metadata is invalid*'
            [IO.File]::ReadAllBytes($fixture.metadataPath) | Should -Be $original
        }

        It 'rejects an unchanged historical readback after protected publication' {
            $sha = '7' * 40
            $fixture = New-DomainCollectionReleaseMetadataFixture `
                -Name 'readback-mismatch' -Sha $sha
            New-DomainCollectionReleaseJarFixture -Release $fixture.release -IncludesV015 $false
            $original = [IO.File]::ReadAllBytes($fixture.metadataPath)
            Mock Write-ProductionDomainCollectionProtectedJson { }

            { Get-ProductionDomainCollectionReleaseSchema `
                    -Config $script:releaseMetadataConfig -Release $fixture.release -Sha $sha } |
                Should -Throw '*Domain collection release metadata is invalid*'
            [IO.File]::ReadAllBytes($fixture.metadataPath) | Should -Be $original
        }

        It 'requires exact cutover confirmation before config lock backup or database effects' {
            { Invoke-ProductionDomainCollectionCutover } |
                Should -Throw '*requires explicit confirmation*'

            Should -Invoke Read-ProductionConfig -Times 0
            Should -Invoke Enter-ProductionFixedRootDeploymentLock -Times 0
            Should -Invoke New-ProductionDomainCollectionCutoverContext -Times 0
        }

        It 'owns one lock through backup candidate stop publish target verification deletion marker recovery and auto refresh' {
            Invoke-ProductionDomainCollectionCutover -Confirm

            $script:events | Should -Be @(
                'lock:acquire',
                'stop-suspended',
                'backup-and-evidence',
                'candidate',
                'root:recheck',
                'stage-publish',
                'target-cutover-prepare',
                'root:recheck',
                'stopped-target-proof',
                'drop-legacy',
                'marker-recovery-auto',
                'lock:release')
            Should -Invoke Enter-ProductionFixedRootDeploymentLock -Times 1 -Exactly
            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 1 -Exactly
        }

        It 'restarts the exact legacy writer when stopped snapshot initialization fails' {
            $script:exerciseRealCutoverContext = $true
            $targetRelease = '2' * 40
            $legacyRelease = '1' * 40
            $targetPath = Join-Path $TestDrive "releases\$targetRelease"
            $legacyPath = Join-Path $TestDrive "releases\$legacyRelease"
            New-Item -ItemType Directory -Path $targetPath,$legacyPath -Force |
                Out-Null
            Mock Get-ProductionDomainCollectionTerminalReinitializationState { $null }
            Mock Resolve-OriginMainRelease { $targetRelease }
            Mock New-ReleaseFromOriginMain { $targetPath }
            Mock Get-ProductionDomainCollectionReleaseSchema {
                if ($Sha -ceq $targetRelease) { return 'TARGET' }
                if ($Sha -ceq $legacyRelease) { return 'LEGACY' }
                throw 'unexpected release'
            }
            Mock Get-JunctionTarget { $legacyPath }
            Mock Get-ProductionDomainCollectionPriorMarkerBase64 { 'cHJpb3I=' }
            Mock New-ProductionDomainCollectionVerifiedBackup {
                [void]$script:events.Add('backup')
                throw 'backup failed'
            }
            Mock Resolve-ProductionDomainCollectionPrepublicationPublication { }
            Mock Read-ProductionDomainSchemaDirection { $null }
            Mock Restore-ProductionDomainCollectionLegacyRelease {
                [void]$script:events.Add('marker:restore')
            }
            Mock Start-ProductionDomainCollectionLegacy {
                [void]$script:events.Add('legacy:start')
            }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$script:events.Add("recovery:$Policy")
            }
            Mock Invoke-ProductionDomainCollectionEngine {
                throw 'preview must not run after backup failure'
            }
            Mock Publish-ProductionDomainCollectionPrepublicationContext {
                throw 'publication must not run after backup failure'
            }

            { New-ProductionDomainCollectionCutoverContext -Config $script:config } |
                Should -Throw '*backup failed*'

            $script:events | Should -Be @(
                'stop-suspended','backup','marker:restore','legacy:start','recovery:Normal')
            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 1 -Exactly
            Should -Invoke Invoke-ProductionDomainCollectionEngine -Times 0 -Exactly
            Should -Invoke Publish-ProductionDomainCollectionPrepublicationContext `
                -Times 0 -Exactly
        }

        It 'keeps the public target writer disabled until legacy deletion completes' {
            $script:publicWriterEnabledBeforeDeletion = $false
            $script:finalTargetVerified = $false
            Mock Prepare-ProductionDomainCollectionTargetCutover {
                & $script:prepareTargetCutoverImplementation -Context $Context
            }
            Mock Write-ProductionDomainSchemaDirection { }
            Mock Save-ProductionDomainCollectionContextState { }
            Mock Switch-ProductionRelease {
                if (-not $script:context.dropStarted) {
                    $script:publicWriterEnabledBeforeDeletion = $true
                } else {
                    $script:finalTargetVerified = $true
                }
            }
            Mock Invoke-ProductionDomainCollectionEngine {
                if ($Action -cne 'verify-live') {
                    throw "unexpected engine action $Action"
                }
                [pscustomobject]@{ complete = $true }
            }
            Mock Invoke-ProductionDomainCollectionDropLegacy {
                if ($script:publicWriterEnabledBeforeDeletion) {
                    throw 'public target writer was enabled before legacy deletion'
                }
                $Context.dropStarted = $true
            }
            Mock Complete-ProductionDomainCollectionCutover {
                & $script:completeCutoverImplementation -Context $Context
            }
            Mock Set-ProductionWebsiteRecoveryPolicy { }
            Mock Update-ProductionAutoDeployToolsUnderHeldLock { }

            { Invoke-ProductionDomainCollectionCutover -Confirm } |
                Should -Not -Throw

            $script:publicWriterEnabledBeforeDeletion | Should -BeFalse
            $script:context.dropStarted | Should -BeTrue
            $script:finalTargetVerified | Should -BeTrue
        }

        It 'continues to deletion when the stopped target evidence remains exact' {
            Mock Invoke-ProductionDomainCollectionEngine {
                if ($Action -cne 'verify-live') {
                    throw "unexpected engine action $Action"
                }
                [pscustomobject]@{ complete = $true }
            }

            Invoke-ProductionDomainCollectionCutover -Confirm

            $script:context.dropStarted | Should -BeTrue
            Should -Invoke Invoke-ProductionDomainCollectionDropLegacy -Times 1 -Exactly
            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery -Times 0
        }

        It 'recovers a persisted prepublication attempt under the same cutover lock' {
            $script:exerciseRealRetryResolver = $true
            Mock Resolve-ProductionDomainCollectionPrepublicationPublication { }
            Mock Read-ProductionDomainSchemaDirection {
                [pscustomobject]@{ state = 'ROLLBACK_IN_PROGRESS' }
            }
            Mock Read-ProductionDomainCollectionProtectedState {
                [pscustomobject]@{
                    state = 'PREVIEWED'
                    legacyDropped = $false
                }
            }
            Mock Invoke-ProductionDomainCollectionFailureRecovery {
                [void]$script:events.Add('recover-persisted-prepublication')
            }

            Invoke-ProductionDomainCollectionCutover -Confirm

            $script:events.IndexOf('lock:acquire') | Should -BeLessThan `
                $script:events.IndexOf('recover-persisted-prepublication')
            $script:events.IndexOf('recover-persisted-prepublication') |
                Should -BeLessThan $script:events.IndexOf('backup-and-evidence')
            $script:events.IndexOf('backup-and-evidence') | Should -BeLessThan `
                $script:events.IndexOf('lock:release')
            Should -Invoke Enter-ProductionFixedRootDeploymentLock -Times 1 -Exactly
        }

        It 'contains a pre-drop failure without running deletion or post-drop restore' {
            Mock Invoke-ProductionDomainCollectionStageAndPublish {
                [void]$script:events.Add('stage-publish')
                throw 'publish failed'
            }

            { Invoke-ProductionDomainCollectionCutover -Confirm } |
                Should -Throw '*publish failed*'

            Should -Invoke Invoke-ProductionDomainCollectionDropLegacy -Times 0
            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery -Times 1 -Exactly `
                -ParameterFilter { -not $PostDrop }
            $script:events[-1] | Should -Be 'lock:release'
        }

        It 'recovers exact prepublication state when candidate proof fails' {
            Mock Invoke-ProductionDomainCollectionCandidateProof { throw 'candidate failed' }

            { Invoke-ProductionDomainCollectionCutover -Confirm } |
                Should -Throw '*candidate failed*'

            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 1 -Exactly
            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery -Times 1 -Exactly `
                -ParameterFilter { -not $PostDrop }
            Should -Invoke Invoke-ProductionDomainCollectionDropLegacy -Times 0
            $script:events | Should -Contain 'recover:False'
        }

        It 'treats any failure after the first deletion intent as restore-bound recovery' {
            Mock Invoke-ProductionDomainCollectionDropLegacy {
                $Context.dropStarted = $true
                [void]$script:events.Add('drop-legacy')
                throw 'drop result lost'
            }

            { Invoke-ProductionDomainCollectionCutover -Confirm } |
                Should -Throw '*drop result lost*'

            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery -Times 1 -Exactly `
                -ParameterFilter { $PostDrop }
            $script:events.IndexOf('drop-legacy') |
                Should -BeLessThan $script:events.IndexOf('recover:True')
        }

        It 'keeps preview read-only while holding and rechecking the fixed-root lock' {
            Mock New-ProductionDomainCollectionPreviewContext {
                [void]$script:events.Add('preview-context')
                [pscustomobject]@{ config = $script:config }
            }
            Mock Invoke-ProductionDomainCollectionPreviewAction {
                [void]$script:events.Add('preview-read')
                [pscustomobject]@{ complete = $true; action = 'preview' }
            }

            $result = Get-ProductionDomainCollectionPreview

            $result.action | Should -Be 'preview'
            $script:events | Should -Be @(
                'lock:acquire','preview-context','root:recheck','preview-read','lock:release')
            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 0
            Should -Invoke Invoke-ProductionDomainCollectionStageAndPublish -Times 0
            Should -Invoke Invoke-ProductionDomainCollectionDropLegacy -Times 0
        }

        It 'publishes a restart-readable first-ever prepublication pair before candidate work' {
            $script:exerciseRealCutoverContext = $true
            $root = Join-Path $TestDrive 'first-prepublication'
            $stateRoot = Join-Path $root 'state'
            $backupRoot = Join-Path $root 'backups'
            $releaseRoot = Join-Path $root 'releases'
            $legacyRelease = '1' * 40
            $targetRelease = '2' * 40
            $legacyPath = Join-Path $releaseRoot $legacyRelease
            $targetPath = Join-Path $releaseRoot $targetRelease
            New-Item -ItemType Directory `
                -Path $stateRoot,$backupRoot,$legacyPath,$targetPath -Force |
                Out-Null
            $archive = Join-Path $backupRoot 'fresh.archive.gz'
            Set-Content -LiteralPath $archive -Value 'fresh-backup' -NoNewline
            $evidenceDigest = 'a' * 64
            $backupIdentity = 'b' * 64
            $candidate = 'cbell_candidate_' + ('c' * 12) + '_' + ('d' * 24)
            $schemaCalls = [Collections.Generic.List[string]]::new()
            $config = [pscustomobject]@{
                programDataRoot = $root
                backupRoot = $backupRoot
                candidatePort = 18081
                productionPort = 18080
                repositoryPath = $root
            }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }
            Mock Resolve-OriginMainRelease { $targetRelease }
            Mock New-ReleaseFromOriginMain { $targetPath }
            Mock Get-JunctionTarget { $legacyPath }
            Mock Get-ProductionDomainCollectionReleaseSchema {
                [void]$schemaCalls.Add($Sha)
                if ($Sha -eq $legacyRelease) { 'LEGACY' } else { 'TARGET' }
            }
            Mock New-ProductionDomainCollectionVerifiedBackup {
                [pscustomobject]@{
                    archive = $archive
                    backupIdentity = $backupIdentity
                }
            }
            Mock Invoke-ProductionDomainCollectionEngine {
                [pscustomobject]@{
                    evidenceDigest = $evidenceDigest
                    evidence = [pscustomobject]@{ version = 1 }
                }
            }
            Mock New-CandidateDatabaseName { $candidate }
            Mock Assert-ProductionDomainCollectionCandidateIsolation { }

            $context = New-ProductionDomainCollectionCutoverContext -Config $config

            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            $statePath = Join-Path $stateRoot 'domain-collection-cutover.json'
            $bindingPath = @((Get-ChildItem -LiteralPath $stateRoot `
                        -Filter 'domain-collection-prepublication.*.json').FullName)
            $bindingPath.Count | Should -Be 1
            $bindingPath = $bindingPath[0]
            $pointerPath = Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json'
            Test-Path -LiteralPath $bindingPath | Should -BeTrue -Because (
                'the durable files are ' +
                (@(Get-ChildItem -LiteralPath $stateRoot).Name -join ','))
            $env:CBELL_PREPUB_MARKER = $markerPath
            $env:CBELL_PREPUB_STATE = $statePath
            $env:CBELL_PREPUB_BINDING = $bindingPath
            try {
                $restartJson = & (Get-Process -Id $PID).Path `
                    -NoProfile -NonInteractive -Command `
                    '$marker=Get-Content -LiteralPath $env:CBELL_PREPUB_MARKER -Raw | ConvertFrom-Json; $state=Get-Content -LiteralPath $env:CBELL_PREPUB_STATE -Raw | ConvertFrom-Json; $binding=Get-Content -LiteralPath $env:CBELL_PREPUB_BINDING -Raw | ConvertFrom-Json; [pscustomobject]@{marker=$marker.state;state=$state.state;bindingTarget=$binding.targetRelease;priorState=$binding.priorStateSha256}|ConvertTo-Json -Compress'
            } finally {
                Remove-Item Env:CBELL_PREPUB_MARKER -ErrorAction SilentlyContinue
                Remove-Item Env:CBELL_PREPUB_STATE -ErrorAction SilentlyContinue
                Remove-Item Env:CBELL_PREPUB_BINDING -ErrorAction SilentlyContinue
            }
            $LASTEXITCODE | Should -Be 0
            $restart = ([string]$restartJson).Trim() | ConvertFrom-Json
            $restart.marker | Should -BeExactly 'ROLLBACK_IN_PROGRESS'
            $restart.state | Should -BeExactly 'PREVIEWED'
            $restart.bindingTarget | Should -BeExactly $targetRelease
            $restart.priorState | Should -BeExactly ''
            Test-Path -LiteralPath $pointerPath | Should -BeFalse
            $context.state | Should -BeExactly 'PREVIEWED'
            $schemaCalls | Should -Be @($targetRelease,$legacyRelease)
            Should -Invoke Get-ProductionDomainCollectionReleaseSchema -Times 2 -Exactly `
                -ParameterFilter { $Config -eq $config }
        }

        It 'restores an exact existing schema marker after first-cutover publication crashes' {
            $root = Join-Path $TestDrive 'first-cutover-existing-marker'
            $stateRoot = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }
            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            [ordered]@{
                version = 1
                state = 'TARGET_ACTIVE'
                updatedAtEpochMillis = 1
                targetRelease = '2' * 40
                legacyRelease = '1' * 40
            } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
            $markerBytes = [IO.File]::ReadAllBytes($markerPath)
            $context = [pscustomobject]@{
                config = $config
                state = 'INITIALIZED'
                targetRelease = '2' * 40
                currentRelease = '1' * 40
                legacyRelease = '1' * 40
                archive = (Join-Path $root 'fresh.archive.gz')
                backupIdentity = 'a' * 64
                evidenceDigest = 'b' * 64
                evidenceFile = (Join-Path $stateRoot 'evidence.json')
                evidenceFileSha256 = 'c' * 64
                ownerToken = 'd' * 32
                candidateDatabase = 'cbell_candidate_' + ('e' * 12) + '_' + ('f' * 24)
                priorMarkerBase64 = Get-ProductionDomainCollectionPriorMarkerBase64 `
                    -Config $config
                priorStateSha256 = ''
                historyFile = ''
                dropStarted = $false
            }
            Mock Save-ProductionDomainCollectionContextState {
                throw 'simulated state publication crash'
            }

            { Publish-ProductionDomainCollectionPrepublicationContext `
                    -Context $context } |
                Should -Throw '*simulated state publication crash*'
            (Get-Content -LiteralPath $markerPath -Raw |
                ConvertFrom-Json).state | Should -BeExactly 'ROLLBACK_IN_PROGRESS'

            Resolve-ProductionDomainCollectionPrepublicationPublication `
                -Config $config

            [Convert]::ToBase64String([IO.File]::ReadAllBytes($markerPath)) |
                Should -BeExactly ([Convert]::ToBase64String($markerBytes))
            Test-Path -LiteralPath (Join-Path $stateRoot `
                'domain-collection-cutover.json') | Should -BeFalse
            Test-Path -LiteralPath (Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json') |
                Should -BeFalse
        }

        It 'rejects a malformed first-cutover marker before capture' {
            $root = Join-Path $TestDrive 'first-cutover-malformed-marker'
            $stateRoot = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            $original = '{"version":1,"state":"TARGET"}'
            Set-Content -LiteralPath $markerPath -Value $original -NoNewline
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }

            { Get-ProductionDomainCollectionPriorMarkerBase64 -Config $config } |
                Should -Throw '*Music runtime schema-direction marker is invalid*'

            Get-Content -LiteralPath $markerPath -Raw | Should -BeExactly $original
        }

        It 'allows preview only from an exact terminal legacy rollback state' {
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
            }
            $legacyPath = 'C:\ProgramData\christopherbell.dev\releases\' + ('1' * 40)
            $script:terminalMarker = [pscustomobject]@{
                version = 2
                state = 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
                targetRelease = '2' * 40
                currentRelease = '2' * 40
                legacyRelease = '1' * 40
                manifestDigest = $script:ManifestDigest
                evidenceDigest = 'a' * 64
                backupIdentity = 'b' * 64
                legacyDropped = $false
            }
            Mock Read-ProductionDomainSchemaDirection { $script:terminalMarker }
            Mock Read-ProductionDomainCollectionProtectedState {
                [pscustomobject]@{
                    config = $config
                    state = 'ROLLED_BACK'
                    targetRelease = '2' * 40
                    legacyRelease = '1' * 40
                    evidenceDigest = 'a' * 64
                    backupIdentity = 'b' * 64
                }
            }
            Mock Get-JunctionTarget { $legacyPath }
            Mock Assert-ReleasePath { $Path }
            Mock Get-ProductionDomainCollectionReleaseSchema { 'LEGACY' }

            $context = New-ProductionDomainCollectionPreviewContext -Config $config

            $context.release | Should -BeExactly ('1' * 40)

            $script:terminalMarker.state = 'TARGET_ACTIVE'
            { New-ProductionDomainCollectionPreviewContext -Config $config } |
                Should -Throw '*terminal*legacy*'
        }

        It 'archives exact terminal history before publishing a fresh cutover context' {
            $script:exerciseRealCutoverContext = $true
            $root = Join-Path $TestDrive 'future-cutover'
            $stateRoot = Join-Path $root 'state'
            $releaseRoot = Join-Path $root 'releases'
            $script:futureLegacyRelease = '1' * 40
            $script:futureOldTarget = '2' * 40
            $script:futureNewTarget = '3' * 40
            $script:futureLegacyPath = Join-Path $releaseRoot $script:futureLegacyRelease
            $script:futureTargetPath = Join-Path $releaseRoot $script:futureNewTarget
            New-Item -ItemType Directory `
                -Path $stateRoot,$script:futureLegacyPath,$script:futureTargetPath -Force |
                Out-Null
            $statePath = Join-Path $stateRoot 'domain-collection-cutover.json'
            $oldStateJson = '{"version":1,"state":"ROLLED_BACK","history":"exact"}'
            Set-Content -LiteralPath $statePath -Value $oldStateJson -NoNewline
            $config = [pscustomobject]@{
                programDataRoot = $root
                backupRoot = (Join-Path $root 'backups')
                candidatePort = 18081
                productionPort = 18080
                repositoryPath = $root
            }
            $script:futureMarker = [pscustomobject]@{
                version = 2
                state = 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
                updatedAtEpochMillis = 1
                targetRelease = $script:futureOldTarget
                currentRelease = $script:futureOldTarget
                legacyRelease = $script:futureLegacyRelease
                manifestDigest = $script:ManifestDigest
                evidenceDigest = 'a' * 64
                backupIdentity = 'b' * 64
                legacyDropped = $false
            }
            $futureMarkerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            $script:futureMarker | ConvertTo-Json |
                Set-Content -LiteralPath $futureMarkerPath -Encoding utf8
            $priorMarkerBase64 = [Convert]::ToBase64String(
                [IO.File]::ReadAllBytes($futureMarkerPath))
            $script:futureTerminal = [pscustomobject]@{
                config = $config
                state = 'ROLLED_BACK'
                targetRelease = $script:futureOldTarget
                legacyRelease = $script:futureLegacyRelease
                backupIdentity = 'b' * 64
                evidenceDigest = 'a' * 64
            }
            $script:futureArchive = Join-Path $config.backupRoot 'new.archive.gz'
            $script:futureBackupIdentity = 'c' * 64
            $script:futureEvidenceDigest = 'd' * 64
            $script:futureCandidate = 'cbell_candidate_' + ('3' * 12) + '_' + ('4' * 24)
            Mock Read-ProductionDomainCollectionProtectedState { $script:futureTerminal }
            Mock Get-JunctionTarget { $script:futureLegacyPath }
            Mock Assert-ReleasePath { $Path }
            Mock Resolve-OriginMainRelease { $script:futureNewTarget }
            Mock New-ReleaseFromOriginMain { $script:futureTargetPath }
            Mock Get-ProductionDomainCollectionReleaseSchema {
                if ($Sha -eq $script:futureLegacyRelease) { 'LEGACY' } else { 'TARGET' }
            }
            Mock New-ProductionDomainCollectionVerifiedBackup {
                [pscustomobject]@{
                    archive = $script:futureArchive
                    backupIdentity = $script:futureBackupIdentity
                }
            }
            Mock Invoke-ProductionDomainCollectionEngine {
                [pscustomobject]@{
                    evidenceDigest = $script:futureEvidenceDigest
                    evidence = [pscustomobject]@{ version = 1 }
                }
            }
            Mock Write-ProductionDomainCollectionProtectedJson {
                New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force |
                    Out-Null
                $jsonDepth = if ([int]$Depth -ge 1) { [int]$Depth } else { 20 }
                $Value | ConvertTo-Json -Depth $jsonDepth |
                    Set-Content -LiteralPath $Path -NoNewline
            }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }
            Mock New-CandidateDatabaseName { $script:futureCandidate }
            Mock Assert-ProductionDomainCollectionCandidateIsolation { }

            $context = New-ProductionDomainCollectionCutoverContext -Config $config

            $context.targetRelease | Should -BeExactly $script:futureNewTarget
            $context.legacyRelease | Should -BeExactly $script:futureLegacyRelease
            $context.backupIdentity | Should -BeExactly $script:futureBackupIdentity
            $context.evidenceDigest | Should -BeExactly $script:futureEvidenceDigest
            $context.candidateDatabase | Should -BeExactly $script:futureCandidate
            $context.ownerToken | Should -Match '^[0-9a-f]{32}$'
            $context.ownerToken | Should -Not -BeExactly ('0' * 32)
            $history = @(Get-ChildItem -LiteralPath (Join-Path $stateRoot 'history') -File)
            $history.Count | Should -Be 1
            (Get-Content -LiteralPath $history[0].FullName -Raw) |
                Should -BeExactly $oldStateJson
            $newState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            $newState.state | Should -BeExactly 'PREVIEWED'
            $newState.backupIdentity | Should -BeExactly $script:futureBackupIdentity
            $newState.evidenceDigest | Should -BeExactly $script:futureEvidenceDigest
            $newState.ownerToken | Should -BeExactly $context.ownerToken
            $newState.candidateDatabase | Should -BeExactly $script:futureCandidate
            $marker = Get-Content -LiteralPath $futureMarkerPath -Raw |
                ConvertFrom-Json
            $marker.state | Should -BeExactly 'ROLLBACK_IN_PROGRESS'
            $marker.targetRelease | Should -BeExactly $script:futureNewTarget
            $marker.currentRelease | Should -BeExactly $script:futureLegacyRelease
            $marker.evidenceDigest | Should -BeExactly $script:futureEvidenceDigest
            $marker.backupIdentity | Should -BeExactly $script:futureBackupIdentity
            $oldStateHash = (Get-FileHash -LiteralPath $history[0].FullName `
                -Algorithm SHA256).Hash.ToLowerInvariant()
            $bindingPath = @((Get-ChildItem -LiteralPath $stateRoot `
                        -Filter 'domain-collection-prepublication.*.json').FullName)
            $bindingPath.Count | Should -Be 1
            $bindingPath = $bindingPath[0]
            $binding = Get-Content -LiteralPath $bindingPath -Raw |
                ConvertFrom-Json
            $binding.priorMarkerBase64 | Should -BeExactly $priorMarkerBase64
            $binding.priorStateSha256 | Should -BeExactly $oldStateHash
            [IO.Path]::GetFullPath([string]$binding.historyFile) |
                Should -BeExactly ([IO.Path]::GetFullPath($history[0].FullName))
            Test-Path -LiteralPath (Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json') |
                Should -BeFalse
        }

        It 'restores an absent first-ever marker after marker-first publication crashes' {
            $root = Join-Path $TestDrive 'first-marker-crash'
            $stateRoot = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            $context = [pscustomobject]@{
                config = $config
                state = 'INITIALIZED'
                targetRelease = '2' * 40
                currentRelease = '1' * 40
                legacyRelease = '1' * 40
                archive = (Join-Path $root 'fresh.archive.gz')
                backupIdentity = 'a' * 64
                evidenceDigest = 'b' * 64
                evidenceFile = (Join-Path $stateRoot 'evidence.json')
                evidenceFileSha256 = 'c' * 64
                ownerToken = 'd' * 32
                candidateDatabase = 'cbell_candidate_' + ('e' * 12) + '_' + ('f' * 24)
                priorMarkerBase64 = ''
                priorStateSha256 = ''
                historyFile = ''
                dropStarted = $false
            }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }
            Mock Save-ProductionDomainCollectionContextState {
                throw 'simulated state publication crash'
            }

            { Publish-ProductionDomainCollectionPrepublicationContext `
                    -Context $context } |
                Should -Throw '*simulated state publication crash*'

            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            $statePath = Join-Path $stateRoot 'domain-collection-cutover.json'
            $pointerPath = Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json'
            (Get-Content -LiteralPath $markerPath -Raw |
                ConvertFrom-Json).state | Should -BeExactly 'ROLLBACK_IN_PROGRESS'
            Test-Path -LiteralPath $statePath | Should -BeFalse
            Test-Path -LiteralPath $pointerPath | Should -BeTrue

            Resolve-ProductionDomainCollectionPrepublicationPublication `
                -Config $config

            Test-Path -LiteralPath $markerPath | Should -BeFalse
            Test-Path -LiteralPath $statePath | Should -BeFalse
            Test-Path -LiteralPath $pointerPath | Should -BeFalse
            @(Get-ChildItem -LiteralPath $stateRoot `
                    -Filter 'domain-collection-prepublication.*.json').Count | Should -Be 1
        }

        It 'keeps repeated evidence snapshots isolated by owner identity' {
            $root = Join-Path $TestDrive 'binding-owner-isolation'
            New-Item -ItemType Directory -Path (Join-Path $root 'state') -Force |
                Out-Null
            $base = @{
                config = [pscustomobject]@{ programDataRoot = $root }
                targetRelease = '2' * 40
                legacyRelease = '1' * 40
                backupIdentity = 'a' * 64
                evidenceDigest = 'b' * 64
                evidenceFileSha256 = 'c' * 64
                priorMarkerBase64 = ''
                priorStateSha256 = ''
                historyFile = ''
            }
            $first = [pscustomobject]$base.Clone()
            $first | Add-Member NoteProperty ownerToken ('1' * 32)
            $first | Add-Member NoteProperty candidateDatabase `
                ('cbell_candidate_' + ('2' * 12) + '_' + ('3' * 24))
            $second = [pscustomobject]$base.Clone()
            $second | Add-Member NoteProperty ownerToken ('4' * 32)
            $second | Add-Member NoteProperty candidateDatabase `
                ('cbell_candidate_' + ('5' * 12) + '_' + ('6' * 24))
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }

            $firstBinding = Write-ProductionDomainCollectionPrepublicationBinding `
                -Context $first
            $secondBinding = Write-ProductionDomainCollectionPrepublicationBinding `
                -Context $second

            $firstBinding.path | Should -Not -BeExactly $secondBinding.path
            Test-Path -LiteralPath $firstBinding.path | Should -BeTrue
            Test-Path -LiteralPath $secondBinding.path | Should -BeTrue
        }

        It 'restores exact terminal marker and history after marker-first publication crashes' {
            $root = Join-Path $TestDrive 'terminal-marker-crash'
            $stateRoot = Join-Path $root 'state'
            $historyRoot = Join-Path $stateRoot 'history'
            New-Item -ItemType Directory -Path $historyRoot -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            $statePath = Join-Path $stateRoot 'domain-collection-cutover.json'
            $priorMarker = [ordered]@{
                version = 2
                state = 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
                updatedAtEpochMillis = 1
                targetRelease = '2' * 40
                currentRelease = '2' * 40
                legacyRelease = '1' * 40
                manifestDigest = $script:ManifestDigest
                evidenceDigest = 'a' * 64
                backupIdentity = 'b' * 64
                legacyDropped = $false
            }
            $priorMarker | ConvertTo-Json |
                Set-Content -LiteralPath $markerPath -Encoding utf8
            $priorMarkerBytes = [IO.File]::ReadAllBytes($markerPath)
            $priorStateJson = '{"version":1,"state":"ROLLED_BACK","history":"exact"}'
            Set-Content -LiteralPath $statePath -Value $priorStateJson -NoNewline
            $priorStateSha = (Get-FileHash -LiteralPath $statePath -Algorithm SHA256).
                Hash.ToLowerInvariant()
            $historyFile = Join-Path $historyRoot `
                "domain-collection-cutover.$priorStateSha.json"
            Copy-Item -LiteralPath $statePath -Destination $historyFile
            $context = [pscustomobject]@{
                config = $config
                state = 'INITIALIZED'
                targetRelease = '3' * 40
                currentRelease = '1' * 40
                legacyRelease = '1' * 40
                archive = (Join-Path $root 'fresh.archive.gz')
                backupIdentity = 'c' * 64
                evidenceDigest = 'd' * 64
                evidenceFile = (Join-Path $stateRoot 'evidence.json')
                evidenceFileSha256 = 'e' * 64
                ownerToken = 'f' * 32
                candidateDatabase = 'cbell_candidate_' + ('3' * 12) + '_' + ('4' * 24)
                priorMarkerBase64 = [Convert]::ToBase64String($priorMarkerBytes)
                priorStateSha256 = $priorStateSha
                historyFile = $historyFile
                dropStarted = $false
            }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }
            Mock Save-ProductionDomainCollectionContextState {
                throw 'simulated state publication crash'
            }

            { Publish-ProductionDomainCollectionPrepublicationContext `
                    -Context $context } |
                Should -Throw '*simulated state publication crash*'
            Resolve-ProductionDomainCollectionPrepublicationPublication `
                -Config $config

            [Convert]::ToBase64String([IO.File]::ReadAllBytes($markerPath)) |
                Should -BeExactly $context.priorMarkerBase64
            (Get-Content -LiteralPath $statePath -Raw) |
                Should -BeExactly $priorStateJson
            (Get-FileHash -LiteralPath $historyFile -Algorithm SHA256).
                Hash.ToLowerInvariant() | Should -BeExactly $priorStateSha
            Test-Path -LiteralPath (Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json') |
                Should -BeFalse
        }

        It 'rejects tampered prepublication binding or marker without restoration' {
            $root = Join-Path $TestDrive 'tampered-marker-crash'
            $stateRoot = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            $context = [pscustomobject]@{
                config = $config
                state = 'INITIALIZED'
                targetRelease = '2' * 40
                currentRelease = '1' * 40
                legacyRelease = '1' * 40
                archive = (Join-Path $root 'fresh.archive.gz')
                backupIdentity = 'a' * 64
                evidenceDigest = 'b' * 64
                evidenceFile = (Join-Path $stateRoot 'evidence.json')
                evidenceFileSha256 = 'c' * 64
                ownerToken = 'd' * 32
                candidateDatabase = 'cbell_candidate_' + ('e' * 12) + '_' + ('f' * 24)
                priorMarkerBase64 = ''
                priorStateSha256 = ''
                historyFile = ''
                dropStarted = $false
            }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Assert-ProductionPathNotReparse { }
            Mock Save-ProductionDomainCollectionContextState {
                throw 'simulated state publication crash'
            }
            { Publish-ProductionDomainCollectionPrepublicationContext `
                    -Context $context } | Should -Throw
            $bindingPath = @((Get-ChildItem -LiteralPath $stateRoot `
                        -Filter 'domain-collection-prepublication.*.json').FullName)
            $bindingPath.Count | Should -Be 1
            $bindingPath = $bindingPath[0]
            $bindingBytes = [IO.File]::ReadAllBytes($bindingPath)
            Add-Content -LiteralPath $bindingPath -Value 'tampered'

            { Resolve-ProductionDomainCollectionPrepublicationPublication `
                    -Config $config } |
                Should -Throw '*binding*'

            (Get-Content -LiteralPath (
                    Get-ProductionMusicSchemaDirectionPath -Config $config) -Raw |
                ConvertFrom-Json).state | Should -BeExactly 'ROLLBACK_IN_PROGRESS'
            Test-Path -LiteralPath (Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json') |
                Should -BeTrue

            [IO.File]::WriteAllBytes($bindingPath,$bindingBytes)
            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $config
            $marker = Get-Content -LiteralPath $markerPath -Raw |
                ConvertFrom-Json
            $marker.backupIdentity = '9' * 64
            $marker | ConvertTo-Json |
                Set-Content -LiteralPath $markerPath -Encoding utf8

            { Resolve-ProductionDomainCollectionPrepublicationPublication `
                    -Config $config } |
                Should -Throw '*exact prior or committed*'

            Test-Path -LiteralPath $markerPath | Should -BeTrue
            Test-Path -LiteralPath (Join-Path $stateRoot `
                'domain-collection-prepublication-reconciliation.json') |
                Should -BeTrue
        }

        It 'recovers only an exact persisted prepublication state on cutover retry' {
            $script:exerciseRealRetryResolver = $true
            $config = [pscustomobject]@{ programDataRoot = 'C:\fixed' }
            $script:retryMarker = [pscustomobject]@{
                state = 'ROLLBACK_IN_PROGRESS'
            }
            $script:retryState = [pscustomobject]@{
                state = 'PREVIEWED'
                legacyDropped = $false
            }
            Mock Resolve-ProductionDomainCollectionPrepublicationPublication { }
            Mock Read-ProductionDomainSchemaDirection { $script:retryMarker }
            Mock Read-ProductionDomainCollectionProtectedState { $script:retryState }
            Mock Invoke-ProductionDomainCollectionFailureRecovery { }

            Resolve-ProductionDomainCollectionPrepublicationForCutoverRetry `
                -Config $config

            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery `
                -Times 1 -Exactly -ParameterFilter { -not $PostDrop }

            $script:retryState.state = 'ROLLBACK_VERIFIED'
            { Resolve-ProductionDomainCollectionPrepublicationForCutoverRetry `
                    -Config $config } |
                Should -Throw '*non-prepublication recovery state*'

            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery `
                -Times 1 -Exactly
        }

        It 'rejects unsafe terminal reinitialization before backup or publication' {
            $script:exerciseRealCutoverContext = $true
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
            }
            $legacyRelease = '1' * 40
            $legacyPath = Join-Path $config.programDataRoot "releases\$legacyRelease"
            $script:reinitializationMarker = [pscustomobject]@{
                version = 2
                state = 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
                targetRelease = '2' * 40
                currentRelease = '2' * 40
                legacyRelease = $legacyRelease
                evidenceDigest = 'a' * 64
                backupIdentity = 'b' * 64
                legacyDropped = $false
            }
            $script:reinitializationState = [pscustomobject]@{
                state = 'ROLLED_BACK'
                targetRelease = '2' * 40
                legacyRelease = $legacyRelease
                evidenceDigest = 'a' * 64
                backupIdentity = 'b' * 64
                terminalReconciliationAuthorized = $false
            }
            $script:activeLegacyPath = $legacyPath
            $script:activeLegacySchema = 'LEGACY'
            Mock Read-ProductionDomainSchemaDirection { $script:reinitializationMarker }
            Mock Read-ProductionDomainCollectionProtectedState {
                $script:reinitializationState
            }
            Mock Get-JunctionTarget { $script:activeLegacyPath }
            Mock Assert-ReleasePath { $Path }
            Mock Get-ProductionDomainCollectionReleaseSchema {
                $script:activeLegacySchema
            }
            Mock Resolve-OriginMainRelease { throw 'release effect must not run' }
            Mock New-ProductionDomainCollectionVerifiedBackup {
                throw 'backup effect must not run'
            }

            Mock Test-Path {
                $true
            } -ParameterFilter {
                $LiteralPath -like '*domain-collection-cutover.json'
            }

            $savedMarker = $script:reinitializationMarker
            $script:reinitializationMarker = $null
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*state exists without*marker*'
            $script:reinitializationMarker = $savedMarker

            $script:reinitializationMarker.state = 'TARGET_ACTIVE'
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*terminal*legacy*'

            $script:reinitializationMarker.state =
                'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
            $script:reinitializationState.state = 'ROLLBACK_READY'
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*terminal*'

            $script:reinitializationState.state = 'ROLLED_BACK'
            $script:reinitializationState.terminalReconciliationAuthorized = $true
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*requires rollback reconciliation*'

            $script:reinitializationState.terminalReconciliationAuthorized = $false
            $script:activeLegacyPath = Join-Path $config.programDataRoot `
                ('releases\' + ('9' * 40))
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*active legacy release*'

            $script:activeLegacyPath = $legacyPath
            $script:activeLegacySchema = 'TARGET'
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*legacy domain-schema*'

            $script:activeLegacySchema = 'LEGACY'
            $script:reinitializationState.backupIdentity = 'c' * 64
            { New-ProductionDomainCollectionCutoverContext -Config $config } |
                Should -Throw '*identities do not match*'

            Should -Invoke Resolve-OriginMainRelease -Times 0
            Should -Invoke New-ProductionDomainCollectionVerifiedBackup -Times 0
        }
    }
}

Describe 'domain collection rollback and isolated candidate boundaries' {
    InModuleScope Production.DomainCollections {
        It 'requires rollback confirmation before reading protected state' {
            Mock Read-ProductionConfig { throw 'config must not be read' }

            { Invoke-ProductionDomainCollectionRollback } |
                Should -Throw '*requires explicit confirmation*'

            Should -Invoke Read-ProductionConfig -Times 0
        }

        It 'keeps rollback marker deletion identity exact across restore' {
            $state = [pscustomobject]@{
                config = [pscustomobject]@{}
                targetRelease = '2' * 40
                currentRelease = '3' * 40
                legacyRelease = '1' * 40
                evidenceDigest = 'a' * 64
                backupIdentity = 'b' * 64
                legacyDropped = $false
            }
            Mock Write-ProductionDomainSchemaDirection { }

            Write-ProductionDomainCollectionRollbackMarker `
                -State $state -MarkerState ROLLBACK_IN_PROGRESS
            $state.legacyDropped = $true
            Write-ProductionDomainCollectionRollbackMarker `
                -State $state -MarkerState ROLLBACK_IN_PROGRESS
            Write-ProductionDomainCollectionRollbackMarker `
                -State $state -MarkerState LEGACY_ACTIVE_RECONCILIATION_REQUIRED

            Should -Invoke Write-ProductionDomainSchemaDirection -Times 1 `
                -ParameterFilter {
                    $State -eq 'ROLLBACK_IN_PROGRESS' -and -not $LegacyDropped
                }
            Should -Invoke Write-ProductionDomainSchemaDirection -Times 1 `
                -ParameterFilter {
                    $State -eq 'ROLLBACK_IN_PROGRESS' -and $LegacyDropped
                }
            Should -Invoke Write-ProductionDomainSchemaDirection -Times 1 `
                -ParameterFilter {
                    $State -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED' -and
                    -not $LegacyDropped
                }
        }

        It 'restores the exact backup before selecting or starting the legacy writer after deletion' {
            $events = [Collections.Generic.List[string]]::new()
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
            }
            $rollbackContext = [pscustomobject]@{
                state='TARGET_ACTIVE'
                legacyDropped=$true
            }
            Mock Read-ProductionConfig { $config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock = [IO.MemoryStream]::new(); Boundary = [pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop-suspended') }
            Mock Assert-ProductionDomainCollectionRollbackFreshness { [void]$events.Add('freshness') }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$events.Add("marker:$MarkerState")
            }
            Mock Restore-ProductionDomainCollectionBackup {
                [void]$events.Add('restore-backup')
                $rollbackContext.state = 'LEGACY_DATA_VERIFIED'
                [void]$events.Add('state:LEGACY_DATA_VERIFIED')
            }
            Mock Recover-ProductionDomainCollectionPrepublication { throw 'prepublication recovery must not run' }
            Mock Restore-ProductionDomainCollectionLegacyRelease { throw 'prior marker must not return' }
            Mock Start-ProductionDomainCollectionLegacy { [void]$events.Add('legacy-start') }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$events.Add("recovery:$Policy")
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
                $rollbackContext.state = $State
            }

            Invoke-ProductionDomainCollectionRollback -Confirm

            $events | Should -Be @(
                'stop-suspended','freshness','marker:ROLLBACK_IN_PROGRESS',
                'state:ROLLBACK_VERIFIED','restore-backup','state:LEGACY_DATA_VERIFIED',
                'state:ROLLBACK_READY','marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'legacy-start',
                'recovery:Normal','state:ROLLED_BACK')
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 1
            Should -Invoke Recover-ProductionDomainCollectionPrepublication -Times 0
            Should -Invoke Set-ProductionWebsiteRecoveryPolicy -Times 1 `
                -ParameterFilter { $Policy -eq 'Normal' }
        }

        It 'reverses publication without restoring the database before deletion' {
            $events = [Collections.Generic.List[string]]::new()
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
            }
            $rollbackContext = [pscustomobject]@{
                state='CANDIDATE_VERIFIED'
                legacyDropped = $false
            }
            Mock Read-ProductionConfig { $config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock = [IO.MemoryStream]::new(); Boundary = [pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop-suspended') }
            Mock Assert-ProductionDomainCollectionRollbackFreshness { throw 'freshness is post-drop only' }
            Mock Recover-ProductionDomainCollectionPrepublication {
                [void]$events.Add('recover-prepublication')
            }
            Mock Restore-ProductionDomainCollectionBackup { throw 'restore must not run' }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$events.Add("marker:$MarkerState")
            }
            Mock Restore-ProductionDomainCollectionLegacyRelease { throw 'prior marker must not return' }
            Mock Start-ProductionDomainCollectionLegacy { [void]$events.Add('legacy-start') }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$events.Add("recovery:$Policy")
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
                $rollbackContext.state = $State
            }

            Invoke-ProductionDomainCollectionRollback -Confirm

            $events | Should -Be @(
                'stop-suspended','marker:ROLLBACK_IN_PROGRESS',
                'recover-prepublication','state:LEGACY_DATA_VERIFIED',
                'state:ROLLBACK_READY','marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'legacy-start',
                'recovery:Normal','state:ROLLED_BACK')
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
            Should -Invoke Assert-ProductionDomainCollectionRollbackFreshness -Times 0
        }

        It 'fails stopped with zero restore effects when delayed target writes close rollback' {
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
            }
            $state = [pscustomobject]@{ legacyDropped = $true; state = 'TARGET_ACTIVE' }
            Mock Read-ProductionConfig { $config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock = [IO.MemoryStream]::new(); Boundary = [pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $state }
            Mock Stop-ProductionDomainCollectionWriter { }
            Mock Assert-ProductionDomainCollectionRollbackFreshness { throw 'snapshot changed' }
            Mock Restore-ProductionDomainCollectionBackup { throw 'restore must not run' }
            Mock Restore-ProductionDomainCollectionLegacyRelease { throw 'legacy must not run' }
            Mock Start-ProductionDomainCollectionLegacy { throw 'legacy must not start' }

            $failure = try {
                Invoke-ProductionDomainCollectionRollback -Confirm
                $null
            } catch { $_.Exception }

            $failure.Message | Should -Match 'stopped with recovery suspended'
            $failure.InnerException.Message | Should -BeExactly 'snapshot changed'

            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
            Should -Invoke Restore-ProductionDomainCollectionLegacyRelease -Times 0
            Should -Invoke Start-ProductionDomainCollectionLegacy -Times 0
        }

        It 'treats persisted DROP_STARTED as restore-bound even before legacyDropped is true' {
            $rollbackContext = [pscustomobject]@{
                state='DROP_STARTED'
                legacyDropped=$false
            }
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new(); Boundary=[pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter { }
            Mock Assert-ProductionDomainCollectionRollbackFreshness { }
            Mock Save-ProductionDomainCollectionContextState {
                $rollbackContext.state = $State
            }
            Mock Write-ProductionDomainCollectionRollbackMarker { }
            Mock Restore-ProductionDomainCollectionBackup {
                $rollbackContext.state = 'LEGACY_DATA_VERIFIED'
            }
            Mock Recover-ProductionDomainCollectionPrepublication {
                throw 'prepublication recovery must not run'
            }
            Mock Restore-ProductionDomainCollectionLegacyRelease { }
            Mock Start-ProductionDomainCollectionLegacy { }
            Mock Set-ProductionWebsiteRecoveryPolicy { }

            Invoke-ProductionDomainCollectionRollback -Confirm

            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 1
            Should -Invoke Recover-ProductionDomainCollectionPrepublication -Times 0
        }

        It 'resumes a verified partial restore without rechecking the removed target catalog' {
            $rollbackContext = [pscustomobject]@{
                state='ROLLBACK_VERIFIED'
                legacyDropped=$true
            }
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new(); Boundary=[pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter { }
            Mock Assert-ProductionDomainCollectionRollbackFreshness {
                throw 'target namespaces may already be removed'
            }
            Mock Save-ProductionDomainCollectionContextState {
                $rollbackContext.state = $State
            }
            Mock Write-ProductionDomainCollectionRollbackMarker { }
            Mock Restore-ProductionDomainCollectionBackup {
                $rollbackContext.state = 'LEGACY_DATA_VERIFIED'
            }
            Mock Restore-ProductionDomainCollectionLegacyRelease { }
            Mock Start-ProductionDomainCollectionLegacy { }
            Mock Set-ProductionWebsiteRecoveryPolicy { }

            Invoke-ProductionDomainCollectionRollback -Confirm

            Should -Invoke Assert-ProductionDomainCollectionRollbackFreshness -Times 0
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 1
        }

        It 'rejects illegal and terminal protected state transitions before persistence' {
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current TARGET_ACTIVE -Next ROLLBACK_VERIFIED } |
                Should -Not -Throw
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current ROLLBACK_VERIFIED -Next LEGACY_DATA_VERIFIED } |
                Should -Not -Throw
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current LEGACY_DATA_VERIFIED -Next ROLLBACK_READY } |
                Should -Not -Throw
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current ROLLBACK_READY -Next ROLLED_BACK } |
                Should -Not -Throw
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current PREVIEWED -Next TARGET_ACTIVE } |
                Should -Throw '*transition*'
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current LEGACY_DATA_VERIFIED -Next ROLLBACK_VERIFIED } |
                Should -Throw '*transition*'
            { Assert-ProductionDomainCollectionStateTransition `
                    -Current ROLLED_BACK -Next ROLLED_BACK } |
                Should -Throw '*terminal*'
        }

        It 'publishes the rollback startup barrier before protected restore state' {
            $events = [Collections.Generic.List[string]]::new()
            $rollbackContext = [pscustomobject]@{
                state='TARGET_ACTIVE'
                legacyDropped=$true
                targetRelease='2' * 40
                legacyRelease='1' * 40
                evidenceDigest='a' * 64
                backupIdentity='b' * 64
            }
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new(); Boundary=[pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop-suspended') }
            Mock Assert-ProductionDomainCollectionRollbackFreshness {
                [void]$events.Add('freshness')
            }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$events.Add("marker:$MarkerState")
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
                $rollbackContext.state = $State
            }
            Mock Restore-ProductionDomainCollectionBackup {
                [void]$events.Add('restore')
                $rollbackContext.state = 'LEGACY_DATA_VERIFIED'
                [void]$events.Add('state:LEGACY_DATA_VERIFIED')
            }
            Mock Recover-ProductionDomainCollectionPrepublication {
                throw 'prepublication recovery must not run'
            }
            Mock Restore-ProductionDomainCollectionLegacyRelease {
                throw 'prior marker must not replace durable v2 rollback state'
            }
            Mock Start-ProductionDomainCollectionLegacy {
                if ($rollbackContext.state -cne 'ROLLBACK_READY') {
                    throw 'legacy start preceded durable rollback-ready state'
                }
                [void]$events.Add('legacy-start')
            }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$events.Add("recovery:$Policy")
            }

            Invoke-ProductionDomainCollectionRollback -Confirm

            $events | Should -Be @(
                'stop-suspended','freshness','marker:ROLLBACK_IN_PROGRESS',
                'state:ROLLBACK_VERIFIED','restore','state:LEGACY_DATA_VERIFIED',
                'state:ROLLBACK_READY','marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'legacy-start','recovery:Normal','state:ROLLED_BACK')
        }

        It 'retries final marker publication without restoring verified legacy data again' {
            $rollbackContext = [pscustomobject]@{
                state='LEGACY_DATA_VERIFIED'
                legacyDropped=$true
                targetRelease='2' * 40
                legacyRelease='1' * 40
                evidenceDigest='a' * 64
                backupIdentity='b' * 64
            }
            $script:markerAttempt = 0
            $script:legacyWrite = 'retained-after-restore-verify'
            $script:stops = 0
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new(); Boundary=[pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter { $script:stops++ }
            Mock Assert-ProductionDomainCollectionRollbackFreshness {
                throw 'verified legacy data must not recheck the removed target catalog'
            }
            Mock Restore-ProductionDomainCollectionBackup {
                $script:legacyWrite = 'lost-by-second-restore'
                throw 'verified legacy data must not restore again'
            }
            Mock Recover-ProductionDomainCollectionPrepublication {
                throw 'verified legacy data must not reverse again'
            }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                $script:markerAttempt++
                if ($script:markerAttempt -eq 1) { throw 'simulated final marker failure' }
            }
            Mock Save-ProductionDomainCollectionContextState {
                $rollbackContext.state = $State
            }
            Mock Start-ProductionDomainCollectionLegacy { }
            Mock Set-ProductionWebsiteRecoveryPolicy { }

            { Invoke-ProductionDomainCollectionRollback -Confirm } |
                Should -Throw '*stopped*recovery suspended*'
            $script:legacyWrite | Should -BeExactly 'retained-after-restore-verify'
            $script:stops | Should -Be 2

            Invoke-ProductionDomainCollectionRollback -Confirm

            $script:legacyWrite | Should -BeExactly 'retained-after-restore-verify'
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
            $rollbackContext.state | Should -BeExactly 'ROLLED_BACK'
        }

        It 're-suspends recovery when terminal persistence fails and retries without restore' {
            $rollbackContext = [pscustomobject]@{
                state='ROLLBACK_READY'
                legacyDropped=$true
                config=[pscustomobject]@{ productionPort=8080 }
            }
            $script:terminalAttempts = 0
            $script:legacyWrite = 'retained-before-final-persistence'
            $script:events = [Collections.Generic.List[string]]::new()
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new(); Boundary=[pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $rollbackContext }
            Mock Stop-ProductionDomainCollectionWriter {
                [void]$script:events.Add('stop-suspended')
            }
            Mock Restore-ProductionDomainCollectionBackup {
                $script:legacyWrite = 'lost-by-second-restore'
                throw 'rollback-ready must not restore'
            }
            Mock Recover-ProductionDomainCollectionPrepublication {
                throw 'rollback-ready must not reverse'
            }
            Mock Write-ProductionDomainCollectionRollbackMarker { }
            Mock Start-ProductionDomainCollectionLegacy {
                [void]$script:events.Add('legacy-start')
            }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$script:events.Add("recovery:$Policy")
            }
            Mock Save-ProductionDomainCollectionContextState {
                if ($State -eq 'ROLLED_BACK') {
                    $script:terminalAttempts++
                    if ($script:terminalAttempts -eq 1) {
                        throw 'simulated terminal persistence failure'
                    }
                }
                $rollbackContext.state = $State
            }

            { Invoke-ProductionDomainCollectionRollback -Confirm } |
                Should -Throw '*stopped*recovery suspended*'
            $script:events[-1] | Should -BeExactly 'stop-suspended'
            $rollbackContext.state | Should -BeExactly 'ROLLBACK_READY'

            Invoke-ProductionDomainCollectionRollback -Confirm

            $script:legacyWrite | Should -BeExactly 'retained-before-final-persistence'
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
            $rollbackContext.state | Should -BeExactly 'ROLLED_BACK'
        }

        It 'reconciles a terminal state committed before post-move protection failed' {
            $root = Join-Path $TestDrive 'terminal-commit'
            $backupRoot = Join-Path $root 'backups'
            $stateRoot = Join-Path $root 'state'
            $releaseRoot = Join-Path $root 'releases'
            $legacyRelease = '1' * 40
            $targetRelease = '2' * 40
            $legacyPath = Join-Path $releaseRoot $legacyRelease
            New-Item -ItemType Directory `
                -Path $backupRoot,$stateRoot,$legacyPath -Force | Out-Null
            $archive = Join-Path $backupRoot 'bound.archive.gz'
            Set-Content -LiteralPath $archive -Value 'archive' -NoNewline
            $backupIdentity = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).
                Hash.ToLowerInvariant()
            [ordered]@{
                archive = $archive
                sha256 = $backupIdentity
                createdAt = '2026-08-11T00:00:00.0000000Z'
            } | ConvertTo-Json | Set-Content -LiteralPath "$archive.sha256.json"
            $evidenceFile = Join-Path $stateRoot 'evidence.json'
            [ordered]@{
                manifestDigest = $script:ManifestDigest
                release = $targetRelease
                backupIdentity = $backupIdentity
            } | ConvertTo-Json | Set-Content -LiteralPath $evidenceFile
            $evidenceFileSha = (Get-FileHash -LiteralPath $evidenceFile -Algorithm SHA256).
                Hash.ToLowerInvariant()
            $config = [pscustomobject]@{
                programDataRoot = $root
                backupRoot = $backupRoot
                productionPort = 18080
            }
            $statePath = Join-Path $stateRoot 'domain-collection-cutover.json'
            $context = [pscustomobject]@{
                config = $config
                state = 'ROLLBACK_READY'
                targetRelease = $targetRelease
                legacyRelease = $legacyRelease
                archive = $archive
                backupIdentity = $backupIdentity
                evidenceDigest = 'a' * 64
                evidenceFile = $evidenceFile
                evidenceFileSha256 = $evidenceFileSha
                ownerToken = 'b' * 32
                candidateDatabase = 'cbell_candidate_' + ('c' * 12) + '_' + ('d' * 24)
                dropStarted = $true
                priorMarkerBase64 = ''
            }
            $marker = [pscustomobject]@{
                version = 2
                state = 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
                targetRelease = $targetRelease
                currentRelease = $targetRelease
                legacyRelease = $legacyRelease
                manifestDigest = $script:ManifestDigest
                evidenceDigest = 'a' * 64
                backupIdentity = $backupIdentity
                legacyDropped = $false
            }
            $reconciliationPath = Join-Path $stateRoot `
                'domain-collection-rollback-reconciliation.json'
            $script:postCommitFault = $true
            $script:terminalStatePath = [IO.Path]::GetFullPath($statePath)
            Mock Protect-ProductionPath {
                if ($script:postCommitFault -and
                    [IO.Path]::GetFullPath($Path) -eq $script:terminalStatePath) {
                    $script:postCommitFault = $false
                    throw 'simulated post-move protection failure'
                }
            }
            Mock Assert-ProtectedProductionPath { }

            { Save-ProductionDomainCollectionContextState `
                    -Context $context -State ROLLED_BACK } |
                Should -Throw '*post-move protection failure*'
            (Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).state |
                Should -BeExactly 'ROLLED_BACK'
            Test-Path -LiteralPath $reconciliationPath | Should -BeTrue
            $hostExecutable = (Get-Process -Id $PID).Path
            $env:CBELL_TERMINAL_STATE_PROBE = $statePath
            try {
                $restartState = & $hostExecutable -NoProfile -NonInteractive -Command `
                    '$value=Get-Content -LiteralPath $env:CBELL_TERMINAL_STATE_PROBE -Raw | ConvertFrom-Json; $value.state'
            } finally {
                Remove-Item Env:CBELL_TERMINAL_STATE_PROBE -ErrorAction SilentlyContinue
            }
            $LASTEXITCODE | Should -Be 0
            ([string]$restartState).Trim() | Should -BeExactly 'ROLLED_BACK'

            $script:events = [Collections.Generic.List[string]]::new()
            Mock Read-ProductionConfig { $config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{
                    Lock = [IO.MemoryStream]::new()
                    Boundary = [pscustomobject]@{}
                }
            }
            Mock Assert-ProductionPathNotReparse { }
            Mock Assert-ProductionDomainCollectionEngineEvidence { }
            Mock Read-ProductionDomainSchemaDirection { $marker }
            Mock Get-JunctionTarget { $legacyPath }
            Mock Assert-ReleasePath { $Path }
            Mock Get-ProductionDomainCollectionReleaseSchema { 'LEGACY' }
            Mock Stop-ProductionDomainCollectionWriter {
                [void]$script:events.Add('stop-suspended')
            }
            Mock Assert-ProductionDomainCollectionRollbackFreshness {
                throw 'terminal retry must not check target freshness'
            }
            Mock Restore-ProductionDomainCollectionBackup {
                throw 'terminal retry must not restore an old backup'
            }
            Mock Recover-ProductionDomainCollectionPrepublication {
                throw 'terminal retry must not run cleanup'
            }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$script:events.Add("marker:$MarkerState")
            }
            Mock Start-ProductionDomainCollectionLegacy {
                [void]$script:events.Add('legacy-start')
            }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$script:events.Add("recovery:$Policy")
            }

            Invoke-ProductionDomainCollectionRollback -Confirm

            $script:events | Should -Be @(
                'stop-suspended','marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'legacy-start','recovery:Normal')
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
            Should -Invoke Recover-ProductionDomainCollectionPrepublication -Times 0
            Test-Path -LiteralPath $reconciliationPath | Should -BeFalse
        }

        It 'rejects unproven terminal rollback replay before writer or restore effects' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{
                    Lock=[IO.MemoryStream]::new()
                    Boundary=[pscustomobject]@{}
                }
            }
            Mock Read-ProductionDomainCollectionProtectedState {
                throw 'Protected domain collection rollback state is terminal.'
            }
            Mock Stop-ProductionDomainCollectionWriter { throw 'writer effect must not run' }
            Mock Restore-ProductionDomainCollectionBackup { throw 'restore effect must not run' }

            { Invoke-ProductionDomainCollectionRollback -Confirm } |
                Should -Throw '*terminal*'

            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 0
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
        }

        It 'rejects exact terminal replay without one-shot reconciliation authorization' {
            $state = [pscustomobject]@{
                state = 'ROLLED_BACK'
                terminalReconciliation = $true
                terminalReconciliationAuthorized = $false
            }
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{
                    Lock = [IO.MemoryStream]::new()
                    Boundary = [pscustomobject]@{}
                }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $state }
            Mock Stop-ProductionDomainCollectionWriter {
                throw 'writer effect must not run'
            }
            Mock Restore-ProductionDomainCollectionBackup {
                throw 'restore effect must not run'
            }

            { Invoke-ProductionDomainCollectionRollback -Confirm } |
                Should -Throw '*one-shot*authorization*'

            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 0
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
        }

        It 'uses state-aware cleanup rather than blind reversal for every pre-drop failure' {
            $events = [Collections.Generic.List[string]]::new()
            $context = [pscustomobject]@{
                config = [pscustomobject]@{ productionPort = 8080 }
                state = 'CANDIDATE_VERIFIED'
                dropStarted = $false
            }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop') }
            Mock Recover-ProductionDomainCollectionPrepublication {
                [void]$events.Add('recover-prepublication')
            }
            Mock Reverse-ProductionDomainCollectionPublication { throw 'blind reverse must not run' }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$events.Add("marker:$MarkerState")
            }
            Mock Restore-ProductionDomainCollectionLegacyRelease { throw 'prior marker must not return' }
            Mock Start-ProductionDomainCollectionLegacy { [void]$events.Add('start') }
            Mock Set-ProductionWebsiteRecoveryPolicy { [void]$events.Add('recovery') }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
                $context.state = $State
            }

            Invoke-ProductionDomainCollectionFailureRecovery `
                -Context $context -PostDrop:$false

            $events | Should -Be @(
                'stop','marker:ROLLBACK_IN_PROGRESS',
                'recover-prepublication','state:LEGACY_DATA_VERIFIED',
                'state:ROLLBACK_READY','marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'start','recovery','state:ROLLED_BACK')
            Should -Invoke Reverse-ProductionDomainCollectionPublication -Times 0
        }

        It 'proves post-drop freshness and transition before automatic restore recovery' {
            $events = [Collections.Generic.List[string]]::new()
            $context = [pscustomobject]@{
                config = [pscustomobject]@{ productionPort = 8080 }
                state = 'DROP_STARTED'
                dropStarted = $true
            }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop') }
            Mock Assert-ProductionDomainCollectionRollbackFreshness {
                [void]$events.Add('freshness')
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
                $context.state = $State
            }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$events.Add("marker:$MarkerState")
            }
            Mock Restore-ProductionDomainCollectionBackup {
                [void]$events.Add('restore')
                $context.state = 'LEGACY_DATA_VERIFIED'
                [void]$events.Add('state:LEGACY_DATA_VERIFIED')
            }
            Mock Restore-ProductionDomainCollectionLegacyRelease { throw 'prior marker must not return' }
            Mock Start-ProductionDomainCollectionLegacy { [void]$events.Add('start') }
            Mock Set-ProductionWebsiteRecoveryPolicy { [void]$events.Add('recovery') }

            Invoke-ProductionDomainCollectionFailureRecovery `
                -Context $context -PostDrop:$true

            $events | Should -Be @(
                'stop','freshness','marker:ROLLBACK_IN_PROGRESS',
                'state:ROLLBACK_VERIFIED','restore','state:LEGACY_DATA_VERIFIED',
                'state:ROLLBACK_READY','marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'start','recovery','state:ROLLED_BACK')
        }

        It 'uses the no-rerestore finalization states for automatic post-drop recovery' {
            $events = [Collections.Generic.List[string]]::new()
            $context = [pscustomobject]@{
                config = [pscustomobject]@{ productionPort = 8080 }
                state = 'LEGACY_DATA_VERIFIED'
                dropStarted = $true
                targetRelease='2' * 40
                legacyRelease='1' * 40
                evidenceDigest='a' * 64
                backupIdentity='b' * 64
            }
            Mock Stop-ProductionDomainCollectionWriter {
                [void]$events.Add('stop-suspended')
            }
            Mock Assert-ProductionDomainCollectionRollbackFreshness {
                throw 'automatic resume must not recheck target data'
            }
            Mock Restore-ProductionDomainCollectionBackup {
                throw 'automatic resume must not restore verified data again'
            }
            Mock Recover-ProductionDomainCollectionPrepublication {
                throw 'automatic resume must not reverse verified data again'
            }
            Mock Write-ProductionDomainCollectionRollbackMarker {
                [void]$events.Add("marker:$MarkerState")
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
                $context.state = $State
            }
            Mock Start-ProductionDomainCollectionLegacy {
                [void]$events.Add('legacy-start')
            }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$events.Add("recovery:$Policy")
            }

            Invoke-ProductionDomainCollectionFailureRecovery `
                -Context $context -PostDrop:$true

            $events | Should -Be @(
                'stop-suspended','state:ROLLBACK_READY',
                'marker:LEGACY_ACTIVE_RECONCILIATION_REQUIRED',
                'legacy-start','recovery:Normal',
                'state:ROLLED_BACK')
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 0
        }

        It 'rejects the live database and production port as candidate identities' {
            { Assert-ProductionDomainCollectionCandidateIsolation `
                    -Database 'christopherbell' -CandidatePort 8081 -ProductionPort 8080 } |
                Should -Throw '*candidate database*'
            { Assert-ProductionDomainCollectionCandidateIsolation `
                    -Database 'cbell_candidate_aaaaaaaaaaaa_aaaaaaaaaaaaaaaaaaaaaaaa' `
                    -CandidatePort 8080 -ProductionPort 8080 } |
                Should -Throw '*candidate port*'
        }
    }
}

Describe 'domain collection protected evidence and engine boundary' {
    InModuleScope Production.DomainCollections {
        BeforeEach {
            $script:validResult = [ordered]@{
                complete = $true
                database = 'christopherbell'
                action = 'preview'
                state = 'PREVIEWED'
                manifestDigest = '576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24'
                backupIdentity = 'a' * 64
                expectedEvidenceDigest = 'b' * 64
                evidenceDigest = 'b' * 64
                evidence = [ordered]@{
                    version = 1
                    manifestDigest = '576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24'
                    release = 'c' * 40
                    backupIdentity = 'a' * 64
                    presentSources = @('accounts')
                    kinds = @(1..52 | ForEach-Object {
                        [ordered]@{ kind = ('kind_{0:d2}' -f $_); count = 0; checksum = 'd' * 64 }
                    })
                    collections = @([ordered]@{
                        name = 'accounts'
                        count = 0
                        checksum = 'd' * 64
                        indexDigest = 'e' * 64
                    })
                    v014 = [ordered]@{
                        id = '014-consolidate-music-runtime-state'
                        checksum = '11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb'
                        queueChecksum = 'e' * 64
                        radioChecksum = 'f' * 64
                        targetChecksum = '0' * 64
                    }
                }
                kinds = @()
                indexes = @()
                nextOperation = 'stage'
            }
        }

        It 'accepts one exact redacted engine result document' {
            $value = ConvertFrom-ProductionDomainCollectionResult `
                -Json ($script:validResult | ConvertTo-Json -Depth 20) `
                -ExpectedDatabase christopherbell `
                -ExpectedAction preview

            $value.complete | Should -BeTrue
            $value.evidence.kinds | Should -HaveCount 52
            ($value.evidence.kinds[0].count -is [int] -or
                $value.evidence.kinds[0].count -is [long]) | Should -BeTrue
            $value.evidence.collections[0].indexDigest | Should -BeExactly ('e' * 64)
        }

        It 'rejects collection evidence without the exact Task 6 index digest' {
            $script:validResult.evidence.collections[0].Remove('indexDigest')

            { ConvertFrom-ProductionDomainCollectionResult `
                    -Json ($script:validResult | ConvertTo-Json -Depth 20) `
                    -ExpectedDatabase christopherbell `
                    -ExpectedAction preview } | Should -Throw '*exact result contract*'
        }

        It 'accepts a bounded successful mutation continuation without treating it as failure' {
            $script:validResult.complete = $false
            $script:validResult.action = 'stage'
            $script:validResult.state = 'STAGING'
            $script:validResult.evidence = $null
            $script:validResult.nextOperation = 'stage'

            $value = ConvertFrom-ProductionDomainCollectionResult `
                -Json ($script:validResult | ConvertTo-Json -Depth 20) `
                -ExpectedDatabase christopherbell `
                -ExpectedAction stage

            $value.complete | Should -BeFalse
            $value.nextOperation | Should -BeExactly 'stage'
        }

        It 'rejects extra output properties and any non-JSON prefix' {
            $script:validResult.secret = 'must-not-pass'
            { ConvertFrom-ProductionDomainCollectionResult `
                    -Json ($script:validResult | ConvertTo-Json -Depth 20) `
                    -ExpectedDatabase christopherbell `
                    -ExpectedAction preview } | Should -Throw '*exact result contract*'
            $script:validResult.Remove('secret')
            { ConvertFrom-ProductionDomainCollectionResult `
                    -Json ("noise`n" + ($script:validResult | ConvertTo-Json -Depth 20)) `
                    -ExpectedDatabase christopherbell `
                    -ExpectedAction preview } | Should -Throw '*single JSON document*'
        }

        It 'passes only validated identities into the file-based migration engine' {
            $config = [pscustomobject]@{
                mongoShellExe = 'C:\tools\mongosh.exe'
                repositoryPath = 'A:\repo'
            }
            Mock Invoke-CheckedProcess { $script:validResult | ConvertTo-Json -Depth 20 }

            $null = Invoke-ProductionDomainCollectionEngine `
                -Config $config `
                -Database christopherbell `
                -Action preview `
                -OwnerToken ('1' * 32) `
                -Release ('c' * 40) `
                -BackupIdentity ('a' * 64) `
                -EvidenceDigest ('0' * 64)

            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $evalIndex = [Array]::IndexOf($ArgumentList, '--eval')
                $FilePath -eq 'C:\tools\mongosh.exe' -and
                $ArgumentList -contains '--file' -and
                $evalIndex -ge 0 -and
                $ArgumentList[$evalIndex + 1] -match 'void 0;$' -and
                ($ArgumentList -join ' ') -match 'DomainCollectionManifest\.js' -and
                ($ArgumentList -join ' ') -match 'Invoke-DomainCollectionMigration\.js' -and
                -not (($ArgumentList -join ' ') -match 'password|payload|secret')
            }
        }

        It 'rejects an invalid database before starting mongosh' {
            Mock Invoke-CheckedProcess { throw 'must not start' }
            { Invoke-ProductionDomainCollectionEngine `
                    -Config ([pscustomobject]@{
                        mongoShellExe='mongosh.exe'; repositoryPath='A:\repo' }) `
                    -Database '../christopherbell' `
                    -Action preview `
                    -OwnerToken ('1' * 32) `
                    -Release ('2' * 40) `
                    -BackupIdentity ('3' * 64) `
                    -EvidenceDigest ('0' * 64) } | Should -Throw '*database*'
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'allows only an isolated loopback URI for the internal disposable wrapper seam' {
            $database = 'cbell_candidate_aaaaaaaaaaaa_aaaaaaaaaaaaaaaaaaaaaaaa'
            $script:validResult.database = $database
            $config = [pscustomobject]@{
                mongoShellExe = 'C:\tools\mongosh.exe'
                repositoryPath = 'A:\repo'
            }
            Mock Invoke-CheckedProcess { $script:validResult | ConvertTo-Json -Depth 20 }

            $null = Invoke-ProductionDomainCollectionEngine `
                -Config $config -Database $database -Action preview `
                -OwnerToken ('1' * 32) -Release ('c' * 40) `
                -BackupIdentity ('a' * 64) -EvidenceDigest ('0' * 64) `
                -MongoUri 'mongodb://127.0.0.1:47001/admin'

            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $ArgumentList -contains 'mongodb://127.0.0.1:47001/admin'
            }
            { Invoke-ProductionDomainCollectionEngine `
                    -Config $config -Database $database -Action preview `
                    -OwnerToken ('1' * 32) -Release ('c' * 40) `
                    -BackupIdentity ('a' * 64) -EvidenceDigest ('0' * 64) `
                    -MongoUri 'mongodb://127.0.0.1:27017/admin' } |
                Should -Throw '*disposable Mongo URI*'
        }
    }
}

Describe 'domain collection backup and process boundaries' {
    InModuleScope Production.DomainCollections {
        BeforeEach {
            Mock Assert-ProductionPathNotReparse { }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Invoke-CheckedProcess { '' }
        }

        It 'binds a fresh archive hash and repeats dry restore verification' {
            $backupRoot = Join-Path $TestDrive 'backups'
            New-Item -ItemType Directory -Path $backupRoot | Out-Null
            $archive = Join-Path $backupRoot 'exact.archive.gz'
            [IO.File]::WriteAllBytes($archive, [byte[]](1,2,3,4))
            $hash = (Get-FileHash $archive -Algorithm SHA256).Hash
            [ordered]@{
                archive = $archive
                sha256 = $hash
                createdAt = [DateTimeOffset]::UtcNow.ToString('o')
            } | ConvertTo-Json | Set-Content "$archive.sha256.json"
            $config = [pscustomobject]@{
                backupRoot = $backupRoot
                mongoToolsPath = 'C:\mongo-tools'
                repositoryPath = 'A:\repo'
            }
            Mock New-ProductionBackup { $archive }
            Mock Get-NativeMongoRestoreDryRunArguments { @('--dryRun') }

            $result = New-ProductionDomainCollectionVerifiedBackup -Config $config

            $result.backupIdentity | Should -BeExactly $hash.ToLowerInvariant()
            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $FilePath -eq 'C:\mongo-tools\mongorestore.exe' -and
                $ArgumentList -contains '--dryRun'
            }
        }

        It 'rejects an archive changed after its sidecar before dry restore' {
            $backupRoot = Join-Path $TestDrive 'tampered-backups'
            New-Item -ItemType Directory -Path $backupRoot | Out-Null
            $archive = Join-Path $backupRoot 'exact.archive.gz'
            [IO.File]::WriteAllBytes($archive, [byte[]](1,2,3,4))
            [ordered]@{
                archive = $archive
                sha256 = 'a' * 64
                createdAt = [DateTimeOffset]::UtcNow.ToString('o')
            } | ConvertTo-Json | Set-Content "$archive.sha256.json"
            $config = [pscustomobject]@{
                backupRoot = $backupRoot
                mongoToolsPath = 'C:\mongo-tools'
                repositoryPath = 'A:\repo'
            }
            Mock New-ProductionBackup { $archive }

            { New-ProductionDomainCollectionVerifiedBackup -Config $config } |
                Should -Throw '*dry-restored backup could not be proven*'
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'removes exact manifest namespaces before archive restore and verifies afterward' {
            $events = [Collections.Generic.List[string]]::new()
            $state = [pscustomobject]@{
                config = [pscustomobject]@{
                    mongoToolsPath = 'C:\mongo-tools'
                    repositoryPath = 'A:\repo'
                }
                archive = 'A:\backups\exact.archive.gz'
                backupIdentity = 'a' * 64
                evidenceDigest = 'b' * 64
                ownerToken = 'c' * 32
                targetRelease = 'd' * 40
                evidence = [pscustomobject]@{ version = 1 }
            }
            Mock Get-FileHash { [pscustomobject]@{ Hash = 'A' * 64 } }
            Mock Invoke-ProductionDomainCollectionUntilComplete {
                [void]$events.Add("engine:$Action")
                [pscustomobject]@{ complete = $true }
            }
            Mock Invoke-CheckedProcess {
                [void]$events.Add('archive-restore')
                if ($ArgumentList -contains '--drop') { throw 'restore must not rely on --drop' }
                ''
            }
            Mock Invoke-ProductionDomainCollectionEngine {
                [void]$events.Add("verify:$Action")
                [pscustomobject]@{ complete = $true }
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
            }

            Restore-ProductionDomainCollectionBackup -State $state

            $events | Should -Be @(
                'engine:prepare-restore','archive-restore','verify:restore-verify',
                'state:LEGACY_DATA_VERIFIED')
        }

        It 'contains no ad hoc service or process lifecycle commands' {
            $module = Get-Content `
                (Join-Path $PSScriptRoot '..\modules\Production.DomainCollections.psm1') -Raw

            $module | Should -Not -Match '\b(Start|Stop)-(Service|Process)\b|\bsc\.exe\b'
            $module | Should -Match 'Stop-ProductionWebsiteService'
            $module | Should -Match 'Switch-ProductionRelease'
            $module | Should -Match 'Test-CandidateRelease'
        }
    }
}
