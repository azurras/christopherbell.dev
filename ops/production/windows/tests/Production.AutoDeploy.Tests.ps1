Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.AutoDeploy.psm1') -Force

Describe 'automatic origin main deployment' {
    BeforeEach {
        $script:config = [pscustomobject]@{ programDataRoot=$TestDrive; repositoryPath=$TestDrive; remote='origin'; branch='main'; autoDeployFailureBackoffSeconds=900 }
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
                Mock New-Item {}
                Mock Copy-Item {}
                Mock Register-ScheduledTask {}
                Mock Start-ScheduledTask {}

                Install-AutoDeployTask

                Should -Invoke Register-ScheduledTask -ParameterFilter {
                    $Action.Execute -eq (Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe')
                }
            }
            finally {
                $env:PATH = $originalPath
            }
        }
    }
}
