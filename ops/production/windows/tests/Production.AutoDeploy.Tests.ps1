Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.AutoDeploy.psm1') -Force

Describe 'automatic origin main deployment' {
    BeforeEach {
        $script:config = [pscustomobject]@{ programDataRoot=$TestDrive; repositoryPath=$TestDrive; remote='origin'; branch='main'; autoDeployFailureBackoffSeconds=900 }
        Mock Assert-ProductionPathNotReparse {} -ModuleName Production.AutoDeploy
        Mock Assert-ProductionTreeNotReparse {} -ModuleName Production.AutoDeploy
        Mock Protect-ProductionPath {} -ModuleName Production.AutoDeploy
        Mock Protect-ProductionTree {} -ModuleName Production.AutoDeploy
        Mock Assert-ProtectedProductionTree {} -ModuleName Production.AutoDeploy
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

    It 'registers the startup task with an absolute PowerShell 7 executable when PATH is empty' {
        InModuleScope Production.AutoDeploy {
            $originalPath = $env:PATH
            try {
                $env:PATH = ''
                Mock Assert-Administrator {}
                Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive } }
                Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
                Mock New-Item {}
                Mock Copy-Item {}
                $script:existingTaskStopped = $false
                Mock Stop-ScheduledTask { $script:existingTaskStopped = $true }
                Mock Get-ScheduledTask { [pscustomobject]@{ State='Ready' } }
                Mock Register-ScheduledTask {
                    if (-not $script:existingTaskStopped) { throw 'Existing task must be stopped before registration.' }
                }
                Mock Start-ScheduledTask {}

                Install-AutoDeployTask

                Should -Invoke Register-ScheduledTask -ParameterFilter {
                    $Action.Execute -eq (Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe')
                }
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
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive } }
            Mock Enter-DeploymentLock { throw 'A production deployment is already running.' }
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

    It 'does not register or restart when the existing task refuses to stop' {
        InModuleScope Production.AutoDeploy {
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock New-Item {}
            Mock Copy-Item {}
            Mock Stop-ScheduledTask {}
            Mock Get-ScheduledTask { [pscustomobject]@{ State='Running' } }
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
            Should -Invoke Start-ScheduledTask -Times 0
        }
    }

    It 'replaces and protects the installed tools tree before SYSTEM task registration' {
        InModuleScope Production.AutoDeploy {
            $script:events = [Collections.Generic.List[string]]::new()
            Mock Assert-Administrator {}
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot=$TestDrive } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Stop-ScheduledTask { $script:events.Add('stop') }
            Mock Get-ScheduledTask { [pscustomobject]@{ State='Ready' } }
            Mock Test-Path { $true }
            Mock Assert-ProductionPathNotReparse { $script:events.Add('reject-links') }
            Mock Assert-ProductionTreeNotReparse { $script:events.Add('reject-tree-links') }
            Mock Remove-Item { $script:events.Add('remove') }
            Mock New-Item { $script:events.Add('create') }
            Mock Protect-ProductionPath { $script:events.Add('protect-root') }
            Mock Copy-Item { $script:events.Add('copy') }
            Mock Protect-ProductionTree { $script:events.Add('protect-tree') }
            Mock Assert-ProtectedProductionTree { $script:events.Add('verify-tree') }
            Mock Register-ScheduledTask { $script:events.Add('register') }
            Mock Start-ScheduledTask { $script:events.Add('start') }

            Install-AutoDeployTask

            $script:events.IndexOf('protect-root') | Should -BeLessThan $script:events.IndexOf('remove')
            $script:events.IndexOf('stop') | Should -BeLessThan $script:events.IndexOf('remove')
            $script:events.IndexOf('remove') | Should -BeLessThan $script:events.IndexOf('copy')
            $script:events.IndexOf('protect-tree') | Should -BeLessThan $script:events.IndexOf('verify-tree')
            $script:events.IndexOf('verify-tree') | Should -BeLessThan $script:events.IndexOf('register')
            Should -Invoke Remove-Item -Times 1 -ParameterFilter { $LiteralPath -like '*\tools' -and $Recurse }
            Should -Invoke Assert-ProtectedProductionTree -Times 1 -ParameterFilter { $Path -like '*\tools' }
        }
    }
}
