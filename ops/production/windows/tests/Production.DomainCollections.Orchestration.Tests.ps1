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

        It 'rejects terminal rollback replay before writer or restore effects' {
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
