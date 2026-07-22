Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Sensors.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Operations.psm1') -Force

Describe 'native Windows production operations' {
    InModuleScope Production.Operations {
        BeforeAll {
            function New-ValidStartupTask {
                param([string]$ProgramDataRoot = 'C:\ProgramData\christopherbell.dev')
                [pscustomobject]@{
                    State = 'Ready'
                    Principal = [pscustomobject]@{ UserId='SYSTEM'; LogonType='ServiceAccount'; RunLevel='Highest' }
                    Triggers = @([pscustomobject]@{ Enabled=$true; CimClass=[pscustomobject]@{ CimClassName='MSFT_TaskBootTrigger' } })
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

        It 'uses the controlled stop for rollback and restoration' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-Service { }
            Mock Stop-ProductionWebsiteService { }
            $junctionWrites = [System.Collections.Generic.List[string]]::new()
            Mock Set-AtomicJunction {
                param($Config, $Path, $Target)
                [void]$junctionWrites.Add("$Path=>$Target")
            }
            Mock Start-Service { }
            $script:rollbackVerification = 0
            Mock Test-ProductionEndpoints {
                if ($script:rollbackVerification++ -eq 0) {
                    throw 'rollback verification failed'
                }
            }

            {
                Invoke-ProductionRollback
            } | Should -Throw '*rollback verification failed*'

            Should -Invoke Stop-ProductionWebsiteService -Times 2 -Exactly -ParameterFilter {
                $ProductionPort -eq 8080
            }
            ($junctionWrites -join '|') | Should -Be (
                'C:\data\current=>C:\data\releases\previous|' +
                'C:\data\previous=>C:\data\releases\current|' +
                'C:\data\current=>C:\data\releases\current|' +
                'C:\data\previous=>C:\data\releases\previous')
        }

        It 'preserves rollback and restoration failures together' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { }
            Mock Set-AtomicJunction { }
            Mock Start-Service { }
            $script:rollbackVerification = 0
            Mock Test-ProductionEndpoints {
                if ($script:rollbackVerification++ -eq 0) {
                    throw 'rollback verification failed'
                }
                throw 'release restoration failed'
            }

            $failure = $null
            try {
                Invoke-ProductionRollback
            } catch {
                $failure = $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            $failure.Message | Should -Match '^Production rollback and release restoration both failed\.'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Be 'rollback verification failed'
            $failure.InnerExceptions[1].Message | Should -Be 'release restoration failed'
        }

        It 'restores both original junctions when the <Name> forward junction write fails' -TestCases @(
            @{ Name = 'first'; FailingWrite = 1; FailureMessage = 'forward current write failed' }
            @{ Name = 'second'; FailingWrite = 2; FailureMessage = 'forward previous write failed' }
        ) {
            param($Name, $FailingWrite, $FailureMessage)
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { }
            $junctionWrites = [System.Collections.Generic.List[string]]::new()
            $script:junctionWriteAttempt = 0
            Mock Set-AtomicJunction {
                param($Config, $Path, $Target)
                $script:junctionWriteAttempt++
                [void]$junctionWrites.Add("$Path=>$Target")
                if ($script:junctionWriteAttempt -eq $FailingWrite) {
                    throw $FailureMessage
                }
            }
            Mock Start-Service { }
            Mock Test-ProductionEndpoints { }

            {
                Invoke-ProductionRollback
            } | Should -Throw "*$FailureMessage*"

            @($junctionWrites)[-2] | Should -Be (
                'C:\data\current=>C:\data\releases\current')
            @($junctionWrites)[-1] | Should -Be (
                'C:\data\previous=>C:\data\releases\previous')
            Should -Invoke Stop-ProductionWebsiteService -Times 2 -Exactly
            Should -Invoke Start-Service -Times 1 -Exactly
        }

        It 'preserves a forward junction failure and attempts both originals when restoration fails' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { }
            $junctionWrites = [System.Collections.Generic.List[string]]::new()
            $script:junctionWriteAttempt = 0
            Mock Set-AtomicJunction {
                param($Config, $Path, $Target)
                $script:junctionWriteAttempt++
                [void]$junctionWrites.Add("$Path=>$Target")
                if ($script:junctionWriteAttempt -eq 2) {
                    throw 'forward previous write failed'
                }
                if ($Path -like '*\current' -and
                    $Target -eq 'C:\data\releases\current') {
                    throw 'restore current write failed'
                }
            }
            Mock Start-Service { }
            Mock Test-ProductionEndpoints { }

            $failure = try {
                Invoke-ProductionRollback
                $null
            } catch {
                $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Be 'forward previous write failed'
            $failure.InnerExceptions[1].Message | Should -Match '^Failed to restore original release junctions\.'
            $failure.InnerExceptions[1].InnerException.Message | Should -Be 'restore current write failed'
            $junctionWrites | Should -Contain (
                'C:\data\previous=>C:\data\releases\previous')
            Should -Invoke Start-Service -Times 0
        }

        It 'blocks manual rollback junction changes and restart when the controlled stop fails' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { throw 'recovery restoration failed' }
            Mock Set-AtomicJunction { }
            Mock Start-Service { }

            {
                Invoke-ProductionRollback
            } | Should -Throw '*recovery restoration failed*'

            Should -Invoke Set-AtomicJunction -Times 0
            Should -Invoke Start-Service -Times 0
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

        It 'rejects startup verification without protected sensor state' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Wait-HttpStatus { 200 }

            { Test-ProductionStartup } | Should -Throw '*sensorLibrariesEnabled*'
        }

        It 'reports the protected sensor state during startup verification' -TestCases @(
            @{ Enabled=$false }
            @{ Enabled=$true }
        ) {
            param($Enabled)
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                    sensorLibrariesEnabled=$Enabled
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Wait-HttpStatus { 200 }
            Mock Assert-ProductionSensorReady { 61.5 }

            (Test-ProductionStartup).SensorLibrariesEnabled | Should -Be $Enabled
        }

        It 'requires a live verified CPU temperature when sensors are enabled' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                    sensorLibrariesEnabled=$true
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Wait-HttpStatus { 200 }
            Mock Assert-ProductionSensorReady { 61.5 }

            (Test-ProductionStartup).CpuTemperatureCelsius | Should -Be 61.5

            Should -Invoke Assert-ProductionSensorReady -Times 1 -ParameterFilter {
                $Root -eq 'C:\ProgramData\christopherbell.dev'
            }
        }

        It 'does not require the native provider while sensors are disabled' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                    sensorLibrariesEnabled=$false
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Wait-HttpStatus { 200 }
            Mock Assert-ProductionSensorReady { throw 'must not run' }

            (Test-ProductionStartup).CpuTemperatureCelsius | Should -BeNullOrEmpty
            Should -Invoke Assert-ProductionSensorReady -Times 0
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

        It 'rejects an automatic deployment task with a disabled boot trigger' {
            $task = New-ValidStartupTask
            $task.Triggers[0].Enabled = $false
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*enabled startup trigger*'
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
