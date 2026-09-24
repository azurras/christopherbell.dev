Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.MusicRuntime.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.AutoDeploy.psm1') -Force

Describe 'automatic origin main deployment' {
    BeforeEach {
        $script:config = [pscustomobject]@{ programDataRoot=$TestDrive; repositoryPath=$TestDrive; remote='origin'; branch='main'; autoDeployFailureBackoffSeconds=900 }
        Mock Assert-ProductionFixedRootBoundary {
            [pscustomobject]@{
                Root = 'C:\ProgramData\christopherbell.dev'
                LockPath = Join-Path $TestDrive 'deploy.lock'
            }
        } -ModuleName Production.AutoDeploy
        Mock Enter-ProductionFixedRootDeploymentLock {
            [pscustomobject]@{
                Lock = Enter-DeploymentLock -LockPath (Join-Path $TestDrive 'deploy.lock')
            }
        } -ModuleName Production.AutoDeploy
        Mock Assert-ProductionPathNotReparse {} -ModuleName Production.AutoDeploy
        Mock Assert-ProductionTreeNotReparse {} -ModuleName Production.AutoDeploy
        Mock Protect-ProductionPath {} -ModuleName Production.AutoDeploy
        Mock Protect-ProductionTree {} -ModuleName Production.AutoDeploy
        Mock Assert-ProtectedProductionTree {} -ModuleName Production.AutoDeploy
        Mock Read-ProductionMusicSchemaDirection {
            [pscustomobject]@{ state='TARGET_ACTIVE' }
        } -ModuleName Production.AutoDeploy
    }

    It 'rejects an alternate root before auto state, marker, remote, or deploy effects' {
        $alternateConfig = [pscustomobject]@{
            programDataRoot = 'C:\attacker-controlled'
            repositoryPath = 'C:\attacker-controlled\repository'
            remote = 'origin'
            branch = 'main'
            autoDeployFailureBackoffSeconds = 900
        }
        Mock Assert-ProductionFixedRootBoundary {
            if ($FixedRoot -cne 'C:\ProgramData\christopherbell.dev') {
                throw 'wrong fixed root reached'
            }
            throw ('Production root boundary is not guarded. ' +
                'Run guarded prod install before retrying.')
        } -ModuleName Production.AutoDeploy
        Mock Read-AutoDeployState { throw 'auto state was reached' } -ModuleName Production.AutoDeploy
        Mock Read-ProductionMusicSchemaDirection { throw 'marker was reached' } -ModuleName Production.AutoDeploy
        Mock Get-RemoteMainSha { throw 'remote was reached' } -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy { throw 'deploy was reached' } -ModuleName Production.AutoDeploy

        { Invoke-AutoDeployOnce $alternateConfig } |
            Should -Throw '*Run guarded prod install before retrying*'
        Should -Invoke Assert-ProductionFixedRootBoundary -Times 1 -Exactly `
            -ModuleName Production.AutoDeploy
        Should -Invoke Read-AutoDeployState -Times 0 -Exactly -ModuleName Production.AutoDeploy
        Should -Invoke Read-ProductionMusicSchemaDirection -Times 0 -Exactly `
            -ModuleName Production.AutoDeploy
        Should -Invoke Get-RemoteMainSha -Times 0 -Exactly -ModuleName Production.AutoDeploy
        Should -Invoke Invoke-ProductionDeploy -Times 0 -Exactly -ModuleName Production.AutoDeploy
    }

    It 'does not write an auto-deploy error log when the configured root is unsafe' {
        InModuleScope Production.AutoDeploy {
            Mock Read-ProductionConfig {
                [pscustomobject]@{ programDataRoot='C:\attacker-controlled' }
            }
            Mock Assert-ProductionFixedRootBoundary {
                throw ('Production root boundary is not guarded. ' +
                    'Run guarded prod install before retrying.')
            }
            Mock Invoke-AutoDeployOnce { throw 'auto deploy was reached' }
            Mock Add-Content { throw 'unsafe log write was reached' }

            { Start-AutoDeployLoop } |
                Should -Throw '*Run guarded prod install before retrying*'
            Should -Invoke Invoke-AutoDeployOnce -Times 0 -Exactly
            Should -Invoke Add-Content -Times 0 -Exactly
        }
    }

    It 'publishes a terminal status when protected configuration cannot be read' {
        InModuleScope Production.AutoDeploy {
            $script:publishedOutcomes = [Collections.Generic.List[object]]::new()
            Mock Initialize-AutoDeployStatusStore { Join-Path $TestDrive 'status' }
            Mock Publish-AutoDeployStatusBestEffort {
                $script:publishedOutcomes.Add([pscustomobject]@{
                    Outcome=$Outcome
                    FailureCategory=$FailureCategory
                })
                return $true
            }
            Mock Read-ProductionConfig { throw 'simulated protected config read failure' }
            Mock Get-RemoteMainSha { throw 'remote must not be read' }
            Mock Invoke-AutoDeployOnce { throw 'deployment must not run' }

            $caught = $null
            try { Start-AutoDeployLoop }
            catch { $caught = $_.Exception }

            $caught.Message | Should -Be 'simulated protected config read failure'
            $script:publishedOutcomes[-1].Outcome | Should -Be 'CHECK_FAILED'
            $script:publishedOutcomes[-1].FailureCategory | Should -Be 'PROTECTED_PRECONDITION'
            Should -Invoke Get-RemoteMainSha -Times 0 -ModuleName Production.AutoDeploy
            Should -Invoke Invoke-AutoDeployOnce -Times 0 -ModuleName Production.AutoDeploy
        }
    }

    It 'publishes a terminal status when the fixed root boundary is rejected' {
        InModuleScope Production.AutoDeploy {
            $script:publishedOutcomes = [Collections.Generic.List[object]]::new()
            $config = [pscustomobject]@{ programDataRoot=$TestDrive }
            Mock Initialize-AutoDeployStatusStore { Join-Path $TestDrive 'status' }
            Mock Publish-AutoDeployStatusBestEffort {
                $script:publishedOutcomes.Add([pscustomobject]@{
                    Outcome=$Outcome
                    FailureCategory=$FailureCategory
                })
                return $true
            }
            Mock Read-ProductionConfig { $config }
            Mock Assert-ProductionFixedRootBoundary { throw 'simulated fixed root boundary failure' }
            Mock Get-RemoteMainSha { throw 'remote must not be read' }
            Mock Invoke-AutoDeployOnce { throw 'deployment must not run' }

            $caught = $null
            try { Start-AutoDeployLoop }
            catch { $caught = $_.Exception }

            $caught.Message | Should -Be 'simulated fixed root boundary failure'
            $script:publishedOutcomes[-1].Outcome | Should -Be 'CHECK_FAILED'
            $script:publishedOutcomes[-1].FailureCategory | Should -Be 'PROTECTED_PRECONDITION'
            Should -Invoke Get-RemoteMainSha -Times 0 -ModuleName Production.AutoDeploy
            Should -Invoke Invoke-AutoDeployOnce -Times 0 -ModuleName Production.AutoDeploy
        }
    }

    It 'preserves the configuration failure when terminal status publication fails' {
        InModuleScope Production.AutoDeploy {
            $script:statusPublicationAttempts = [Collections.Generic.List[string]]::new()
            Mock Initialize-AutoDeployStatusStore { Join-Path $TestDrive 'status' }
            Mock Publish-AutoDeployStatus {
                $script:statusPublicationAttempts.Add($Outcome)
                throw 'simulated status store write failure'
            }
            Mock Read-ProductionConfig { throw 'simulated protected config read failure' }

            $caught = $null
            try { Start-AutoDeployLoop }
            catch { $caught = $_.Exception }

            $caught.Message | Should -Be 'simulated protected config read failure'
            $script:statusPublicationAttempts | Should -Contain 'CHECK_FAILED'
        }
    }

    It 'refuses automatic deploy before remote access while legacy reconciliation is required' {
        $script:publishedOutcome = $null
        Mock Read-ProductionMusicSchemaDirection {
            [pscustomobject]@{ state='LEGACY_ACTIVE_RECONCILIATION_REQUIRED' }
        } -ModuleName Production.AutoDeploy
        Mock Publish-AutoDeployStatusBestEffort {
            $script:publishedOutcome = $Outcome
            $true
        } -ModuleName Production.AutoDeploy
        Mock Get-RemoteMainSha { throw 'remote must not be read' } -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy { throw 'deploy must not run' } -ModuleName Production.AutoDeploy

        { Invoke-AutoDeployOnce $config } | Should -Throw '*blocked*legacy*reconciliation*'

        $script:publishedOutcome | Should -Be 'BLOCKED'
        Should -Invoke Get-RemoteMainSha -Times 0 -ModuleName Production.AutoDeploy
        Should -Invoke Invoke-ProductionDeploy -Times 0 -ModuleName Production.AutoDeploy
    }

    It 'reports a protected marker read failure as a failed check, not a migration block' {
        $script:publishedOutcome = $null
        Mock Read-ProductionMusicSchemaDirection { throw 'protected marker could not be read' } `
            -ModuleName Production.AutoDeploy
        Mock Publish-AutoDeployStatusBestEffort {
            $script:publishedOutcome = $Outcome
            $true
        } -ModuleName Production.AutoDeploy
        Mock Get-RemoteMainSha { throw 'remote must not be read' } -ModuleName Production.AutoDeploy

        { Invoke-AutoDeployOnce $config } | Should -Throw '*protected marker could not be read*'

        $script:publishedOutcome | Should -Be 'CHECK_FAILED'
        Should -Invoke Get-RemoteMainSha -Times 0 -ModuleName Production.AutoDeploy
    }

    It 'refuses automatic deploy before remote access when schema direction is absent' {
        Mock Read-ProductionMusicSchemaDirection { $null } -ModuleName Production.AutoDeploy
        Mock Get-RemoteMainSha { throw 'remote must not be read' } -ModuleName Production.AutoDeploy

        { Invoke-AutoDeployOnce $config } | Should -Throw '*marker is absent*'

        Should -Invoke Get-RemoteMainSha -Times 0 -ModuleName Production.AutoDeploy
    }

    It 'does not deploy when the remote SHA is already active' {
        Mock Get-RemoteMainSha { '0123456789012345678901234567890123456789' } -ModuleName Production.AutoDeploy
        Mock Get-ActiveReleaseSha { '0123456789012345678901234567890123456789' } -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy {} -ModuleName Production.AutoDeploy
        Invoke-AutoDeployOnce $config
        Should -Invoke Invoke-ProductionDeploy -Times 0 -ModuleName Production.AutoDeploy
    }

    It 'deploys exactly once when remote main changes' {
        Mock Get-RemoteMainSha { 'abcdefabcdefabcdefabcdefabcdefabcdefabcd' } -ModuleName Production.AutoDeploy
        $script:activeCalls = 0
        Mock Get-ActiveReleaseSha {
            if ($script:activeCalls++ -eq 0) { '0123456789012345678901234567890123456789' }
            else { 'abcdefabcdefabcdefabcdefabcdefabcdefabcd' }
        } -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy {} -ModuleName Production.AutoDeploy
        Invoke-AutoDeployOnce $config
        Should -Invoke Invoke-ProductionDeploy -Times 1 -ModuleName Production.AutoDeploy
        (Read-AutoDeployState $config).successfulSha | Should -Be 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
    }

    It 'backs off the same failed SHA' {
        $state = New-AutoDeployState
        $state.failedSha = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
        $state.failedAt = (Get-Date).ToUniversalTime().ToString('o')
        Write-AutoDeployState $config $state
        Mock Get-RemoteMainSha { 'abcdefabcdefabcdefabcdefabcdefabcdefabcd' } -ModuleName Production.AutoDeploy
        Mock Get-ActiveReleaseSha { '0123456789012345678901234567890123456789' } -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy {} -ModuleName Production.AutoDeploy
        Invoke-AutoDeployOnce $config
        Should -Invoke Invoke-ProductionDeploy -Times 0 -ModuleName Production.AutoDeploy
    }

    It 'retries the same failed SHA after the backoff expires' {
        $remoteSha = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
        $state = New-AutoDeployState
        $state.failedSha = $remoteSha
        $state.failedAt = ([datetime]::UtcNow.AddMinutes(-16)).ToString('o')
        Write-AutoDeployState $config $state
        Mock Get-RemoteMainSha { $remoteSha } -ModuleName Production.AutoDeploy
        $script:activeCalls = 0
        Mock Get-ActiveReleaseSha {
            if ($script:activeCalls++ -eq 0) { '0123456789012345678901234567890123456789' }
            else { $remoteSha }
        } -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy {} -ModuleName Production.AutoDeploy

        Invoke-AutoDeployOnce $config

        Should -Invoke Invoke-ProductionDeploy -Times 1 -ModuleName Production.AutoDeploy
    }

    It 'surfaces an automatic deployment failure after persisting its failed SHA' {
        $remoteSha = 'fedcbafedcbafedcbafedcbafedcbafedcbafedc'
        Mock Get-RemoteMainSha { $remoteSha } -ModuleName Production.AutoDeploy
        Mock Get-ActiveReleaseSha { '0123456789012345678901234567890123456789' } `
            -ModuleName Production.AutoDeploy
        Mock Invoke-ProductionDeploy { throw 'candidate process exited before binding' } `
            -ModuleName Production.AutoDeploy

        $caughtMessage = $null
        try { Invoke-AutoDeployOnce $config }
        catch { $caughtMessage = $_.Exception.Message }

        $caughtMessage | Should -Be 'candidate process exited before binding'

        $state = Read-AutoDeployState $config
        $state.failedSha | Should -Be $remoteSha
        $state.error | Should -Be 'candidate process exited before binding'
    }

    It 'reads operator status without loading protected deployment configuration' {
        Mock Read-ProductionConfig { throw 'protected configuration was accessed' } `
            -ModuleName Production.AutoDeploy

        { Get-AutoDeployStatus } | Should -Not -Throw

        Should -Invoke Read-ProductionConfig -Times 0 -Exactly `
            -ModuleName Production.AutoDeploy
    }

    It 'keeps the production CLI free of unapproved-verb discovery warnings' {
        $commandPath = Join-Path $PSScriptRoot '..\prod.ps1'
        $warningRecords = @()

        $null = & $commandPath help -WarningVariable warningRecords

        $warningRecords | Should -BeNullOrEmpty
    }

    It 'creates a standard-user-readable status store with no untrusted write rights' {
        InModuleScope Production.AutoDeploy {
            $parent = Join-Path $TestDrive 'status-parent'
            $statusRoot = Join-Path $parent 'christopherbell.dev-status'
            New-Item -ItemType Directory -Path $parent | Out-Null

            Initialize-AutoDeployStatusStore -StatusRoot $statusRoot

            $acl = Get-Acl -LiteralPath $statusRoot
            $acl.AreAccessRulesProtected | Should -BeTrue
            $users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
            $rules = @($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]))
            $userRules = @($rules | Where-Object { $_.IdentityReference -eq $users })
            $userRules.Count | Should -BeGreaterThan 0
            foreach ($rule in $userRules) {
                $rule.AccessControlType | Should -Be ([Security.AccessControl.AccessControlType]::Allow)
                $rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write |
                    Should -Be 0
                $rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Delete |
                    Should -Be 0
                $rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ChangePermissions |
                    Should -Be 0
                $rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::TakeOwnership |
                    Should -Be 0
            }
            { Assert-AutoDeployStatusDirectory -Path $statusRoot } | Should -Not -Throw
        }
    }

    It 'publishes only sanitized status and marks old status stale without protected configuration' {
        InModuleScope Production.AutoDeploy {
            $parent = Join-Path $TestDrive 'status-publication-parent'
            $statusRoot = Join-Path $parent 'christopherbell.dev-status'
            New-Item -ItemType Directory -Path $statusRoot -Force | Out-Null
            $now = [datetime]::UtcNow
            $state = New-AutoDeployState
            $state.remoteSha = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
            $state.attemptedSha = $state.remoteSha
            $state.failedSha = $state.remoteSha
            $state.error = 'SPRING_DATASOURCE_PASSWORD=never-export-this'
            Mock Assert-AutoDeployStatusDirectory {} -ModuleName Production.AutoDeploy
            Mock Assert-AutoDeployStatusFile {} -ModuleName Production.AutoDeploy

            Publish-AutoDeployStatus -Outcome 'DEPLOYMENT_FAILED' -FailureCategory 'CANDIDATE_STARTUP' `
                -State $state -StatusRoot $statusRoot `
                -UpdatedAt $now.AddMinutes(-10)

            $result = Get-AutoDeployStatus -StatusRoot $statusRoot -Now $now
            $result.status | Should -Be 'DEPLOYMENT_FAILED'
            $result.failureCategory | Should -Be 'CANDIDATE_STARTUP'
            $result.freshness | Should -Be 'STALE'
            $result.message | Should -Not -Match 'never-export-this|SPRING_DATASOURCE_PASSWORD'
            (Get-Content -LiteralPath (Join-Path $statusRoot 'auto-deploy.json') -Raw) |
                Should -Not -Match 'never-export-this|SPRING_DATASOURCE_PASSWORD'

            Publish-AutoDeployStatus -Outcome 'CHECKING' -State $state `
                -StatusRoot $statusRoot -UpdatedAt $now.AddSeconds(-1)
            $result = Get-AutoDeployStatus -StatusRoot $statusRoot -Now $now
            $result.status | Should -Be 'CHECKING'
            $result.freshness | Should -Be 'FRESH'
            $reportedUpdatedAt = [datetimeoffset]::Parse($result.updatedAt)
            $reportedUpdatedAt.UtcDateTime | Should -Be $now.AddSeconds(-1)
        }
    }

    It 'reports a future-dated status as unavailable instead of fresh' {
        InModuleScope Production.AutoDeploy {
            $statusRoot = Join-Path $TestDrive 'future-status'
            New-Item -ItemType Directory -Path $statusRoot -Force | Out-Null
            Mock Assert-AutoDeployStatusDirectory {}
            Mock Assert-AutoDeployStatusFile {}
            Publish-AutoDeployStatus -Outcome 'CHECKING' -State (New-AutoDeployState) `
                -StatusRoot $statusRoot -UpdatedAt ([datetime]'2026-09-24T12:00:00Z')

            $result = Get-AutoDeployStatus -StatusRoot $statusRoot `
                -Now ([datetime]'2026-09-24T11:59:59Z')

            $result.available | Should -BeFalse
            $result.freshness | Should -Be 'UNAVAILABLE'
            $result.reason | Should -Be 'FUTURE_TIMESTAMP'
        }
    }

    It 'reports malformed status as unavailable without returning record contents' {
        InModuleScope Production.AutoDeploy {
            $statusRoot = Join-Path $TestDrive 'malformed-status'
            New-Item -ItemType Directory -Path $statusRoot -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $statusRoot 'auto-deploy.json') `
                -Value '{"password":"do-not-return"}'
            Mock Assert-AutoDeployStatusDirectory {}
            Mock Assert-AutoDeployStatusFile {}

            $result = Get-AutoDeployStatus -StatusRoot $statusRoot

            $result.available | Should -BeFalse
            $result.freshness | Should -Be 'UNAVAILABLE'
            $result.message | Should -Not -Match 'do-not-return|password'
        }
    }

    It 'rejects a versioned tool bundle whose file hash changed after staging' {
        InModuleScope Production.AutoDeploy {
            $root = Join-Path $TestDrive 'tool-integrity'
            New-Item -ItemType Directory -Path (Join-Path $root 'modules') -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $root 'prod.ps1') -Value '# trusted'
            Set-Content -LiteralPath (Join-Path $root 'modules\Production.AutoDeploy.psm1') `
                -Value '# trusted module'
            $files = @(Get-AutoDeployToolManifestEntries -Root $root)
            [ordered]@{
                schemaVersion=1
                sourceCommitSha='fedcbafedcbafedcbafedcbafedcbafedcbafedc'
                treeSha='abcdefabcdefabcdefabcdefabcdefabcdefabcd'
                files=$files
            } |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'auto-deploy-tools.json')
            Mock Assert-ProtectedProductionTree {}
            Mock Assert-ProductionTreeNotReparse {}

            Assert-AutoDeployToolVersion -Root $root -TreeSha 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
            Set-Content -LiteralPath (Join-Path $root 'prod.ps1') -Value '# altered'

            { Assert-AutoDeployToolVersion -Root $root -TreeSha 'abcdefabcdefabcdefabcdefabcdefabcdefabcd' } |
                Should -Throw '*integrity check*'
        }
    }

    It 'stages trusted versioned tools before switching the poller action' {
        InModuleScope Production.AutoDeploy {
            $programDataRoot = Join-Path $TestDrive 'stage'
            $config = [pscustomobject]@{
                programDataRoot=$programDataRoot
                repositoryPath=(Join-Path $TestDrive 'repository')
                remote='origin'
                branch='main'
            }
            $sha = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
            $treeSha = '0123012301230123012301230123012301230123'
            $script:toolRefreshEvents = [Collections.Generic.List[string]]::new()
            Mock Assert-ProductionFixedRootBoundary {}
            Mock Get-RemoteMainSha { $sha }
            Mock Resolve-OriginMainRelease { $sha }
            Mock Assert-ProductionPathNotReparse {}
            Mock Protect-ProductionPath {}
            Mock Assert-ProductionTreeNotReparse { $script:toolRefreshEvents.Add('verify-no-reparse') }
            Mock Protect-ProductionTree { $script:toolRefreshEvents.Add('protect') }
            Mock Assert-ProtectedProductionTree { $script:toolRefreshEvents.Add('verify') }
            Mock Get-ScheduledTask { [pscustomobject]@{ Actions=@() } }
            Mock Resolve-PowerShell7Executable { 'C:\PowerShell\pwsh.exe' }
            Mock Stop-ScheduledTask {}
            Mock Start-ScheduledTask {}
            Mock Set-ScheduledTask {
                $script:toolRefreshEvents.Add('switch')
                $script:toolRefreshAction = $Action
            }
            Mock Invoke-CheckedProcess {
                if ($ArgumentList -contains 'add') {
                    $worktree = $ArgumentList[-2]
                    $source = Join-Path $worktree 'ops\production\windows'
                    New-Item -ItemType Directory -Path (Join-Path $source 'modules') -Force | Out-Null
                    Set-Content -LiteralPath (Join-Path $source 'prod.ps1') -Value '# test source'
                    Set-Content -LiteralPath (Join-Path $source 'modules\Production.AutoDeploy.psm1') `
                        -Value '# test module'
                    $script:toolRefreshWorktree = $worktree
                    return ''
                }
                if ($ArgumentList -contains 'rev-parse') {
                    if ($ArgumentList[-1] -like '*:ops/production/windows') { return $treeSha }
                    return $sha
                }
                if ($ArgumentList -contains 'remove') {
                    Remove-Item -LiteralPath $script:toolRefreshWorktree -Recurse -Force
                    $script:toolRefreshEvents.Add('remove-worktree')
                    return ''
                }
                throw 'Unexpected Git operation.'
            }

            $result = Update-AutoDeployToolsFromOriginMain -Config $config

            $result.Sha | Should -Be $treeSha
            $result.SourceSha | Should -Be $sha
            $result.Switched | Should -BeTrue
            $script:toolRefreshEvents.IndexOf('remove-worktree') |
                Should -BeLessThan $script:toolRefreshEvents.IndexOf('switch')
            $script:toolRefreshEvents.IndexOf('verify') |
                Should -BeLessThan $script:toolRefreshEvents.IndexOf('switch')
            $script:toolRefreshEvents.IndexOf('verify-no-reparse') |
                Should -BeLessThan $script:toolRefreshEvents.IndexOf('switch')
            $script:toolRefreshAction.Arguments | Should -Match ([regex]::Escape("$programDataRoot\tools\versions\$treeSha\prod.ps1"))
            $script:toolRefreshAction.Arguments | Should -Match 'auto-deploy$'
            Test-Path -LiteralPath (Join-Path $programDataRoot "tools\versions\$treeSha\auto-deploy-tools.json") |
                Should -BeTrue
            Should -Invoke Stop-ScheduledTask -Times 0
            Should -Invoke Start-ScheduledTask -Times 0
        }
    }

    It 'cleans a partial tools stage and preserves the task action when copying fails' {
        InModuleScope Production.AutoDeploy {
            $programDataRoot = Join-Path $TestDrive 'stage-failure'
            $config = [pscustomobject]@{
                programDataRoot=$programDataRoot
                repositoryPath=(Join-Path $TestDrive 'repository')
                remote='origin'
                branch='main'
            }
            $sha = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
            $treeSha = '0123012301230123012301230123012301230123'
            Mock Assert-ProductionFixedRootBoundary {}
            Mock Get-RemoteMainSha { $sha }
            Mock Resolve-OriginMainRelease { $sha }
            Mock Assert-ProductionPathNotReparse {}
            Mock Protect-ProductionPath {}
            Mock Assert-ProductionTreeNotReparse {}
            Mock Protect-ProductionTree {}
            Mock Assert-ProtectedProductionTree {}
            Mock Get-ScheduledTask { [pscustomobject]@{ Actions=@() } }
            Mock Resolve-PowerShell7Executable { 'C:\PowerShell\pwsh.exe' }
            Mock Set-ScheduledTask { throw 'task action must not change after a partial stage.' }
            Mock Get-ChildItem { throw 'simulated copy failure' } -ModuleName Production.AutoDeploy
            Mock Invoke-CheckedProcess {
                if ($ArgumentList -contains 'add') {
                    $worktree = $ArgumentList[-2]
                    $source = Join-Path $worktree 'ops\production\windows'
                    New-Item -ItemType Directory -Path (Join-Path $source 'modules') -Force | Out-Null
                    Set-Content -LiteralPath (Join-Path $source 'prod.ps1') -Value '# test source'
                    Set-Content -LiteralPath (Join-Path $source 'modules\Production.AutoDeploy.psm1') `
                        -Value '# test module'
                    $script:failedStageWorktree = $worktree
                    return ''
                }
                if ($ArgumentList -contains 'rev-parse') {
                    if ($ArgumentList[-1] -like '*:ops/production/windows') { return $treeSha }
                    return $sha
                }
                if ($ArgumentList -contains 'remove') {
                    Remove-Item -LiteralPath $script:failedStageWorktree -Recurse -Force
                    return ''
                }
                throw 'Unexpected Git operation.'
            }

            { Update-AutoDeployToolsFromOriginMain -Config $config } |
                Should -Throw '*simulated copy failure*'

            Test-Path -LiteralPath (Join-Path $programDataRoot "tools\versions\$treeSha") |
                Should -BeFalse
            [IO.Directory]::GetDirectories((Join-Path $programDataRoot 'tools\versions')).Count |
                Should -Be 0
            Test-Path -LiteralPath $script:failedStageWorktree | Should -BeFalse
            Should -Invoke Set-ScheduledTask -Times 0
        }
    }

    It 'checks once and exits without keeping a console process alive' {
        InModuleScope Production.AutoDeploy {
            $config = [pscustomobject]@{ programDataRoot=$TestDrive }
            $script:loopEvents = [Collections.Generic.List[string]]::new()
            $deploymentLock = [pscustomobject]@{}
            $deploymentLock | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
                $script:loopEvents.Add('unlock-tools')
            }
            Mock Read-ProductionConfig { $config }
            Mock Initialize-AutoDeployStatusStore { $null }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=$deploymentLock }
            }
            Mock Update-AutoDeployToolsFromOriginMain {
                $script:loopEvents.Add('refresh-tools')
                [pscustomobject]@{ Sha='abcdefabcdefabcdefabcdefabcdefabcdefabcd'; Switched=$false }
            }
            Mock Invoke-AutoDeployOnce {
                if ($script:loopEvents.IndexOf('unlock-tools') -lt 0) {
                    throw 'Automatic release check started before tool refresh released the lock.'
                }
            }
            Mock Start-Sleep { throw 'A one-shot scheduled task must not sleep.' }

            { Start-AutoDeployLoop } | Should -Not -Throw

            Should -Invoke Invoke-AutoDeployOnce -Times 1 -Exactly -ParameterFilter { $Config -eq $config }
            $script:loopEvents.IndexOf('refresh-tools') | Should -BeLessThan $script:loopEvents.IndexOf('unlock-tools')
            Should -Invoke Start-Sleep -Times 0
        }
    }

    It 'surfaces tool refresh failure when its protected state cannot be persisted' {
        InModuleScope Production.AutoDeploy {
            $config = [pscustomobject]@{ programDataRoot=$TestDrive }
            $script:publishedOutcomes = [Collections.Generic.List[object]]::new()
            $script:failedToolRefreshErrors = [Collections.Generic.List[string]]::new()
            Mock Read-ProductionConfig { $config }
            Mock Initialize-AutoDeployStatusStore { Join-Path $TestDrive 'status' }
            Mock Assert-ProductionFixedRootBoundary {}
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new() }
            }
            Mock Update-AutoDeployToolsFromOriginMain {
                throw 'simulated tool refresh failure'
            }
            Mock Read-AutoDeployState {
                $state = New-AutoDeployState
                $state.toolRefreshStatus = 'SUCCEEDED'
                return $state
            }
            Mock Write-AutoDeployState {
                throw 'simulated state persistence failure'
            }
            Mock Publish-AutoDeployStatusBestEffort {
                $script:publishedOutcomes.Add([pscustomobject]@{
                    Outcome=$Outcome
                    FailureCategory=$FailureCategory
                    State=$State
                })
                return $true
            }
            Mock Invoke-AutoDeployOnce { throw 'stale bundle must not deploy' }
            Mock Add-Content {}

            $caught = $null
            try { Start-AutoDeployLoop }
            catch { $caught = $_.Exception }

            $caught | Should -Not -BeNullOrEmpty
            $caught | Should -BeOfType [AggregateException]
            $script:failedToolRefreshErrors.AddRange(
                [string[]]@($caught.InnerExceptions | ForEach-Object Message))
            $script:failedToolRefreshErrors | Should -Contain 'simulated tool refresh failure'
            $script:failedToolRefreshErrors | Should -Contain 'simulated state persistence failure'
            $script:publishedOutcomes[-1].Outcome | Should -Be 'CHECK_FAILED'
            $script:publishedOutcomes[-1].FailureCategory | Should -Be 'PROTECTED_PRECONDITION'
            $script:publishedOutcomes[-1].State.toolRefreshStatus | Should -Be 'FAILED'
            Should -Invoke Invoke-AutoDeployOnce -Times 0 -ModuleName Production.AutoDeploy
        }
    }

    It 'continues with the trusted bundle when a refresh failure is persisted' {
        InModuleScope Production.AutoDeploy {
            $config = [pscustomobject]@{ programDataRoot=$TestDrive }
            $script:storedAutoDeployState = New-AutoDeployState
            $script:storedAutoDeployState.toolRefreshStatus = 'SUCCEEDED'
            $script:releaseCheckStarted = $false
            Mock Read-ProductionConfig { $config }
            Mock Initialize-AutoDeployStatusStore { Join-Path $TestDrive 'status' }
            Mock Assert-ProductionFixedRootBoundary {}
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new() }
            }
            Mock Update-AutoDeployToolsFromOriginMain {
                throw 'simulated tool refresh failure'
            }
            Mock Read-AutoDeployState { return $script:storedAutoDeployState }
            Mock Write-AutoDeployState {
                $script:storedAutoDeployState = $State
            }
            Mock Publish-AutoDeployStatusBestEffort { return $true }
            Mock Invoke-AutoDeployOnce { $script:releaseCheckStarted = $true }

            { Start-AutoDeployLoop } | Should -Not -Throw

            $script:storedAutoDeployState.toolRefreshStatus | Should -Be 'FAILED'
            $script:releaseCheckStarted | Should -BeTrue
            Should -Invoke Invoke-AutoDeployOnce -Times 1 -ModuleName Production.AutoDeploy
        }
    }

    It 'registers the startup task with an absolute PowerShell 7 executable when PATH is empty' {
        InModuleScope Production.AutoDeploy {
            $originalPath = $env:PATH
            try {
                $env:PATH = ''
                Mock Assert-Administrator {}
                Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive; autoDeployPollSeconds=60 } }
                Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
                Mock Enter-ProductionFixedRootDeploymentLock {
                    [pscustomobject]@{ Lock=[IO.MemoryStream]::new() }
                }
                Mock Initialize-AutoDeployStatusStore { $null }
                Mock New-Item {}
                Mock Copy-Item {}
                Mock Move-ProductionAutoDeployToolsDirectory {}
                Mock Remove-Item {}
                $script:existingTaskStopped = $false
                Mock Stop-ScheduledTask { $script:existingTaskStopped = $true }
                Mock Get-ScheduledTask {
                    [pscustomobject]@{ TaskName='ChristopherBellAutoDeploy'; TaskPath='\'; State='Ready' }
                }
                Mock Export-ScheduledTask { '<Task>previous task</Task>' }
                Mock Disable-ScheduledTask {}
                Mock Enable-ScheduledTask {}
                Mock Register-ScheduledTask {
                    if (-not $script:existingTaskStopped) { throw 'Existing task must be stopped before registration.' }
                    $script:registeredTask = [pscustomobject]@{
                        Action=$Action
                        Triggers=@($Trigger)
                        Settings=$Settings
                    }
                }
                Mock Start-ScheduledTask {}

                Install-AutoDeployTask

                Should -Invoke Register-ScheduledTask -ParameterFilter {
                    $Action.Execute -eq (Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe')
                }
                $script:registeredTask.Action.Arguments | Should -Match '(?:^| )-NonInteractive(?: |$)'
                $script:registeredTask.Action.Arguments | Should -Match '(?:^| )-WindowStyle Hidden(?: |$)'
                $script:registeredTask.Action.Arguments | Should -Match ' auto-deploy$'
                $script:registeredTask.Triggers.Count | Should -Be 2
                @($script:registeredTask.Triggers | Where-Object {
                    $_.CimClass.CimClassName -eq 'MSFT_TaskTimeTrigger' -and
                    [string]$_.Repetition.Interval -eq 'PT1M'
                }).Count | Should -Be 1
                $script:registeredTask.Settings.Hidden | Should -BeTrue
                $script:registeredTask.Settings.StartWhenAvailable | Should -BeTrue
                $script:registeredTask.Settings.DisallowStartIfOnBatteries | Should -BeFalse
                $script:registeredTask.Settings.StopIfGoingOnBatteries | Should -BeFalse
                [string]$script:registeredTask.Settings.ExecutionTimeLimit | Should -Be 'PT2H'
                Should -Invoke Stop-ScheduledTask -Times 1 -ParameterFilter { $TaskName -eq 'ChristopherBellAutoDeploy' }
            }
            finally {
                $env:PATH = $originalPath
            }
        }
    }

    It 'does not overwrite tools or stop the task while a deployment is active' {
        InModuleScope Production.AutoDeploy {
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive; autoDeployPollSeconds=60 } }
            Mock Enter-DeploymentLock { throw 'A production deployment is already running.' }
            Mock Enter-ProductionFixedRootDeploymentLock {
                throw 'A production deployment is already running.'
            }
            Mock New-Item {}
            Mock Copy-Item {}
            Mock Stop-ScheduledTask {}
            Mock Register-ScheduledTask {}

            { Install-AutoDeployTask } | Should -Throw '*already running*'

            Should -Invoke Copy-Item -Times 0
            Should -Invoke Stop-ScheduledTask -Times 0
            Should -Invoke Register-ScheduledTask -Times 0
        }
    }

    It 're-enables the existing task and does not start it when it refuses to stop' {
        InModuleScope Production.AutoDeploy {
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive; autoDeployPollSeconds=60 } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=[IO.MemoryStream]::new() }
            }
            Mock Initialize-AutoDeployStatusStore { $null }
            Mock New-Item {}
            Mock Copy-Item {}
            Mock Stop-ScheduledTask {}
            Mock Get-ScheduledTask {
                [pscustomobject]@{ TaskName='ChristopherBellAutoDeploy'; TaskPath='\'; State='Running' }
            }
            Mock Export-ScheduledTask { '<Task>previous task</Task>' }
            Mock Disable-ScheduledTask {}
            Mock Enable-ScheduledTask {}
            Mock Start-Sleep {}
            $script:dateCall = 0
            Mock Get-Date {
                $script:dateCall++
                if ($script:dateCall -eq 1) { [datetime]'2026-07-12T10:00:00' }
                else { [datetime]'2026-07-12T10:00:31' }
            }
            Mock Register-ScheduledTask {}
            Mock Start-ScheduledTask {}

            { Install-AutoDeployTask } | Should -Throw '*did not stop*'

            Should -Invoke Register-ScheduledTask -Times 0
            Should -Invoke Enable-ScheduledTask -Times 1
            Should -Invoke Start-ScheduledTask -Times 0
        }
    }

    It 'keeps the previous poller bundle when staging copy fails' {
        InModuleScope Production.AutoDeploy {
            $programDataRoot = Join-Path $TestDrive 'bootstrap-copy-failure'
            $tools = Join-Path $programDataRoot 'tools'
            $config = [pscustomobject]@{
                programDataRoot=$programDataRoot
                autoDeployPollSeconds=60
            }
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            $oldMarker = Join-Path $tools 'known-good.txt'
            Set-Content -LiteralPath $oldMarker -Value 'previous bundle'
            Mock Initialize-AutoDeployStatusStore {}
            Mock Stop-ScheduledTask {}
            Mock Get-ScheduledTask {
                [pscustomobject]@{
                    TaskName='ChristopherBellAutoDeploy'
                    TaskPath='\'
                    State='Ready'
                    Actions=@('previous action')
                }
            }
            Mock Export-ScheduledTask { '<Task>previous task</Task>' }
            Mock Disable-ScheduledTask {}
            Mock Enable-ScheduledTask {}
            Mock Copy-Item { throw 'simulated tools copy failure' } -ModuleName Production.AutoDeploy
            Mock Register-ScheduledTask {}

            { Update-ProductionAutoDeployToolsUnderHeldLock -Config $config } |
                Should -Throw '*simulated tools copy failure*'

            Test-Path -LiteralPath $oldMarker | Should -BeTrue
            (Get-Content -LiteralPath $oldMarker -Raw).TrimEnd() | Should -Be 'previous bundle'
            Should -Invoke Disable-ScheduledTask -Times 0
            Should -Invoke Stop-ScheduledTask -Times 0
            Should -Invoke Register-ScheduledTask -Times 0
        }
    }

    It 'restores the old bundle and scheduled task when registration fails after switching' {
        InModuleScope Production.AutoDeploy {
            $programDataRoot = Join-Path $TestDrive 'bootstrap-register-failure'
            $tools = Join-Path $programDataRoot 'tools'
            $config = [pscustomobject]@{
                programDataRoot=$programDataRoot
                autoDeployPollSeconds=60
            }
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            $oldMarker = Join-Path $tools 'known-good.txt'
            Set-Content -LiteralPath $oldMarker -Value 'previous bundle'
            $script:registerCalls = 0
            $script:restoredTaskXml = $null
            Mock Initialize-AutoDeployStatusStore {}
            Mock Get-ScheduledTask {
                [pscustomobject]@{ TaskName='ChristopherBellAutoDeploy'; TaskPath='\'; State='Ready' }
            }
            Mock Export-ScheduledTask { '<Task>previous task</Task>' }
            Mock Disable-ScheduledTask {}
            Mock Stop-ScheduledTask {}
            Mock Enable-ScheduledTask {}
            Mock Register-ScheduledTask {
                $script:registerCalls++
                if ($Xml) {
                    $script:restoredTaskXml = $Xml
                    return
                }
                throw 'simulated new task registration failure'
            }

            { Update-ProductionAutoDeployToolsUnderHeldLock -Config $config } |
                Should -Throw '*simulated new task registration failure*'

            Test-Path -LiteralPath $oldMarker | Should -BeTrue
            (Get-Content -LiteralPath $oldMarker -Raw).TrimEnd() | Should -Be 'previous bundle'
            $script:restoredTaskXml | Should -Be '<Task>previous task</Task>'
            $script:registerCalls | Should -Be 2
        }
    }

    It 'stops a partially registered first-install task before restoring the old bundle' {
        InModuleScope Production.AutoDeploy {
            $programDataRoot = Join-Path $TestDrive 'bootstrap-first-register-failure'
            $tools = Join-Path $programDataRoot 'tools'
            $config = [pscustomobject]@{
                programDataRoot=$programDataRoot
                autoDeployPollSeconds=60
            }
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $tools 'known-good.txt') -Value 'previous bundle'
            $script:firstInstallTaskRegistered = $false
            Mock Initialize-AutoDeployStatusStore {}
            Mock Get-ScheduledTask {
                if ($script:firstInstallTaskRegistered) {
                    [pscustomobject]@{
                        TaskName='ChristopherBellAutoDeploy'
                        TaskPath='\'
                        State='Ready'
                    }
                }
            }
            Mock Register-ScheduledTask {
                $script:firstInstallTaskRegistered = $true
                throw 'simulated first-install registration failure'
            }
            Mock Disable-ScheduledTask {}
            Mock Stop-ScheduledTask {}
            Mock Unregister-ScheduledTask {}

            { Update-ProductionAutoDeployToolsUnderHeldLock -Config $config } |
                Should -Throw '*simulated first-install registration failure*'

            Test-Path -LiteralPath (Join-Path $tools 'known-good.txt') | Should -BeTrue
            Should -Invoke Disable-ScheduledTask -Times 1
            Should -Invoke Stop-ScheduledTask -Times 1
            Should -Invoke Unregister-ScheduledTask -Times 1
        }
    }

    It 'surfaces both registration and task cleanup failures during first-install recovery' {
        InModuleScope Production.AutoDeploy {
            $programDataRoot = Join-Path $TestDrive 'bootstrap-first-register-rollback-failure'
            $config = [pscustomobject]@{
                programDataRoot=$programDataRoot
                autoDeployPollSeconds=60
            }
            $script:firstInstallTaskRegistered = $false
            Mock Initialize-AutoDeployStatusStore {}
            Mock Get-ScheduledTask {
                if ($script:firstInstallTaskRegistered) {
                    [pscustomobject]@{
                        TaskName='ChristopherBellAutoDeploy'
                        TaskPath='\'
                        State='Ready'
                    }
                }
            }
            Mock Register-ScheduledTask {
                $script:firstInstallTaskRegistered = $true
                throw 'simulated first-install registration failure'
            }
            Mock Disable-ScheduledTask {}
            Mock Stop-ScheduledTask {}
            Mock Unregister-ScheduledTask { Write-Error 'simulated task cleanup failure' }

            $caught = $null
            try { Update-ProductionAutoDeployToolsUnderHeldLock -Config $config }
            catch { $caught = $_.Exception }

            $caught | Should -BeOfType [AggregateException]
            $caught.InnerExceptions.Count | Should -Be 2
            $caught.InnerExceptions[0].Message | Should -Be 'simulated first-install registration failure'
            $caught.InnerExceptions[1].Message | Should -Be 'simulated task cleanup failure'
        }
    }

    It 'replaces and protects the installed tools tree before SYSTEM task registration' {
        InModuleScope Production.AutoDeploy {
            $script:events = [Collections.Generic.List[string]]::new()
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive; autoDeployPollSeconds=60 } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            $deploymentLock = [pscustomobject]@{}
            $deploymentLock | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
                $script:events.Add('unlock')
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{ Lock=$deploymentLock }
            }
            Mock Initialize-AutoDeployStatusStore { $script:events.Add('initialize-status') }
            Mock Export-ScheduledTask { '<Task>previous task</Task>' }
            Mock Disable-ScheduledTask { $script:events.Add('disable') }
            Mock Stop-ScheduledTask { $script:events.Add('stop') }
            Mock Enable-ScheduledTask { $script:events.Add('enable') }
            Mock Get-ScheduledTask {
                [pscustomobject]@{ TaskName='ChristopherBellAutoDeploy'; TaskPath='\'; State='Ready' }
            }
            Mock Test-Path { $true }
            Mock Assert-ProductionPathNotReparse { $script:events.Add('reject-links') }
            Mock Assert-ProductionTreeNotReparse { $script:events.Add('reject-tree-links') }
            Mock Remove-Item { $script:events.Add('remove-backup') }
            Mock New-Item { $script:events.Add('create') }
            Mock Protect-ProductionPath { $script:events.Add('protect-root') }
            Mock Copy-Item { $script:events.Add('copy') }
            Mock Protect-ProductionTree { $script:events.Add('protect-tree') }
            Mock Assert-ProtectedProductionTree { $script:events.Add('verify-tree') }
            Mock Move-ProductionAutoDeployToolsDirectory {
                $script:events.Add('move')
                $script:events.Add("move:$SourcePath`:$DestinationPath")
            }
            Mock Register-ScheduledTask { $script:events.Add('register') }
            Mock Start-ScheduledTask { $script:events.Add('start') }

            Install-AutoDeployTask

            $script:events.IndexOf('initialize-status') | Should -BeLessThan $script:events.IndexOf('copy')
            $script:events.IndexOf('protect-tree') | Should -BeLessThan $script:events.IndexOf('verify-tree')
            $script:events.IndexOf('copy') | Should -BeLessThan $script:events.IndexOf('disable')
            $script:events.IndexOf('verify-tree') | Should -BeLessThan $script:events.IndexOf('disable')
            $script:events.IndexOf('disable') | Should -BeLessThan $script:events.IndexOf('stop')
            $script:events.IndexOf('stop') | Should -BeLessThan $script:events.IndexOf('move')
            $script:events.IndexOf('move') | Should -BeLessThan $script:events.IndexOf('register')
            $script:events.IndexOf('register') | Should -BeLessThan $script:events.IndexOf('enable')
            $script:events.IndexOf('enable') | Should -BeLessThan $script:events.IndexOf('unlock')
            $script:events.IndexOf('register') | Should -BeLessThan $script:events.IndexOf('unlock')
            $script:events.IndexOf('unlock') | Should -BeLessThan $script:events.IndexOf('start')
            Should -Invoke Move-ProductionAutoDeployToolsDirectory -Times 2
            Should -Invoke Assert-ProtectedProductionTree -Times 1 -ParameterFilter { $Path -like '*\tools' }
        }
    }
}

Describe 'automatic deployment task removal' {
    It 'keeps WhatIf read-only after the administrator check' {
        InModuleScope Production.AutoDeploy {
            Mock Assert-Administrator {}
            Mock Get-ProductionAutoDeployTask { throw 'task lookup should not be called' }

            Remove-AutoDeployTask -WhatIf | Should -Be 'Would remove the ChristopherBellAutoDeploy task.'
            Should -Invoke Assert-Administrator -Times 1 -Exactly
            Should -Invoke Get-ProductionAutoDeployTask -Times 0 -Exactly
        }
    }

    It 'treats an absent task as an idempotent no-op' {
        InModuleScope Production.AutoDeploy {
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=[IO.MemoryStream]::new() } }
            Mock Get-ProductionAutoDeployTask { $null }
            Mock Disable-ScheduledTask { throw 'disable should not be called' }
            Mock Stop-ProductionAutoDeployTask { throw 'stop should not be called' }
            Mock Unregister-ScheduledTask { throw 'unregister should not be called' }

            { Remove-AutoDeployTask } | Should -Not -Throw
            Should -Invoke Disable-ScheduledTask -Times 0 -Exactly
            Should -Invoke Stop-ProductionAutoDeployTask -Times 0 -Exactly
            Should -Invoke Unregister-ScheduledTask -Times 0 -Exactly
        }
    }

    It 'disables, stops, unregisters, and verifies a running task in order' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Running' }
            $script:events = [Collections.Generic.List[string]]::new()
            Mock Assert-Administrator {}
            $script:lock = [IO.MemoryStream]::new()
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock {
                $script:events.Add('lock')
                [pscustomobject]@{ Lock=$script:lock }
            }
            Mock Get-ProductionAutoDeployTask {
                $script:events.Add('lookup')
                $script:task
            }
            Mock Disable-ScheduledTask {
                $script:events.Add('disable')
                $script:task.State = 'Disabled'
            }
            Mock Stop-ProductionAutoDeployTask { $script:events.Add('stop') }
            Mock Unregister-ScheduledTask {
                $script:events.Add('unregister')
                $script:task = $null
            }

            Remove-AutoDeployTask

            $script:events.ToArray() | Should -Be @(
                'lock', 'lookup', 'disable', 'lookup', 'stop', 'unregister', 'lookup')
            $script:lock.CanRead | Should -BeFalse
            Should -Invoke Get-ProductionAutoDeployTask -Times 3 -Exactly
        }
    }

    It 'removes a disabled task without changing its state or issuing a stop' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Disabled' }
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=[IO.MemoryStream]::new() } }
            Mock Get-ProductionAutoDeployTask {
                $currentTask = $script:task
                $script:task = $null
                $currentTask
            }
            Mock Disable-ScheduledTask { throw 'disable should not be called' }
            Mock Stop-ProductionAutoDeployTask { throw 'stop should not be called' }
            Mock Unregister-ScheduledTask {}

            Remove-AutoDeployTask

            Should -Invoke Disable-ScheduledTask -Times 0 -Exactly
            Should -Invoke Stop-ProductionAutoDeployTask -Times 0 -Exactly
            Should -Invoke Unregister-ScheduledTask -Times 1 -Exactly
        }
    }

    It 'surfaces a stop failure and restores the prior enabled state' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Running' }
            Mock Assert-Administrator {}
            $script:lock = [IO.MemoryStream]::new()
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=$script:lock } }
            Mock Get-ProductionAutoDeployTask { $script:task }
            Mock Disable-ScheduledTask { $script:task.State = 'Disabled' }
            Mock Stop-ProductionAutoDeployTask { throw 'simulated stop failure' }
            Mock Unregister-ScheduledTask {}
            Mock Enable-ScheduledTask { $script:task.State = 'Ready' }

            { Remove-AutoDeployTask } | Should -Throw '*simulated stop failure*'
            $script:task.State | Should -Be 'Ready'
            $script:lock.CanRead | Should -BeFalse
            Should -Invoke Enable-ScheduledTask -Times 1 -Exactly
            Should -Invoke Unregister-ScheduledTask -Times 0 -Exactly
        }
    }

    It 'does not unregister when the scheduler fails to disable the task' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Ready' }
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=[IO.MemoryStream]::new() } }
            Mock Get-ProductionAutoDeployTask { $script:task }
            Mock Disable-ScheduledTask {}
            Mock Enable-ScheduledTask {}
            Mock Unregister-ScheduledTask {}

            { Remove-AutoDeployTask } | Should -Throw '*task could not be disabled*'
            Should -Invoke Unregister-ScheduledTask -Times 0 -Exactly
            Should -Invoke Enable-ScheduledTask -Times 0 -Exactly
        }
    }

    It 'surfaces an unregister failure and restores the prior enabled state' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Ready' }
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=[IO.MemoryStream]::new() } }
            Mock Get-ProductionAutoDeployTask { $script:task }
            Mock Disable-ScheduledTask { $script:task.State = 'Disabled' }
            Mock Unregister-ScheduledTask { throw 'simulated unregister failure' }
            Mock Enable-ScheduledTask { $script:task.State = 'Ready' }

            { Remove-AutoDeployTask } | Should -Throw '*simulated unregister failure*'
            $script:task.State | Should -Be 'Ready'
            Should -Invoke Enable-ScheduledTask -Times 1 -Exactly
        }
    }

    It 'rejects a scheduler no-op and reports when enabled-state recovery also fails' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Ready' }
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=[IO.MemoryStream]::new() } }
            Mock Get-ProductionAutoDeployTask { $script:task }
            Mock Disable-ScheduledTask { $script:task.State = 'Disabled' }
            Mock Unregister-ScheduledTask {}
            Mock Enable-ScheduledTask { throw 'simulated state recovery failure' }

            $caught = $null
            try { Remove-AutoDeployTask }
            catch { $caught = $_.Exception }

            $caught | Should -BeOfType [System.AggregateException]
            $caught.InnerExceptions.Count | Should -Be 2
            $caught.InnerExceptions[0].Message | Should -Match 'task remains registered'
            $caught.InnerExceptions[1].Message | Should -Match 'simulated state recovery failure'
        }
    }

    It 'verifies the task was re-enabled after a scheduler no-op' {
        InModuleScope Production.AutoDeploy {
            $script:task = [pscustomobject]@{ State='Ready' }
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { [pscustomobject]@{ Lock=[IO.MemoryStream]::new() } }
            Mock Get-ProductionAutoDeployTask { $script:task }
            Mock Disable-ScheduledTask { $script:task.State = 'Disabled' }
            Mock Unregister-ScheduledTask {}
            Mock Enable-ScheduledTask {}

            $caught = $null
            try { Remove-AutoDeployTask }
            catch { $caught = $_.Exception }

            $caught | Should -BeOfType [System.AggregateException]
            $caught.InnerExceptions.Count | Should -Be 2
            $caught.InnerExceptions[1].Message | Should -Match 'prior enabled state was not restored'
        }
    }

    It 'does not inspect or change the task when deployment-lock acquisition fails' {
        InModuleScope Production.AutoDeploy {
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev' } }
            Mock Enter-ProductionFixedRootDeploymentLock { throw 'simulated deployment lock failure' }
            Mock Get-ProductionAutoDeployTask { throw 'task lookup should not be called' }
            Mock Disable-ScheduledTask { throw 'disable should not be called' }
            Mock Stop-ProductionAutoDeployTask { throw 'stop should not be called' }
            Mock Unregister-ScheduledTask { throw 'unregister should not be called' }

            { Remove-AutoDeployTask } | Should -Throw '*simulated deployment lock failure*'
            Should -Invoke Get-ProductionAutoDeployTask -Times 0 -Exactly
            Should -Invoke Disable-ScheduledTask -Times 0 -Exactly
            Should -Invoke Stop-ProductionAutoDeployTask -Times 0 -Exactly
            Should -Invoke Unregister-ScheduledTask -Times 0 -Exactly
        }
    }
}
