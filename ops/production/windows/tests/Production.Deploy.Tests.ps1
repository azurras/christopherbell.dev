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
        }

        It 'accepts the WinSW stop exception only after stopped service and closed port postconditions pass' {
            Mock Invoke-CheckedProcess { '' }
            Mock Stop-Service { throw 'simulated WinSW invalid handle failure' }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080 -PortTimeoutMilliseconds 1
            } | Should -Not -Throw

            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $FilePath -eq 'sc.exe' -and $ArgumentList[-1] -eq ''
            }
            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $FilePath -eq 'sc.exe' -and
                $ArgumentList[-1] -eq 'restart/10000/restart/30000'
            }
        }

        It 'fails closed when the website service does not reach Stopped' {
            Mock Invoke-CheckedProcess { '' }
            Mock Stop-Service { throw 'simulated WinSW invalid handle failure' }
            Mock Get-Service { New-ServiceStateStub -Status Running -WaitFails }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080 -ServiceTimeoutSeconds 1
            } | Should -Throw '*did not reach Stopped*'

            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $ArgumentList[-1] -eq 'restart/10000/restart/30000'
            }
        }

        It 'fails closed when the production port remains open' {
            Mock Invoke-CheckedProcess { '' }
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
            Mock Invoke-CheckedProcess { '' }
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { throw 'simulated TCP inspection failure' }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*inspect production port 8080*'
        }

        It 'does not request a stop when recovery suspension fails' {
            Mock Invoke-CheckedProcess {
                if ($ArgumentList[-1] -eq '') { throw 'simulated recovery suspension failure' }
            }
            Mock Stop-Service { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*suspend website service recovery*'

            Should -Invoke Stop-Service -Times 0
        }

        It 'blocks restart when recovery restoration fails' {
            Mock Invoke-CheckedProcess {
                if ($ArgumentList[-1] -eq 'restart/10000/restart/30000') {
                    throw 'simulated recovery restoration failure'
                }
                return ''
            }
            Mock Stop-Service { }
            Mock Get-Service { New-ServiceStateStub }
            Mock Get-NetTCPConnection { }

            {
                Stop-ProductionWebsiteService -ProductionPort 8080
            } | Should -Throw '*restore website service recovery*'
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

            {
                Switch-ProductionRelease $config 'C:\data\releases\new'
            } | Should -Throw '*deployment and automatic rollback both failed*'
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
