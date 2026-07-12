Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force

Describe 'native Windows deployment' {
    InModuleScope Production.Deploy {
        It 'resolves fetched remote main instead of the checked out branch' {
            Mock Invoke-CheckedProcess {
                if ($ArgumentList -contains 'rev-parse') { return '0123456789abcdef0123456789abcdef01234567' }
                return ''
            }
            $config = [pscustomobject]@{ repositoryPath='C:\repo'; remote='origin'; branch='main' }
            Resolve-OriginMainRelease $config | Should -Be '0123456789abcdef0123456789abcdef01234567'
            Should -Invoke Invoke-CheckedProcess -ParameterFilter { $ArgumentList -contains 'fetch' }
        }

        It 'restores the former release when production verification fails' {
            Mock Assert-ReleasePath { $Path }
            Mock Get-JunctionTarget { 'C:\data\releases\old' }
            Mock Stop-Service {}
            Mock Start-Service {}
            Mock Set-AtomicJunction {}
            $script:attempt = 0
            Mock Test-ProductionEndpoints { if ($script:attempt++ -eq 0) { throw 'failed verification' } }
            $config = [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 }
            { Switch-ProductionRelease $config 'C:\data\releases\new' } | Should -Throw '*failed verification*'
            Should -Invoke Set-AtomicJunction -ParameterFilter { $Target -eq 'C:\data\releases\old' }
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
    }
}
