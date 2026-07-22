Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force

Describe 'native Windows deployment' {
    InModuleScope Production.Deploy {
        BeforeAll {
            function New-ServiceStateStub {
                param(
                    [string]$Status = 'Stopped',
                    [switch]$WaitFails
                )
                $service = [pscustomobject]@{
                    Status = $Status
                    WaitFails = [bool]$WaitFails
                }
                $service | Add-Member -MemberType ScriptMethod -Name WaitForStatus -Value {
                    param($ExpectedStatus, $Timeout)
                    $null = $ExpectedStatus
                    $null = $Timeout
                    if ($this.WaitFails) {
                        throw [System.TimeoutException]::new('simulated service wait timeout')
                    }
                    $this.Status = 'Stopped'
                }
                $service | Add-Member -MemberType ScriptMethod -Name Refresh -Value { }
                return $service
            }

            function New-RecoveryPolicyQueryOutput {
                param(
                    [ValidateSet('Suspended','Normal')]
                    [string]$Policy,
                    [Nullable[int]]$ResetPeriodSeconds
                )

                $effectiveResetPeriodSeconds = if ($null -ne $ResetPeriodSeconds) {
                    [int]$ResetPeriodSeconds
                } elseif ($Policy -eq 'Suspended') {
                    0
                } else {
                    3600
                }
                $failureActions = if ($Policy -eq 'Normal') {
                    @(
                        'FAILURE_ACTIONS              : RESTART -- Delay = 10000 milliseconds.',
                        '                               RESTART -- Delay = 30000 milliseconds.'
                    )
                } else {
                    @('FAILURE_ACTIONS              :')
                }
                return @(
                    '[SC] QueryServiceConfig2 SUCCESS',
                    '',
                    'SERVICE_NAME: ChristopherBellDev',
                    "        RESET_PERIOD (in seconds)    : $effectiveResetPeriodSeconds",
                    '        REBOOT_MESSAGE               :',
                    '        COMMAND_LINE                 :'
                ) + $failureActions -join [Environment]::NewLine
            }

            function Invoke-RecoveryCommandDouble {
                param($FilePath, $ArgumentList, $TimeoutMilliseconds)
                $null = $FilePath
                $null = $TimeoutMilliseconds
                $command = [string]$ArgumentList[0]
                if ($command -eq 'failure') {
                    $policy = if ([string]::IsNullOrEmpty([string]$ArgumentList[-1])) {
                        'Suspended'
                    } else {
                        'Normal'
                    }
                    [void]$script:recoveryCommands.Add("failure:$($ArgumentList[-1])")
                    $mode = if ($policy -eq 'Suspended') {
                        $script:suspendedRecoveryMode
                    } else {
                        $script:normalRecoveryMode
                    }
                    if ($mode -eq 'MutationFailure') {
                        throw "simulated $($policy.ToLowerInvariant()) mutation failure"
                    }
                    if ($mode -eq 'MutationTimeout') {
                        throw [System.TimeoutException]::new(
                            "simulated $($policy.ToLowerInvariant()) mutation timeout")
                    }
                    $script:configuredRecoveryPolicy = $policy
                    $script:configuredRecoveryResetPeriodSeconds = [int]$ArgumentList[3]
                    return ''
                }
                if ($command -eq 'qfailure') {
                    [void]$script:recoveryCommands.Add('qfailure')
                    $mode = if ($script:configuredRecoveryPolicy -eq 'Suspended') {
                        $script:suspendedRecoveryMode
                    } else {
                        $script:normalRecoveryMode
                    }
                    if ($mode -eq 'QueryFailure') {
                        throw "simulated $($script:configuredRecoveryPolicy.ToLowerInvariant()) query failure"
                    }
                    if ($mode -eq 'QueryTimeout') {
                        throw [System.TimeoutException]::new(
                            "simulated $($script:configuredRecoveryPolicy.ToLowerInvariant()) query timeout")
                    }
                    if ($mode -eq 'Mismatch') {
                        $oppositePolicy = if ($script:configuredRecoveryPolicy -eq 'Normal') {
                            'Suspended'
                        } else {
                            'Normal'
                        }
                        return New-RecoveryPolicyQueryOutput -Policy $oppositePolicy
                    }
                    if ($mode -eq 'ResetMismatch') {
                        return New-RecoveryPolicyQueryOutput `
                            -Policy $script:configuredRecoveryPolicy `
                            -ResetPeriodSeconds 42
                    }
                    if ($mode -eq 'DuplicateReset') {
                        return @(
                            New-RecoveryPolicyQueryOutput `
                                -Policy $script:configuredRecoveryPolicy `
                                -ResetPeriodSeconds $script:configuredRecoveryResetPeriodSeconds
                            '        RESET_PERIOD (in seconds)    : 42'
                        ) -join [Environment]::NewLine
                    }
                    if ($mode -eq 'DuplicateFailureActions') {
                        return @(
                            New-RecoveryPolicyQueryOutput `
                                -Policy $script:configuredRecoveryPolicy `
                                -ResetPeriodSeconds $script:configuredRecoveryResetPeriodSeconds
                            '        FAILURE_ACTIONS              : RESUME'
                        ) -join [Environment]::NewLine
                    }
                    return New-RecoveryPolicyQueryOutput `
                        -Policy $script:configuredRecoveryPolicy `
                        -ResetPeriodSeconds $script:configuredRecoveryResetPeriodSeconds
                }
                throw "Unexpected recovery command: $command"
            }
        }

        It 'accepts the Windows-normalized suspended policy with reset period zero' {
            $queryOutput = New-RecoveryPolicyQueryOutput `
                -Policy Suspended -ResetPeriodSeconds 0

            {
                Assert-ProductionWebsiteRecoveryPolicy `
                    -Policy Suspended -QueryOutput $queryOutput
            } | Should -Not -Throw
        }

        It 'rejects a nonzero reset period for suspended recovery' {
            $queryOutput = New-RecoveryPolicyQueryOutput `
                -Policy Suspended -ResetPeriodSeconds 42

            {
                Assert-ProductionWebsiteRecoveryPolicy `
                    -Policy Suspended -QueryOutput $queryOutput
            } | Should -Throw '*Expected reset period 0*received reset period 42*'
        }

        It 'rejects reset period zero for normal recovery' {
            $queryOutput = New-RecoveryPolicyQueryOutput `
                -Policy Normal -ResetPeriodSeconds 0

            {
                Assert-ProductionWebsiteRecoveryPolicy `
                    -Policy Normal -QueryOutput $queryOutput
            } | Should -Throw '*Expected reset period 3600*received reset period 0*'
        }

        It 'accepts one labeled normal restart followed by one unlabeled restart' {
            $queryOutput = New-RecoveryPolicyQueryOutput -Policy Normal

            {
                Assert-ProductionWebsiteRecoveryPolicy `
                    -Policy Normal -QueryOutput $queryOutput
            } | Should -Not -Throw
        }

        It 'rejects contradictory duplicate reset-period fields' {
            $queryOutput = @(
                New-RecoveryPolicyQueryOutput -Policy Suspended
                '        RESET_PERIOD (in seconds)    : 42'
            ) -join [Environment]::NewLine

            {
                Assert-ProductionWebsiteRecoveryPolicy `
                    -Policy Suspended -QueryOutput $queryOutput
            } | Should -Throw '*Suspended recovery policy verification failed*'
        }

        It 'rejects an empty failure-actions field followed by an unrecognized duplicate field' {
            $queryOutput = @(
                New-RecoveryPolicyQueryOutput -Policy Suspended
                '        FAILURE_ACTIONS              : RESUME'
            ) -join [Environment]::NewLine

            {
                Assert-ProductionWebsiteRecoveryPolicy `
                    -Policy Suspended -QueryOutput $queryOutput
            } | Should -Throw '*Suspended recovery policy verification failed*'
        }

        It 'bounds checked processes that do not exit' {
            $slowPowerShell = Join-Path $PSHOME 'pwsh.exe'
            $watch = [Diagnostics.Stopwatch]::StartNew()

            {
                Invoke-BoundedCheckedProcess `
                    -FilePath $slowPowerShell `
                    -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 10') `
                    -TimeoutMilliseconds 100
            } | Should -Throw '*did not exit within 100 milliseconds*'

            $watch.Elapsed | Should -BeLessThan ([timespan]::FromSeconds(5))
        }

        Context 'controlled website service stop' {
            BeforeEach {
                $script:configuredRecoveryPolicy = 'Normal'
                $script:configuredRecoveryResetPeriodSeconds = 3600
                $script:suspendedRecoveryMode = $null
                $script:normalRecoveryMode = $null
                $script:recoveryCommands = [System.Collections.Generic.List[string]]::new()
                Mock Invoke-BoundedCheckedProcess {
                    param($FilePath, $ArgumentList, $TimeoutMilliseconds)
                    Invoke-RecoveryCommandDouble @PSBoundParameters
                }
                Mock Invoke-CheckedProcess {
                    param($FilePath, $ArgumentList)
                    Invoke-RecoveryCommandDouble @PSBoundParameters
                }
                Mock Stop-Service { throw 'unexpected real stop seam reached' }
                Mock Get-Service { throw 'unexpected real service query seam reached' }
                Mock Get-NetTCPConnection { throw 'unexpected real TCP query seam reached' }
                Mock Start-Service { throw 'unexpected real start seam reached' }
                Mock Set-AtomicJunction { throw 'unexpected real junction seam reached' }
                Mock Test-ProductionEndpoints { throw 'unexpected real endpoint seam reached' }
            }

        It 'accepts the WinSW stop exception only after stopped service and closed port postconditions pass' {
            Mock Stop-Service { throw 'simulated WinSW invalid handle failure' }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080 -PortTimeoutMilliseconds 1
            } | Should -Not -Throw

            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|qfailure|failure:restart/10000/restart/30000|qfailure')
            Should -Invoke Invoke-BoundedCheckedProcess -Times 4 -Exactly -ParameterFilter {
                $FilePath -eq 'sc.exe' -and $TimeoutMilliseconds -eq 5000
            }
            Should -Invoke Invoke-BoundedCheckedProcess -Times 1 -Exactly -ParameterFilter {
                $ArgumentList[0] -eq 'failure' -and
                $ArgumentList[1] -eq 'ChristopherBellDev' -and
                $ArgumentList[2] -eq 'reset=' -and
                $ArgumentList[3] -eq '0' -and
                $ArgumentList[4] -eq 'actions=' -and
                [string]::IsNullOrEmpty([string]$ArgumentList[5])
            }
            Should -Invoke Invoke-BoundedCheckedProcess -Times 1 -Exactly -ParameterFilter {
                $ArgumentList[0] -eq 'failure' -and
                $ArgumentList[1] -eq 'ChristopherBellDev' -and
                $ArgumentList[2] -eq 'reset=' -and
                $ArgumentList[3] -eq '3600' -and
                $ArgumentList[4] -eq 'actions=' -and
                $ArgumentList[5] -eq 'restart/10000/restart/30000'
            }
            Should -Invoke Invoke-BoundedCheckedProcess -Times 2 -Exactly -ParameterFilter {
                $ArgumentList.Count -eq 2 -and
                $ArgumentList[0] -eq 'qfailure' -and
                $ArgumentList[1] -eq 'ChristopherBellDev'
            }
        }

        It 'preserves stop request and failed postcondition causes in order' {
            Mock Stop-Service { throw 'simulated WinSW invalid handle failure' }
            Mock Get-Service { New-ServiceStateStub -Status Running -WaitFails }

            $failure = try {
                Stop-ProductionWebsiteService -ProductionPort 8080 -ServiceTimeoutSeconds 1
                $null
            } catch {
                $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Be 'simulated WinSW invalid handle failure'
            $failure.InnerExceptions[1].Message | Should -Match '^ChristopherBellDev did not reach Stopped'
        }

        It 'fails closed when the production port remains open' {
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection {
                [pscustomobject]@{ LocalPort = 8080; OwningProcess = 42 }
            }
            Mock Start-Sleep { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080 -PortTimeoutMilliseconds 1
            } | Should -Throw '*port 8080 remained open*'
        }

        It 'fails closed when the production port cannot be inspected' {
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { throw 'simulated TCP inspection failure' }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*inspect production port 8080*'
        }

        It 'restores normal policy and avoids stop when suspension mutation fails' {
            $script:suspendedRecoveryMode = 'MutationFailure'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*suspend website service recovery*mutation*'

            Should -Invoke Stop-Service -Times 0
            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|failure:restart/10000/restart/30000|qfailure')
        }

        It 'restores normal policy and avoids stop when suspension mutation times out' {
            $script:suspendedRecoveryMode = 'MutationTimeout'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*suspend website service recovery*timeout*'

            Should -Invoke Stop-Service -Times 0
            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|failure:restart/10000/restart/30000|qfailure')
        }

        It 'restores normal policy and avoids stop when suspension query fails' {
            $script:suspendedRecoveryMode = 'QueryFailure'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*verify suspended website service recovery*'

            Should -Invoke Stop-Service -Times 0
            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|qfailure|failure:restart/10000/restart/30000|qfailure')
        }

        It 'restores normal policy and avoids stop when suspension query times out' {
            $script:suspendedRecoveryMode = 'QueryTimeout'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*verify suspended website service recovery*timeout*'

            Should -Invoke Stop-Service -Times 0
            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|qfailure|failure:restart/10000/restart/30000|qfailure')
        }

        It 'restores normal policy and avoids stop when suspended policy verification mismatches' {
            $script:suspendedRecoveryMode = 'Mismatch'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*Suspended recovery policy verification failed*'

            Should -Invoke Stop-Service -Times 0
        }

        It 'restores normal policy and avoids stop for contradictory duplicate reset-period fields' {
            $script:suspendedRecoveryMode = 'DuplicateReset'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*Suspended recovery policy verification failed*'

            Should -Invoke Stop-Service -Times 0
            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|qfailure|failure:restart/10000/restart/30000|qfailure')
        }

        It 'restores normal policy and avoids stop for duplicate failure-actions fields' {
            $script:suspendedRecoveryMode = 'DuplicateFailureActions'
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*Suspended recovery policy verification failed*'

            Should -Invoke Stop-Service -Times 0
            ($script:recoveryCommands -join '|') | Should -Be (
                'failure:|qfailure|failure:restart/10000/restart/30000|qfailure')
        }

        It 'preserves suspension and restoration failures in order' {
            $script:suspendedRecoveryMode = 'MutationFailure'
            $script:normalRecoveryMode = 'MutationFailure'
            Mock Stop-Service { }

            $failure = try {
                Stop-ProductionWebsiteService -ProductionPort 8080
                $null
            } catch {
                $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Match '^Failed to suspend website service recovery during mutation'
            $failure.InnerExceptions[1].Message | Should -Match '^Failed to restore website service recovery during mutation'
            Should -Invoke Stop-Service -Times 0
        }

        It 'preserves postcondition and restoration failures in order' {
            $script:normalRecoveryMode = 'MutationFailure'
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub -Status Running -WaitFails }

            $failure = try {
                Stop-ProductionWebsiteService -ProductionPort 8080 -ServiceTimeoutSeconds 1
                $null
            } catch {
                $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Match '^ChristopherBellDev did not reach Stopped'
            $failure.InnerExceptions[1].Message | Should -Match '^Failed to restore website service recovery during mutation'
        }

        It 'fails closed when restored policy query times out' {
            $script:normalRecoveryMode = 'QueryTimeout'
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*verify restored website service recovery*timeout*'
        }

        It 'fails closed when restored policy query fails' {
            $script:normalRecoveryMode = 'QueryFailure'
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*verify restored website service recovery*query failure*'
        }

        It 'fails closed when restored policy mutation times out' {
            $script:normalRecoveryMode = 'MutationTimeout'
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*restore website service recovery*mutation*timeout*'
        }

        It 'fails closed when restored policy verification mismatches' {
            $script:normalRecoveryMode = 'Mismatch'
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*Normal recovery policy verification failed*'
        }

        It 'fails closed when restored policy reset period mismatches' {
            $script:normalRecoveryMode = 'ResetMismatch'
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*Normal recovery policy verification failed*reset period 3600*'
        }

        It 'blocks junction changes and restart when normal recovery verification fails' {
            $script:normalRecoveryMode = 'Mismatch'
            Mock Assert-ReleasePath { $Path }
            Mock Get-JunctionTarget { 'C:\data\releases\old' }
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }
            Mock Set-AtomicJunction { }
            Mock Start-Service { }
            $config = [pscustomobject]@{
                programDataRoot = 'C:\data'
                productionPort = 8080
            }

            {
                Switch-ProductionRelease $config 'C:\data\releases\new'
            } | Should -Throw '*Normal recovery policy verification failed*'

            Should -Invoke Set-AtomicJunction -Times 0
            Should -Invoke Start-Service -Times 0
        }
        }

        It 'resolves fetched remote main instead of the checked out branch' {
            Mock Invoke-CheckedProcess {
                if ($ArgumentList -contains 'rev-parse') { return '0123456789abcdef0123456789abcdef01234567' }
                return ''
            }
            $config = [pscustomobject]@{ repositoryPath='C:\repo'; remote='origin'; branch='main' }
            Resolve-OriginMainRelease $config | Should -Be '0123456789abcdef0123456789abcdef01234567'
            Should -Invoke Invoke-CheckedProcess -ParameterFilter { $ArgumentList -contains 'fetch' }
        }

        It 'restores the former release through the controlled stop boundary when verification fails' {
            Mock Assert-ReleasePath { $Path }
            Mock Get-JunctionTarget { 'C:\data\releases\old' }
            Mock Stop-ProductionWebsiteService { }
            Mock Start-Service { }
            Mock Set-AtomicJunction { }
            $script:attempt = 0
            Mock Test-ProductionEndpoints {
                if ($script:attempt++ -eq 0) { throw 'failed verification' }
            }
            $config = [pscustomobject]@{
                programDataRoot = 'C:\data'
                productionPort = 8080
            }

            {
                Switch-ProductionRelease $config 'C:\data\releases\new'
            } | Should -Throw '*failed verification*'

            Should -Invoke Stop-ProductionWebsiteService -Times 2 -Exactly -ParameterFilter {
                $ProductionPort -eq 8080
            }
            Should -Invoke Set-AtomicJunction -ParameterFilter {
                $Target -eq 'C:\data\releases\old'
            }
        }

        It 'preserves deployment and rollback failures together' {
            Mock Assert-ReleasePath { $Path }
            Mock Get-JunctionTarget { 'C:\data\releases\old' }
            $script:stopAttempt = 0
            Mock Stop-ProductionWebsiteService {
                if ($script:stopAttempt++ -eq 1) { throw 'rollback stop failed' }
            }
            Mock Start-Service { }
            Mock Set-AtomicJunction { }
            Mock Test-ProductionEndpoints { throw 'deployment verification failed' }
            $config = [pscustomobject]@{
                programDataRoot = 'C:\data'
                productionPort = 8080
            }

            $failure = try {
                Switch-ProductionRelease $config 'C:\data\releases\new'
                $null
            } catch {
                $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            $failure.Message | Should -Match '^Production deployment and automatic rollback both failed\.'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Be 'deployment verification failed'
            $failure.InnerExceptions[1].Message | Should -Be 'rollback stop failed'
        }

        It 'overrides the candidate database for migration validation' {
            $process = [pscustomobject]@{ Id=1234; HasExited=$true }
            $process | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value { param($milliseconds) $true }
            Mock Start-ProductionJar { $process }
            Mock Test-ProductionEndpoints {}
            $config = [pscustomobject]@{ candidatePort=8081 }
            Test-CandidateRelease $config 'C:\data\releases\new' 'christopherbell_restore_check'
            Should -Invoke Start-ProductionJar -ParameterFilter { $AdditionalEnvironment.SPRING_MONGODB_DATABASE -eq 'christopherbell_restore_check' }
        }

        It 'forces native sensor libraries off in deployment candidates' {
            $process = [pscustomobject]@{ Id=1234; HasExited=$true }
            $process | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {
                param($milliseconds) $true
            }
            Mock Start-ProductionJar { $process }
            Mock Test-ProductionEndpoints {}
            $config = [pscustomobject]@{ candidatePort=8081 }

            Test-CandidateRelease $config 'C:\data\releases\new' 'restore_check'

            Should -Invoke Start-ProductionJar -Times 1 -Exactly -ParameterFilter {
                $AdditionalEnvironment.COMMAND_CENTER_SENSOR_LIBRARIES_ENABLED -eq 'false' -and
                $AdditionalEnvironment.SPRING_MONGODB_DATABASE -eq 'restore_check'
            }
        }

        It 'allows the fixed JNA bridge in deployment candidate JVMs' {
            $deploy = Get-Content (
                Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1'
            ) -Raw

            $deploy | Should -Match '--enable-native-access=ALL-UNNAMED'
        }
    }
}
