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
            $script:config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
                candidatePort = 8081
                repositoryPath = 'A:\Projects\christopherbell.dev'
            }
            $script:context = [pscustomobject][ordered]@{
                config = $script:config
                targetRelease = '2' * 40
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
            Mock New-ProductionDomainCollectionCutoverContext {
                [void]$script:events.Add('backup-and-evidence')
                $script:context
            }
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
            Mock Start-ProductionDomainCollectionTargetForVerification {
                [void]$script:events.Add('target-start-verify')
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
                'backup-and-evidence',
                'candidate',
                'root:recheck',
                'stop-suspended',
                'stage-publish',
                'target-start-verify',
                'stop-suspended',
                'root:recheck',
                'drop-legacy',
                'marker-recovery-auto',
                'lock:release')
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

        It 'leaves the old writer untouched when candidate proof fails before live mutation' {
            Mock Invoke-ProductionDomainCollectionCandidateProof { throw 'candidate failed' }

            { Invoke-ProductionDomainCollectionCutover -Confirm } |
                Should -Throw '*candidate failed*'

            Should -Invoke Stop-ProductionDomainCollectionWriter -Times 0
            Should -Invoke Invoke-ProductionDomainCollectionFailureRecovery -Times 0
            Should -Invoke Invoke-ProductionDomainCollectionDropLegacy -Times 0
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

        It 'restores the exact backup before selecting or starting the legacy writer after deletion' {
            $events = [Collections.Generic.List[string]]::new()
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
            }
            $state = [pscustomobject]@{ legacyDropped = $true }
            Mock Read-ProductionConfig { $config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock = [IO.MemoryStream]::new(); Boundary = [pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $state }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop-suspended') }
            Mock Restore-ProductionDomainCollectionBackup { [void]$events.Add('restore-backup') }
            Mock Reverse-ProductionDomainCollectionPublication { throw 'reverse must not run' }
            Mock Restore-ProductionDomainCollectionLegacyRelease { [void]$events.Add('legacy-junction') }
            Mock Start-ProductionDomainCollectionLegacy { [void]$events.Add('legacy-start') }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$events.Add("recovery:$Policy")
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
            }

            Invoke-ProductionDomainCollectionRollback -Confirm

            $events | Should -Be @(
                'stop-suspended','restore-backup','legacy-junction','legacy-start',
                'recovery:Normal','state:ROLLED_BACK')
            Should -Invoke Restore-ProductionDomainCollectionBackup -Times 1
            Should -Invoke Reverse-ProductionDomainCollectionPublication -Times 0
            Should -Invoke Set-ProductionWebsiteRecoveryPolicy -Times 1 `
                -ParameterFilter { $Policy -eq 'Normal' }
        }

        It 'reverses publication without restoring the database before deletion' {
            $events = [Collections.Generic.List[string]]::new()
            $config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
            }
            $state = [pscustomobject]@{ legacyDropped = $false }
            Mock Read-ProductionConfig { $config }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock = [IO.MemoryStream]::new(); Boundary = [pscustomobject]@{} }
            }
            Mock Read-ProductionDomainCollectionProtectedState { $state }
            Mock Stop-ProductionDomainCollectionWriter { [void]$events.Add('stop-suspended') }
            Mock Reverse-ProductionDomainCollectionPublication { [void]$events.Add('reverse') }
            Mock Restore-ProductionDomainCollectionBackup { throw 'restore must not run' }
            Mock Restore-ProductionDomainCollectionLegacyRelease { [void]$events.Add('legacy-junction') }
            Mock Start-ProductionDomainCollectionLegacy { [void]$events.Add('legacy-start') }
            Mock Set-ProductionWebsiteRecoveryPolicy {
                [void]$events.Add("recovery:$Policy")
            }
            Mock Save-ProductionDomainCollectionContextState {
                [void]$events.Add("state:$State")
            }

            Invoke-ProductionDomainCollectionRollback -Confirm

            $events | Should -Be @(
                'stop-suspended','reverse','legacy-junction','legacy-start',
                'recovery:Normal','state:ROLLED_BACK')
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
                        [ordered]@{ kind = ('kind_{0:d2}' -f $_); count = '0'; checksum = 'd' * 64 }
                    })
                    collections = @()
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
