Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.SharedFolder.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force

Describe 'native Windows service installer' {
    It 'reuses an existing WinSW binary only after its pinned digest is verified' {
        $serviceRoot = Join-Path $TestDrive 'service'
        New-Item -ItemType Directory -Path $serviceRoot | Out-Null
        'existing-winsw' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe')
        Mock Get-FileHash {
            [pscustomobject]@{ Hash='05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA' }
        } -ModuleName Production.Install
        Mock Invoke-WebRequest { throw 'WinSW should not be downloaded again.' }

        Install-WinSwBinary -ServiceRoot $serviceRoot

        Get-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe') -Raw | Should -Match 'existing-winsw'
        Should -Invoke Invoke-WebRequest -Times 0
    }

    It 'rejects an existing WinSW binary whose digest is not pinned' {
        $serviceRoot = Join-Path $TestDrive 'untrusted-service'
        New-Item -ItemType Directory -Path $serviceRoot | Out-Null
        'untrusted-winsw' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe')

        { Install-WinSwBinary -ServiceRoot $serviceRoot } |
            Should -Throw '*installed WinSW SHA-256 verification failed*'
    }

    It 'rejects a pre-existing reparse service-host destination' {
        InModuleScope Production.Install {
            Mock Get-Item {
                [pscustomobject]@{
                    Attributes=[IO.FileAttributes]::ReparsePoint
                    FullName=$Path
                }
            }

            { Assert-ProductionWebsiteServiceDestinationNotReparse `
                    -Path 'C:\service\ChristopherBellDev.xml' } |
                Should -Throw '*service-host destination must not be a reparse point*'
        }
    }

    It 'preserves an existing secret environment file' {
        $root = Join-Path $TestDrive 'data'
        New-ProductionDirectories $root
        $environment = Join-Path $root 'config\app.env'
        'APP_JWT_SECRET=keep-this-value' | Set-Content $environment
        Install-ConfigurationExamples $root
        Get-Content $environment -Raw | Should -Match 'keep-this-value'
    }

    It 'replaces the production config tree ACL and verifies the protected result' {
        $root = Join-Path $TestDrive 'protected-data'
        $config = Join-Path $root 'config'
        New-Item -ItemType Directory -Path $config -Force | Out-Null
        $events = [Collections.Generic.List[string]]::new()

        Protect-ProductionSecrets -Root $root `
            -ProtectTreeAction { param($Path) $events.Add("protect:$Path") } `
            -AssertTreeAction { param($Path) $events.Add("assert:$Path") }

        @($events) | Should -Be @("protect:$config", "assert:$config")
    }

    It 'creates configuration examples without real credentials' {
        $root = Join-Path $TestDrive 'new-data'
        New-ProductionDirectories $root
        Install-ConfigurationExamples $root
        Get-Content (Join-Path $root 'config\app.env') -Raw | Should -Match 'replace-with'
    }

    It 'adds new native defaults without replacing existing deploy values' {
        $root = Join-Path $TestDrive 'existing-data'
        New-ProductionDirectories $root
        $deploy = Join-Path $root 'config\deploy.json'
        @{ repositoryPath='A:\custom-repository'; smokeAccountEmail='admin@christopherbell.dev'; wslDistro='Debian'; wslWebsiteStartCommand='start-site' } |
            ConvertTo-Json | Set-Content $deploy
        Install-ConfigurationExamples $root
        $updated = Get-Content $deploy -Raw | ConvertFrom-Json
        $updated.repositoryPath | Should -Be 'A:\custom-repository'
        $updated.smokeAccountEmail | Should -Be 'admin@christopherbell.dev'
        $updated.cloudflaredExe | Should -Match 'cloudflared\.exe$'
        $updated.publicUrl | Should -Be 'https://www.christopherbell.dev/'
        $updated.PSObject.Properties.Name | Should -Not -Contain 'wslDistro'
        $updated.PSObject.Properties.Name | Should -Not -Contain 'wslWebsiteStartCommand'
    }

    It 'adds the sensor provider switch disabled without replacing existing values' {
        $root = Join-Path $TestDrive 'sensor-default'
        New-ProductionDirectories $root
        $deploy = Join-Path $root 'config\deploy.json'
        @{ repositoryPath='A:\custom-repository'; sensorLibrariesEnabled=$true } |
            ConvertTo-Json | Set-Content $deploy

        Install-ConfigurationExamples $root

        $updated = Get-Content $deploy -Raw | ConvertFrom-Json
        $updated.sensorLibrariesEnabled | Should -BeTrue
        $example = Get-Content (Join-Path $root 'config\deploy.example.json') -Raw | ConvertFrom-Json
        $example.sensorLibrariesEnabled | Should -BeFalse
    }

    It 'starts the website with the protected typed sensor switch' {
        $startup = Get-Content (Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1') -Raw
        $startup | Should -Match 'sensorLibrariesEnabled'
        $startup | Should -Match 'COMMAND_CENTER_SENSOR_LIBRARIES_ENABLED'
        $startup | Should -Match '--enable-native-access=ALL-UNNAMED'
        $startup | Should -Match 'APP_SHARED_FOLDER_ENABLED'
        $startup | Should -Match 'APP_MAIL_ENABLED'
        $startup | Should -Match "SetEnvironmentVariable\(\s*'APP_SHARED_FOLDER_ENABLED',\s*'false',\s*'Process'\s*\)"
        $startup | Should -Not -Match "SetEnvironmentVariable\('COMMAND_CENTER_SENSOR_LIBRARIES_ENABLED',\s*'true'"
    }

    It 'ships the shared-folder feature disabled until the guarded production rollout enables it' {
        $environment = Get-Content (Join-Path $PSScriptRoot '..\config\app.env.example')

        $environment | Should -Contain 'APP_SHARED_FOLDER_ENABLED=false'
        $environment | Should -Contain 'APP_MAIL_ENABLED=true'
    }

    It 'preserves website service setup while delegating the shared-folder runtime install' {
        $module = Get-Content (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Raw

        $module | Should -Match 'function Install-WebsiteService'
        $module | Should -Match 'Set-Service MongoDB -StartupType Automatic'
        $module | Should -Match 'sc\.exe config ChristopherBellDev start= disabled depend= MongoDB'
        $module | Should -Match 'Install-SharedFolderRuntime -ProductionRoot \$root -Configuration \$config'
    }

    It 'installs the launcher and WriterStart module only through the verified bundle publisher' {
        $module = Get-Content (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Raw

        $module | Should -Match 'Publish-ProductionWriterStartGuardBundle'
        $module | Should -Not -Match (
            "Copy-Item\s+\(Join-Path\s+\`$PSScriptRoot\s+'\.\.\\service\\" +
            "Start-ChristopherBellDev\.ps1'\)")
    }

    It 'protects the canonical service directory before staging WinSW or service XML' {
        $module = Get-Content (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Raw
        $protect = $module.IndexOf(
            'Protect-ProductionWebsiteServiceDirectory -Configuration $Configuration')

        $protect | Should -BeGreaterThan -1
        $protect | Should -BeLessThan $module.IndexOf(
            'Install-WinSwBinary -ServiceRoot $service')
        $protect | Should -BeLessThan $module.IndexOf('Copy-Item $sourceXml $installedXml')
    }

    It 'rejects a reparse service directory before any service-host file write' {
        InModuleScope Production.Install {
            $root = Join-Path $TestDrive 'installer-reparse-root'
            $target = Join-Path $TestDrive 'installer-reparse-target'
            New-Item -ItemType Directory -Path $root,$target -Force | Out-Null
            $service = Join-Path $root 'service'
            New-Item -ItemType Junction -Path $service -Target $target | Out-Null
            Mock Install-WinSwBinary { throw 'WinSW write must not run' }
            Mock Copy-Item { throw 'XML write must not run' }
            try {
                { Install-WebsiteService `
                        -Root $root `
                        -Configuration ([pscustomobject]@{ programDataRoot=$root }) } |
                    Should -Throw '*reparse*'
                Should -Invoke Install-WinSwBinary -Times 0
                Should -Invoke Copy-Item -Times 0
                @(Get-ChildItem -LiteralPath $target -Force).Count | Should -Be 0
            } finally {
                if (Test-Path -LiteralPath $service) {
                    Remove-Item -LiteralPath $service -Force
                }
            }
        }
    }

    It 'accepts only the exact LocalSystem website service executable binding' {
        InModuleScope Production.Install {
            $root = Join-Path $TestDrive 'binding-root'
            $binary = Join-Path $root 'service\ChristopherBellDev.exe'
            Mock Get-CimInstance {
                [pscustomobject]@{ PathName="`"$($binary.ToLowerInvariant())`""; StartName='LocalSystem' }
            }

            { Assert-ProductionWebsiteServiceBinding -Root $root } | Should -Not -Throw
        }
    }

    It 'rejects an unsafe website service executable or identity binding' -ForEach @(
        @{ PathName='C:\Other\ChristopherBellDev.exe'; StartName='LocalSystem' },
        @{ PathName=$null; StartName='LocalService' }
    ) {
        InModuleScope Production.Install -Parameters @{
            UnsafePathName=$PathName
            UnsafeStartName=$StartName
        } {
            param($UnsafePathName,$UnsafeStartName)
            $root = Join-Path $TestDrive 'unsafe-binding-root'
            $expected = Join-Path $root 'service\ChristopherBellDev.exe'
            $actual = if ($UnsafePathName) { $UnsafePathName } else { $expected }
            Mock Get-CimInstance {
                [pscustomobject]@{ PathName=$actual; StartName=$UnsafeStartName }
            }

            { Assert-ProductionWebsiteServiceBinding -Root $root } |
                Should -Throw '*binding was not verified*'
        }
    }
}

Describe 'native runtime reinstall lifecycle' {
    InModuleScope Production.Install {
        BeforeEach {
            $script:events = [Collections.Generic.List[string]]::new()
            $script:serviceStatus = 'Stopped'
            $script:startupType = 'Automatic'
            $script:config = [pscustomobject]@{
                programDataRoot = 'C:\ProgramData\christopherbell.dev'
                productionPort = 8080
                cloudflaredExe = 'C:\cloudflared.exe'
            }
            $script:lock = [pscustomobject]@{}
            $script:lock | Add-Member ScriptMethod Dispose {
                [void]$script:events.Add('lock:release')
            }
            Mock Assert-Administrator { }
            Mock Initialize-ProductionDeploymentLockDirectory { }
            Mock New-ProductionDirectories { }
            Mock Install-ConfigurationExamples { }
            Mock Read-ProductionConfig { $script:config }
            Mock Read-ProductionEnvironment { @{} }
            Mock Protect-ProductionSecrets { }
            Mock Install-CloudflaredService { }
            Mock Enter-DeploymentLock {
                [void]$script:events.Add('lock:acquire')
                $script:lock
            }
            Mock Get-ProductionWebsiteServiceOrNull {
                [void]$script:events.Add("state:$script:serviceStatus")
                [pscustomobject]@{ Name='ChristopherBellDev'; Status=$script:serviceStatus }
            }
            Mock Set-ProductionWebsiteStartupType {
                $script:startupType = $StartupType
                [void]$script:events.Add("startup:$StartupType")
            }
            Mock Stop-ProductionWebsiteService {
                $script:serviceStatus = 'Stopped'
                [void]$script:events.Add('stop')
            }
            Mock Install-WebsiteService { [void]$script:events.Add('website-install') }
            Mock Install-SharedFolderRuntime { [void]$script:events.Add('shared-install') }
            Mock Assert-ProductionWebsiteServiceBoundary {
                [void]$script:events.Add('boundary-verified')
            }
            Mock Start-Service {
                $script:serviceStatus = 'Running'
                [void]$script:events.Add('start')
            }
            Mock Test-ProductionEndpoints { [void]$script:events.Add('health') }
            Mock Test-ProductionPublicEndpoints { [void]$script:events.Add('public-health') }
        }

        It 'captures a running service under deploy.lock then restarts and health-checks it' {
            $script:serviceStatus = 'Running'

            Install-ProductionRuntime

            $script:events | Should -Be @(
                'lock:acquire','state:Running','startup:Disabled','stop',
                'website-install','shared-install','boundary-verified','startup:Automatic',
                'start','health','public-health','lock:release')
            $script:startupType | Should -Be 'Automatic'
            $script:serviceStatus | Should -Be 'Running'
        }

        It 'preserves an intentionally stopped service after successful reinstall' {
            Install-ProductionRuntime

            $script:startupType | Should -Be 'Automatic'
            $script:serviceStatus | Should -Be 'Stopped'
            Should -Invoke Start-Service -Times 0
            Should -Invoke Test-ProductionEndpoints -Times 0
            Should -Invoke Test-ProductionPublicEndpoints -Times 0
        }

        It 'leaves reinstall failure stopped and Disabled with the original cause' {
            $script:serviceStatus = 'Running'
            Mock Install-SharedFolderRuntime { throw 'shared runtime failed' }

            { Install-ProductionRuntime } |
                Should -Throw '*shared runtime failed*'

            $script:startupType | Should -Be 'Disabled'
            $script:serviceStatus | Should -Be 'Stopped'
            Should -Invoke Start-Service -Times 0
            Should -Invoke Set-ProductionWebsiteStartupType -ParameterFilter {
                $StartupType -eq 'Disabled'
            }
        }

        It 'contains a transitional prior state before rejecting installation' {
            $script:serviceStatus = 'StartPending'

            { Install-ProductionRuntime } | Should -Throw '*Running or Stopped*'

            $script:startupType | Should -Be 'Disabled'
            $script:serviceStatus | Should -Be 'Stopped'
            Should -Invoke Set-ProductionWebsiteStartupType -ParameterFilter {
                $StartupType -eq 'Disabled'
            }
            Should -Invoke Stop-ProductionWebsiteService
            Should -Invoke Install-WebsiteService -Times 0
        }

        It 'disables and stops a running writer before early installation effects' {
            $script:serviceStatus = 'Running'
            Mock New-ProductionDirectories {
                [void]$script:events.Add('directories')
                throw 'directory installation failed'
            }

            { Install-ProductionRuntime } | Should -Throw '*directory installation failed*'

            $script:events.IndexOf('lock:acquire') |
                Should -BeLessThan $script:events.IndexOf('state:Running')
            $script:events.IndexOf('startup:Disabled') |
                Should -BeLessThan $script:events.IndexOf('directories')
            $script:events.IndexOf('stop') |
                Should -BeLessThan $script:events.IndexOf('directories')
            $script:startupType | Should -Be 'Disabled'
            $script:serviceStatus | Should -Be 'Stopped'
        }

        It 'preserves SCM discovery failures as containment causes' {
            Mock Get-ProductionWebsiteServiceOrNull { throw 'SCM query denied' }

            $caught = $null
            try {
                Install-ProductionRuntime
            } catch {
                $caught = $_.Exception
            }

            $caught | Should -BeOfType ([System.AggregateException])
            @($caught.InnerExceptions.Message) | Should -Contain 'SCM query denied'
            $caught.Message | Should -Match 'containment could not be verified'
        }
    }
}

Describe 'native cloudflared service installer' {
    InModuleScope Production.Install {
        BeforeEach {
            Mock Get-AuthenticodeSignature {
                [pscustomobject]@{
                    Status = 'Valid'
                    SignerCertificate = [pscustomobject]@{ Subject='CN="Cloudflare, Inc.", O="Cloudflare, Inc.", C=US' }
                }
            }
            Mock Get-CimInstance { [pscustomobject]@{ PathName='"C:\cloudflared.exe" service' } }
        }

        It 'rejects a cloudflared executable without a valid Cloudflare signature' {
            Mock Test-Path { $true }
            Mock Get-AuthenticodeSignature {
                [pscustomobject]@{ Status='NotSigned'; SignerCertificate=$null }
            }
            { Assert-CloudflaredExecutable -Executable 'C:\cloudflared.exe' } |
                Should -Throw '*signed by Cloudflare*'
        }

        It 'rejects an existing service bound to a different executable without a replacement token' {
            Mock Get-Service { [pscustomobject]@{ Status='Running' } } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true }
            Mock Get-CimInstance { [pscustomobject]@{ PathName='"C:\stale\cloudflared.exe" service' } }
            { Install-CloudflaredService -Executable 'C:\cloudflared.exe' } |
                Should -Throw '*not bound*'
        }

        It 'requires a token path only when cloudflared is not installed' {
            Mock Get-Service { $null } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true } -ParameterFilter { $LiteralPath -eq 'C:\cloudflared.exe' }
            { Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $null } |
                Should -Throw '*CloudflareTokenPath*'
        }

        It 'installs cloudflared without writing the token to output' {
            $tokenPath = Join-Path $TestDrive 'tunnel-token.txt'
            ('a' * 240) | Set-Content $tokenPath -NoNewline
            Mock Get-Service { $null } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true }
            Mock Invoke-CheckedProcess {}
            Mock Set-Service {}
            Mock Start-Service {}
            Mock Start-Process {}
            $output = Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $tokenPath
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'C:\cloudflared.exe' -and
                $ArgumentList[0] -eq 'service' -and
                $ArgumentList[1] -eq 'install' -and
                $ArgumentList[2].Length -eq 240
            }
            ($output -join '') | Should -Not -Match ('a' * 20)
        }

        It 'replaces an existing cloudflared credential only when a token path is supplied' {
            $tokenPath = Join-Path $TestDrive 'rotated-token.txt'
            ('b' * 240) | Set-Content $tokenPath -NoNewline
            Mock Get-Service { [pscustomobject]@{ Status='Running' } } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true }
            Mock Invoke-CheckedProcess {}
            Mock Set-Service {}
            Mock Start-Service {}
            Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $tokenPath
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'C:\cloudflared.exe' -and ($ArgumentList -join ' ') -eq 'service uninstall'
            }
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'C:\cloudflared.exe' -and
                $ArgumentList[0] -eq 'service' -and
                $ArgumentList[1] -eq 'install' -and
                $ArgumentList[2].Length -eq 240
            }
        }
    }
}
