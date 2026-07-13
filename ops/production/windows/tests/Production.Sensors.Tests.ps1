Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Sensors.psm1') -Force

Describe 'PawnIO sensor provider operations' {
    InModuleScope Production.Sensors {
        BeforeEach {
            Mock Assert-SensorAdministrator {}
            Mock Assert-NoActiveSensorThreat {}
            Mock Start-MpScan {}
            Mock Restart-Service {}
            Mock Test-ProductionEndpoints {}
            Mock Protect-ProductionPath {}
            Mock Protect-ProductionTree {}
            Mock Assert-ProtectedProductionTree {}
        }

        It 'rejects an installer whose hash or signer thumbprint differs' {
            Mock Test-Path { $true }
            Mock Get-FileHash { [pscustomobject]@{ Hash='BAD' } }
            Mock Get-AuthenticodeSignature {
                [pscustomobject]@{ Status='Valid'; SignerCertificate=[pscustomobject]@{ Thumbprint='BAD' } }
            }
            { Assert-PawnIoInstaller -Path 'C:\audit\PawnIO_setup.exe' } |
                Should -Throw '*SHA-256*'
        }

        It 'treats installer reboot-required as a stop with sensors still disabled' {
            Mock Invoke-WebRequest { 'installer' | Set-Content -LiteralPath $OutFile }
            Mock Assert-PawnIoInstaller {}
            Mock Start-Process { [pscustomobject]@{ ExitCode=3010 } }
            Mock Set-ProductionSensorState {}
            { Install-PawnIoProvider -Root $TestDrive } | Should -Throw '*reboot required*'
            Should -Invoke Set-ProductionSensorState -ParameterFilter { -not $Enabled }
        }

        It 'never enables sensors as part of a successful install' {
            Mock Invoke-WebRequest { 'installer' | Set-Content -LiteralPath $OutFile }
            Mock Assert-PawnIoInstaller {}
            Mock Start-Process { [pscustomobject]@{ ExitCode=0 } }
            Mock Set-ProductionSensorState {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0'; Driver='Running' } }
            Install-PawnIoProvider -Root $TestDrive
            Should -Invoke Set-ProductionSensorState -Times 1 -ParameterFilter { -not $Enabled }
            Should -Invoke Start-MpScan -Times 2
        }

        It 'uses the official PawnIO install and silent command-line switches' {
            Mock Invoke-WebRequest { 'installer' | Set-Content -LiteralPath $OutFile }
            Mock Assert-PawnIoInstaller {}
            Mock Start-Process { [pscustomobject]@{ ExitCode=0 } }
            Mock Set-ProductionSensorState {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0'; Driver='Running' } }

            Install-PawnIoProvider -Root $TestDrive

            Should -Invoke Start-Process -Times 1 -Exactly -ParameterFilter {
                $ArgumentList.Count -eq 2 -and
                $ArgumentList[0] -eq '-install' -and
                $ArgumentList[1] -eq '-silent'
            }
        }

        It 'uses a fresh protected installer directory and revalidates immediately before launch' {
            $script:downloadPath = $null
            $script:protectedPaths = [Collections.Generic.List[string]]::new()
            Mock Protect-ProductionPath { $script:protectedPaths.Add([IO.Path]::GetFullPath($Path)) }
            Mock Protect-ProductionTree {}
            Mock Assert-ProtectedProductionTree {}
            Mock Invoke-WebRequest {
                $script:downloadPath = $OutFile
                'installer' | Set-Content -LiteralPath $OutFile
            }
            Mock Assert-PawnIoInstaller {}
            Mock Start-Process { [pscustomobject]@{ ExitCode=0 } }
            Mock Set-ProductionSensorState {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0'; Driver='Running' } }

            Install-PawnIoProvider -Root $TestDrive

            $script:protectedPaths[0] | Should -Be ([IO.Path]::GetFullPath($TestDrive))
            $relative = [IO.Path]::GetRelativePath($TestDrive, $script:downloadPath)
            $relative | Should -Match '^sensors[\\/][0-9a-f-]{36}[\\/]PawnIO_setup-2\.2\.0\.exe$'
            Should -Invoke Assert-PawnIoInstaller -Times 2 -Exactly
            Should -Invoke Assert-ProtectedProductionTree -Times 2 -Exactly
            Test-Path -LiteralPath (Split-Path -Parent $script:downloadPath) | Should -BeFalse
        }

        It 'fails enablement closed and restores false when endpoint verification fails' {
            $configPath = Join-Path $TestDrive 'deploy.json'
            @{ productionPort=8080; sensorLibrariesEnabled=$false } | ConvertTo-Json | Set-Content $configPath
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0'; Driver='Running' } }
            Mock Test-ProductionEndpoints { throw 'endpoint failed' }
            { Set-ProductionSensorState -Enabled $true -ConfigPath $configPath } | Should -Throw '*endpoint failed*'
            (Get-Content $configPath -Raw | ConvertFrom-Json).sensorLibrariesEnabled | Should -BeFalse
            Should -Invoke Restart-Service -Times 2 -ParameterFilter { $Name -eq 'ChristopherBellDev' }
        }

        It 'reports protected state installation and active threat state without mutation' {
            $configPath = Join-Path $TestDrive 'deploy.json'
            @{ sensorLibrariesEnabled=$false } | ConvertTo-Json | Set-Content $configPath
            Mock Get-PawnIoInstallation {
                [pscustomobject]@{
                    Version='2.2.0'; Driver='Running'; DriverPath='C:\Windows\System32\drivers\PawnIO.sys'
                    DriverSignature='Valid'; UninstallString='C:\Program Files\PawnIO\uninstall.exe'
                }
            }
            Mock Get-MpThreat { @() }
            $status = Get-ProductionSensorStatus -ConfigPath $configPath
            $status.Enabled | Should -BeFalse
            $status.PawnIoVersion | Should -Be '2.2.0'
            $status.Driver | Should -Be 'Running'
            $status.DriverSignature | Should -Be 'Valid'
            $status.UninstallRegistered | Should -BeTrue
            $status.ActiveThreats | Should -Be 0
        }

        It 'rejects an installed driver without a valid Windows signature' {
            Mock Get-PawnIoInstallation {
                [pscustomobject]@{ Version='2.2.0'; Driver='Running'; DriverSignature='NotSigned' }
            }
            { Assert-PawnIoInstallation } | Should -Throw '*signature*'
        }

        It 'requires a plausible live direct probe after provider and Defender checks' {
            Mock Assert-NoActiveSensorThreat {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0'; Driver='Running' } }
            Mock Get-ProductionCpuTemperature { 63.25 }

            Assert-ProductionSensorReady -Root 'C:\ProgramData\christopherbell.dev' | Should -Be 63.25

            Should -Invoke Assert-NoActiveSensorThreat -Times 1
            Should -Invoke Assert-PawnIoInstallation -Times 1
            Should -Invoke Get-ProductionCpuTemperature -Times 1
        }

        It 'rejects an implausible direct CPU temperature' -TestCases @(
            @{ Value=0.0 }
            @{ Value=126.0 }
            @{ Value=[double]::NaN }
        ) {
            param($Value)
            Mock Assert-NoActiveSensorThreat {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0'; Driver='Running' } }
            Mock Get-ProductionCpuTemperature { $Value }

            { Assert-ProductionSensorReady -Root 'C:\ProgramData\christopherbell.dev' } |
                Should -Throw '*plausible*'
        }
    }
}
