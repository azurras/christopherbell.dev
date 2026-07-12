Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Operations.psm1') -Force

Describe 'native Windows production operations' {
    InModuleScope Production.Operations {
        BeforeAll {
            function New-ValidStartupTask {
                param([string]$ProgramDataRoot = 'C:\ProgramData\christopherbell.dev')
                [pscustomobject]@{
                    State = 'Ready'
                    Principal = [pscustomobject]@{ UserId='SYSTEM'; LogonType='ServiceAccount'; RunLevel='Highest' }
                    Triggers = @([pscustomobject]@{ CimClass=[pscustomobject]@{ CimClassName='MSFT_TaskBootTrigger' } })
                    Actions = @([pscustomobject]@{
                        Execute = Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'
                        Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$ProgramDataRoot\tools\prod.ps1`" auto-deploy"
                    })
                    Settings = [pscustomobject]@{
                        Enabled=$true
                        ExecutionTimeLimit='PT0S'
                        RestartCount=3
                        RestartInterval='PT1M'
                        MultipleInstances='IgnoreNew'
                    }
                }
            }
        }

        It 'refuses rollback unless both release junctions exist' {
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data' } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget { $null }
            { Invoke-ProductionRollback -WhatIf } | Should -Throw '*Both current and previous*'
        }

        It 'reports cloudflared with native website and MongoDB services' {
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 } }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-JunctionTarget { $null }
            Mock Get-NetTCPConnection { [pscustomobject]@{ OwningProcess=42 } }
            (Get-ProductionStatus).CloudflaredService | Should -Be 'Running'
        }

        It 'rejects startup when a required service is not automatic' {
            Mock Read-ProductionConfig { [pscustomobject]@{ publicUrl='https://www.christopherbell.dev/'; productionPort=8080 } }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Manual' } }
            { Test-ProductionStartup } | Should -Throw '*Automatic*'
        }

        It 'accepts the complete automatic deployment startup contract' {
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task (New-ValidStartupTask) -Config $config } | Should -Not -Throw
        }

        It 'rejects an automatic deployment task with the wrong principal' {
            $task = New-ValidStartupTask
            $task.Principal.UserId = 'Christopher'
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*SYSTEM*'
        }

        It 'rejects an automatic deployment task without a boot trigger' {
            $task = New-ValidStartupTask
            $task.Triggers[0].CimClass.CimClassName = 'MSFT_TaskLogonTrigger'
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*startup trigger*'
        }

        It 'rejects an automatic deployment task with the wrong action' {
            $task = New-ValidStartupTask
            $task.Actions[0].Execute = 'pwsh.exe'
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*PowerShell 7 executable*'
        }

        It 'rejects an automatic deployment task without restart resilience' {
            $task = New-ValidStartupTask
            $task.Settings.RestartCount = 0
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*restart*'
        }

        It 'rejects automatic deployment polling slower than one minute' {
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=61 }
            { Assert-AutoDeployTaskContract -Task (New-ValidStartupTask) -Config $config } | Should -Throw '*60 seconds*'
        }

        It 'uses attached IPv4 URI and archive arguments for native backups' {
            $dump = Get-NativeMongoDumpArguments 'A:\backups\native.archive.gz'
            $restore = Get-NativeMongoRestoreDryRunArguments 'A:\backups\native.archive.gz'
            $dump | Should -Contain '--uri=mongodb://127.0.0.1:27017'
            $dump | Should -Contain '--archive=A:\backups\native.archive.gz'
            $restore | Should -Contain '--uri=mongodb://127.0.0.1:27017'
            $restore | Should -Contain '--archive=A:\backups\native.archive.gz'
            $dump | Should -Not -Contain '--archive'
            $restore | Should -Not -Contain '--archive'
        }
    }
}
