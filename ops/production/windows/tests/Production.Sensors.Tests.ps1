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
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0.0'; Driver='Running' } }
            Install-PawnIoProvider -Root $TestDrive
            Should -Invoke Set-ProductionSensorState -Times 1 -ParameterFilter { -not $Enabled }
            Should -Invoke Start-MpScan -Times 2
        }

        It 'uses the official PawnIO install and silent command-line switches' {
            Mock Invoke-WebRequest { 'installer' | Set-Content -LiteralPath $OutFile }
            Mock Assert-PawnIoInstaller {}
            Mock Start-Process { [pscustomobject]@{ ExitCode=0 } }
            Mock Set-ProductionSensorState {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0.0'; Driver='Running' } }

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
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0.0'; Driver='Running' } }

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
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0.0'; Driver='Running' } }
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
                    Version='2.2.0.0'; Driver='Running'; DriverPath='C:\Windows\System32\drivers\PawnIO.sys'
                    DriverSignature='Valid'; UninstallString='C:\Program Files\PawnIO\uninstall.exe'
                }
            }
            Mock Get-MpThreat { @() }
            $status = Get-ProductionSensorStatus -ConfigPath $configPath
            $status.Enabled | Should -BeFalse
            $status.PawnIoVersion | Should -Be '2.2.0.0'
            $status.Driver | Should -Be 'Running'
            $status.DriverSignature | Should -Be 'Valid'
            $status.UninstallRegistered | Should -BeTrue
            $status.ActiveThreats | Should -Be 0
        }

        It 'rejects an installed driver without a valid Windows signature' {
            Mock Get-PawnIoInstallation {
                [pscustomobject]@{ Version='2.2.0.0'; Driver='Running'; DriverSignature='NotSigned' }
            }
            { Assert-PawnIoInstallation } | Should -Throw '*signature*'
        }

        It 'accepts the exact installed PawnIO product version' {
            Mock Get-PawnIoInstallation {
                [pscustomobject]@{
                    Version='2.2.0.0'; Driver='Running'; DriverSignature='Valid'
                    UninstallString='C:\Program Files\PawnIO\uninstall.exe -uninstall'
                }
            }
            { Assert-PawnIoInstallation } | Should -Not -Throw
        }

        It 'rejects the release tag in place of the installed product version' {
            Mock Get-PawnIoInstallation {
                [pscustomobject]@{
                    Version='2.2.0'; Driver='Running'; DriverSignature='Valid'
                    UninstallString='C:\Program Files\PawnIO\uninstall.exe -uninstall'
                }
            }
            { Assert-PawnIoInstallation } | Should -Throw '*2.2.0.0*'
        }

        It 'ignores a sole stale owner until the current owner marker appears' {
            $script:directoryEnumerations = 0
            $ownerStartedAt = [datetime]'2026-07-16T15:00:00Z'
            Mock Test-Path { $true }
            Mock Get-ChildItem {
                $script:directoryEnumerations++
                if ($script:directoryEnumerations -eq 1) {
                    return @([pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-stale'
                        FullName='C:\sensors\stale'
                    })
                }
                return @(
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-stale'
                        FullName='C:\sensors\stale'
                    },
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-live'
                        FullName='C:\sensors\live'
                    })
            }
            Mock Test-ProductionSensorOwnerMarker {
                $Directory.FullName -eq 'C:\sensors\live'
            }
            Mock Start-Sleep {}

            $directory = Wait-ProductionSensorResourceDirectory `
                -Base 'C:\sensors' `
                -OwnerPid 1234 `
                -OwnerStartedAt $ownerStartedAt `
                -Timeout ([timespan]::FromSeconds(1))

            $directory.FullName | Should -Be 'C:\sensors\live'
            Should -Invoke Get-ChildItem -Times 2 -Exactly
            Should -Invoke Start-Sleep -Times 1 -Exactly -ParameterFilter {
                $Milliseconds -eq 250
            }
        }

        It 'reports the unresolved resource count when the wait expires' {
            $ownerStartedAt = [datetime]'2026-07-16T15:00:00Z'
            Mock Test-Path { $true }
            Mock Get-ChildItem {
                @(
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-stale-a'
                        FullName='C:\sensors\stale-a'
                    },
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-stale-b'
                        FullName='C:\sensors\stale-b'
                    })
            }
            Mock Test-ProductionSensorOwnerMarker { $false }
            Mock Start-Sleep {}

            {
                Wait-ProductionSensorResourceDirectory `
                    -Base 'C:\sensors' `
                    -OwnerPid 1234 `
                    -OwnerStartedAt $ownerStartedAt `
                    -Timeout ([timespan]::Zero)
            } | Should -Throw '*found 0 live of 2 total*'

            Should -Invoke Start-Sleep -Times 0 -Exactly
        }

        It 'clamps a nonzero unresolved wait to the remaining monotonic timeout' {
            $ownerStartedAt = [datetime]'2026-07-16T15:00:00Z'
            Mock Test-Path { $true }
            Mock Get-ChildItem {
                @(
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-stale-a'
                        FullName='C:\sensors\stale-a'
                    },
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-stale-b'
                        FullName='C:\sensors\stale-b'
                    })
            }
            Mock Test-ProductionSensorOwnerMarker { $false }

            $elapsed = Measure-Command {
                {
                    Wait-ProductionSensorResourceDirectory `
                        -Base 'C:\sensors' `
                        -OwnerPid 1234 `
                        -OwnerStartedAt $ownerStartedAt `
                        -Timeout ([timespan]::FromMilliseconds(20)) `
                        -PollMilliseconds 250
                } | Should -Throw '*found 0 live of 2 total*'
            }

            $elapsed.TotalMilliseconds | Should -BeLessThan 200
        }

        It 'matches owner markers by pid and process start time' {
            $directory = New-Item -ItemType Directory -Path (
                Join-Path $TestDrive 'librehardwaremonitor-0.9.6-owner')
            $ownerStartedAt = [datetime]'2026-07-16T15:00:00Z'
            @(
                'pid=9999'
                "startedAtEpochMillis=$(
                    ([DateTimeOffset]$ownerStartedAt).ToUnixTimeMilliseconds()
                )"
            ) | Set-Content -LiteralPath (Join-Path $directory.FullName 'live-owner.pid')

            Test-ProductionSensorOwnerMarker `
                -Directory $directory `
                -OwnerPid 1234 `
                -OwnerStartedAt $ownerStartedAt | Should -BeFalse

            @(
                'pid=1234'
                "startedAtEpochMillis=$(
                    ([DateTimeOffset]$ownerStartedAt.AddMilliseconds(-1)).
                        ToUnixTimeMilliseconds()
                )"
            ) | Set-Content -LiteralPath (Join-Path $directory.FullName 'live-owner.pid')

            Test-ProductionSensorOwnerMarker `
                -Directory $directory `
                -OwnerPid 1234 `
                -OwnerStartedAt $ownerStartedAt | Should -BeFalse

            @(
                'pid=1234'
                "startedAtEpochMillis=$(
                    ([DateTimeOffset]$ownerStartedAt).ToUnixTimeMilliseconds()
                )"
            ) | Set-Content -LiteralPath (Join-Path $directory.FullName 'live-owner.pid')

            Test-ProductionSensorOwnerMarker `
                -Directory $directory `
                -OwnerPid 1234 `
                -OwnerStartedAt $ownerStartedAt | Should -BeTrue
        }

        It 'excludes invalid nonce directory names from the live count' {
            $ownerStartedAt = [datetime]'2026-07-16T15:00:00Z'
            Mock Test-Path { $true }
            Mock Get-ChildItem {
                @(
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-invalid!'
                        FullName='C:\sensors\invalid'
                    },
                    [pscustomobject]@{
                        Name='librehardwaremonitor-0.9.6-live'
                        FullName='C:\sensors\live'
                    })
            }
            Mock Test-ProductionSensorOwnerMarker { $true }

            $directory = Wait-ProductionSensorResourceDirectory `
                -Base 'C:\sensors' `
                -OwnerPid 1234 `
                -OwnerStartedAt $ownerStartedAt `
                -Timeout ([timespan]::FromSeconds(1))

            $directory.FullName | Should -Be 'C:\sensors\live'
        }

        It 'uses the full framework probe host and terminating script errors' {
            $module = Get-Content (
                Join-Path $PSScriptRoot '..\modules\Production.Sensors.psm1'
            ) -Raw
            $probe = Get-Content (
                Join-Path $PSScriptRoot (
                    '..\..\..\..\website\src\main\resources\lib\' +
                    'cpu-temperature.ps1'
                )
            ) -Raw

            $module | Should -Match (
                'System32\\WindowsPowerShell\\v1\.0\\powershell\.exe'
            )
            $module | Should -Not -Match 'PowerShell\\7\\pwsh\.exe'
            $probe | Should -Match (
                '(?m)^\$ErrorActionPreference\s*=\s*''Stop'''
            )
        }

        It 'prefers package temperature and excludes TjMax distance headroom' {
            $probePath = Join-Path $PSScriptRoot (
                '..\..\..\..\website\src\main\resources\lib\cpu-temperature.ps1'
            )
            $tokens = $null
            $errors = $null
            $ast = [Management.Automation.Language.Parser]::ParseFile(
                $probePath, [ref]$tokens, [ref]$errors)
            $function = $ast.Find({
                param($node)
                $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -eq 'Select-CpuTemperature'
            }, $true)

            $errors | Should -BeNullOrEmpty
            $function | Should -Not -BeNullOrEmpty
            Invoke-Expression $function.Extent.Text

            Select-CpuTemperature @(
                [pscustomobject]@{ Name='Core Average'; Value=37.5 },
                [pscustomobject]@{ Name='Core Max'; Value=45.0 },
                [pscustomobject]@{ Name='CPU Package'; Value=48.0 },
                [pscustomobject]@{
                    Name='E-Core #5 Distance to TjMax'
                    Value=66.0
                }
            ) | Should -Be 48.0

            Select-CpuTemperature @(
                [pscustomobject]@{ Name='Core Max'; Value=45.0 },
                [pscustomobject]@{ Name='P-Core #1'; Value=43.0 },
                [pscustomobject]@{
                    Name='P-Core #1 Distance to TjMax'
                    Value=57.0
                }
            ) | Should -Be 45.0

            Select-CpuTemperature @(
                [pscustomobject]@{ Name='P-Core #1'; Value=43.0 },
                [pscustomobject]@{ Name='E-Core #1'; Value=36.0 },
                [pscustomobject]@{
                    Name='E-Core #1 Distance to TjMax'
                    Value=64.0
                }
            ) | Should -Be 43.0
        }

        It 'derives the expected probe hash from the active release jar' {
            $root = Join-Path $TestDrive 'release-aware-hash'
            $current = New-Item -ItemType Directory (
                Join-Path $root 'current')
            $jar = Join-Path $current.FullName 'app.jar'
            $content = 'previous-release-probe'
            $archive = [IO.Compression.ZipFile]::Open(
                $jar, [IO.Compression.ZipArchiveMode]::Create)
            try {
                $entry = $archive.CreateEntry(
                    'BOOT-INF/classes/lib/cpu-temperature.ps1')
                $stream = $entry.Open()
                try {
                    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($content)
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $stream.Dispose()
                }
            } finally {
                $archive.Dispose()
            }
            $sha = [Security.Cryptography.SHA256]::Create()
            try {
                $expected = -join (
                    $sha.ComputeHash(
                        [Text.UTF8Encoding]::new($false).GetBytes($content)
                    ) | ForEach-Object { $_.ToString('X2') }
                )
            } finally {
                $sha.Dispose()
            }

            Get-ProductionCpuTemperatureScriptHash -Root $root |
                Should -Be $expected
        }

        It 'requires a plausible live direct probe after provider and Defender checks' {
            Mock Assert-NoActiveSensorThreat {}
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0.0'; Driver='Running' } }
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
            Mock Assert-PawnIoInstallation { [pscustomobject]@{ Version='2.2.0.0'; Driver='Running' } }
            Mock Get-ProductionCpuTemperature { $Value }

            { Assert-ProductionSensorReady -Root 'C:\ProgramData\christopherbell.dev' } |
                Should -Throw '*plausible*'
        }
    }
}
