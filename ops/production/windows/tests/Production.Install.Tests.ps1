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
        $module | Should -Match 'Set-ProductionWebsiteStartupType -StartupType Disabled'
        $module | Should -Match 'Install-SharedFolderRuntime -ProductionRoot \$root -Configuration \$config'
    }

    It 'registers the website service from a non-automatic WinSW definition' {
        [xml]$service = Get-Content (
            Join-Path $PSScriptRoot '..\service\ChristopherBellDev.xml') -Raw

        [string]$service.service.startmode | Should -Be 'Manual'
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

    It 'binds the validated config to the locked root before config-derived effects' {
        $module = Get-Content (
            Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Raw
        $runtime = $module.Substring($module.IndexOf(
            'function Invoke-ProductionRuntimeInstallAtRoot'))
        $read = $runtime.IndexOf('$config = Read-ProductionConfig')
        $bind = $runtime.IndexOf('Assert-ProductionConfiguredRootBoundary')

        $read | Should -BeGreaterOrEqual 0
        $bind | Should -BeGreaterThan $read
        $bind | Should -BeLessThan $runtime.IndexOf(
            'Read-ProductionEnvironment')
        $bind | Should -BeLessThan $runtime.IndexOf(
            'Protect-ProductionWebsiteServiceDirectory')
        $bind | Should -BeLessThan $runtime.IndexOf(
            'Install-CloudflaredService')
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

    It 'observes registration then immediately establishes and verifies Disabled startup' {
        InModuleScope Production.Install {
            $script:registrationEvents = [Collections.Generic.List[string]]::new()
            $script:serviceRegistered = $false
            $serviceRoot = Join-Path $TestDrive 'registration-service'
            $state = [pscustomobject]@{ ServiceRegistered=$false }
            Mock Protect-ProductionWebsiteServiceDirectory { $serviceRoot }
            Mock Set-ProductionMongoServiceInstallPolicy { }
            Mock Install-WinSwBinary {
                Join-Path $serviceRoot 'ChristopherBellDev.exe'
            }
            Mock Copy-Item { }
            Mock Publish-ProductionWebsiteWriterStartBundle {
                [void]$script:registrationEvents.Add('bundle-published')
            }
            Mock Get-ProductionWebsiteServiceOrNull {
                if ($script:serviceRegistered) {
                    return [pscustomobject]@{ Status='Stopped' }
                }
                return $null
            }
            Mock Invoke-ProductionWinSwServiceInstall {
                [void]$script:registrationEvents.Add('registered-manual')
                $script:serviceRegistered = $true
            }
            Mock Set-ProductionWebsiteStartupType {
                [void]$script:registrationEvents.Add("startup:$StartupType")
            }
            Mock Set-ProductionWebsiteServiceInstallPolicy {
                [void]$script:registrationEvents.Add('service-policy')
            }
            Mock Assert-ProductionWebsiteServiceBoundary {
                [void]$script:registrationEvents.Add('boundary-verified')
            }
            Mock Start-Service { throw 'website must not start during installation' }

            Install-WebsiteService `
                -Root $TestDrive `
                -Configuration ([pscustomobject]@{ programDataRoot=$TestDrive }) `
                -RegistrationState $state

            $state.ServiceRegistered | Should -BeTrue
            $script:registrationEvents | Should -Be @(
                'bundle-published',
                'registered-manual',
                'startup:Disabled',
                'service-policy',
                'boundary-verified')
            Should -Invoke Set-ProductionWebsiteStartupType -ParameterFilter {
                $StartupType -eq 'Automatic'
            } -Times 0
            Should -Invoke Start-Service -Times 0
        }
    }

    It 'rejects an invalid registration tracker before any service installation effect' {
        InModuleScope Production.Install {
            Mock Protect-ProductionWebsiteServiceDirectory {
                throw 'service directory effect must not run'
            }

            { Install-WebsiteService `
                    -Root $TestDrive `
                    -Configuration ([pscustomobject]@{ programDataRoot=$TestDrive }) `
                    -RegistrationState ([pscustomobject]@{}) } |
                Should -Throw '*Boolean ServiceRegistered*'

            Should -Invoke Protect-ProductionWebsiteServiceDirectory -Times 0
        }
    }

    It 'fails closed at each first-registration crash checkpoint' -ForEach @(
        @{ Checkpoint='before-registration'; ExpectedRegistered=$false },
        @{ Checkpoint='registration-action'; ExpectedRegistered=$false },
        @{ Checkpoint='disabled-readback'; ExpectedRegistered=$true }
    ) {
        InModuleScope Production.Install -Parameters @{
            FailureCheckpoint=$Checkpoint
            RegisteredAtFailure=$ExpectedRegistered
        } {
            param($FailureCheckpoint,$RegisteredAtFailure)
            $script:serviceRegistered = $false
            $serviceRoot = Join-Path $TestDrive "checkpoint-$FailureCheckpoint"
            $state = [pscustomobject]@{ ServiceRegistered=$false }
            Mock Protect-ProductionWebsiteServiceDirectory { $serviceRoot }
            Mock Set-ProductionMongoServiceInstallPolicy { }
            Mock Install-WinSwBinary {
                Join-Path $serviceRoot 'ChristopherBellDev.exe'
            }
            Mock Copy-Item { }
            Mock Publish-ProductionWebsiteWriterStartBundle {
                if ($FailureCheckpoint -eq 'before-registration') {
                    throw 'checkpoint before registration'
                }
            }
            Mock Get-ProductionWebsiteServiceOrNull {
                if ($script:serviceRegistered) {
                    return [pscustomobject]@{ Status='Stopped' }
                }
                return $null
            }
            Mock Invoke-ProductionWinSwServiceInstall {
                if ($FailureCheckpoint -eq 'registration-action') {
                    throw 'checkpoint during registration action'
                }
                $script:serviceRegistered = $true
            }
            Mock Set-ProductionWebsiteStartupType {
                if ($FailureCheckpoint -eq 'disabled-readback') {
                    throw 'checkpoint before Disabled readback'
                }
            }
            Mock Set-ProductionWebsiteServiceInstallPolicy { }
            Mock Assert-ProductionWebsiteServiceBoundary { }
            Mock Start-Service { throw 'website must never run at a crash checkpoint' }

            { Install-WebsiteService `
                    -Root $TestDrive `
                    -Configuration ([pscustomobject]@{ programDataRoot=$TestDrive }) `
                    -RegistrationState $state } | Should -Throw '*checkpoint*'

            $state.ServiceRegistered | Should -Be $RegisteredAtFailure
            Should -Invoke Set-ProductionWebsiteStartupType -ParameterFilter {
                $StartupType -eq 'Automatic'
            } -Times 0
            Should -Invoke Start-Service -Times 0
        }
    }
}

Describe 'production install root and lock bootstrap' {
    InModuleScope Production.Install {
        BeforeEach {
            $script:bootstrapEvents = [Collections.Generic.List[string]]::new()
            Mock Get-ProductionInstallDirectoryIdentity {
                $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
                [pscustomobject]@{
                    NativeFinalPath = [IO.Path]::GetFullPath($Path)
                    VolumeSerialNumber = [uint32]1
                    FileIndex = [uint64]$item.CreationTimeUtc.Ticks
                    ReparsePoint = [bool](
                        $item.Attributes -band [IO.FileAttributes]::ReparsePoint)
                }
            }
            Mock Assert-ProductionInstallRootParentBoundary {
                Get-ProductionInstallDirectoryIdentity -Path $Path
            }
            Mock Assert-ProductionInstallRootParentState {
                Get-ProductionInstallDirectoryIdentity -Path $Path
            }
            Mock New-ProductionInstallProtectedDirectoryNative {
                Microsoft.PowerShell.Management\New-Item `
                    -ItemType Directory -Path $Path -ErrorAction Stop | Out-Null
                Get-ProductionInstallDirectoryIdentity -Path $Path
            }
            Mock New-ProductionInstallProtectedChildDirectoryNative {
                $child = Join-Path $ParentPath $ChildName
                Microsoft.PowerShell.Management\New-Item `
                    -ItemType Directory -Path $child -ErrorAction Stop | Out-Null
                Get-ProductionInstallDirectoryIdentity -Path $child
            }
            Mock Move-ProductionInstallRootStageNew {
                Move-Item -LiteralPath $Source -Destination $Destination `
                    -ErrorAction Stop
            }
            Mock Protect-ProductionPath {
                [void]$script:bootstrapEvents.Add("protect:$Path")
            }
            Mock Assert-ProtectedProductionPath {
                [void]$script:bootstrapEvents.Add("verify:$Path")
            }
            Mock Get-ProductionWebsiteServiceOrNull {
                throw 'service discovery must not run'
            }
            Mock New-ProductionDirectories { throw 'directory install must not run' }
            Mock Install-ConfigurationExamples { throw 'config write must not run' }
            Mock Read-ProductionConfig { throw 'config read must not run' }
            Mock Install-CloudflaredService { throw 'cloudflared effect must not run' }
            Mock Install-WinSwBinary { throw 'WinSW write must not run' }
            Mock Copy-Item { throw 'XML write must not run' }
            Mock Publish-ProductionWebsiteWriterStartBundle {
                throw 'publisher effect must not run'
            }
            Mock Enter-DeploymentLock { throw 'lock acquisition must not run' }
        }

        It 'rejects an existing unprotected normal root without repairing or descending into it' {
            $root = Join-Path $TestDrive 'unprotected-existing-root'
            New-Item -ItemType Directory -Path $root | Out-Null
            'preserve-root' | Set-Content -LiteralPath (Join-Path $root 'sentinel.txt')
            Mock Assert-ProtectedProductionPath {
                if ($Path -eq $root) { throw 'existing production root is unprotected' }
            }

            { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                Should -Throw '*existing production root is unprotected*'

            Should -Invoke Protect-ProductionPath -Times 0 -ParameterFilter {
                $Path -eq $root
            }
            Should -Invoke Enter-DeploymentLock -Times 0
            Test-Path -LiteralPath (Join-Path $root 'locks') | Should -BeFalse
            Get-Content -LiteralPath (Join-Path $root 'sentinel.txt') -Raw |
                Should -Match 'preserve-root'
        }

        It 'rejects an install parent that grants untrusted replacement control' {
            Mock Assert-ProductionInstallRootParentBoundary {
                throw 'Production install root parent grants untrusted replacement control'
            }

            { Assert-ProductionInstallRootParentBoundary -Path $TestDrive } |
                Should -Throw '*replacement control*'
        }

        It 'does not create locks through a root replaced during child creation' {
            $root = Join-Path $TestDrive 'child-race-root'
            $target = Join-Path $TestDrive 'child-race-target'
            New-Item -ItemType Directory -Path $root,$target | Out-Null
            $sentinel = Join-Path $target 'sentinel.txt'
            'redirect-target' | Set-Content -LiteralPath $sentinel
            $targetAcl = (Get-Acl -LiteralPath $target).Sddl
            $targetWriteTime = (Get-Item -LiteralPath $target).LastWriteTimeUtc.Ticks
            Mock New-ProductionInstallProtectedChildDirectoryNative {
                Remove-Item -LiteralPath $root
                New-Item -ItemType Junction -Path $root -Target $target | Out-Null
                [ChristopherBell.Dev.ProductionInstallNativeDirectory]::
                    CreateProtectedChildDirectoryNew(
                        $root,
                        [uint32]$ParentIdentity.VolumeSerialNumber,
                        [uint64]$ParentIdentity.FileIndex,
                        $ChildName,
                        $script:ProtectedProductionDirectorySddl)
            }
            try {
                { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                    Should -Throw

                Should -Invoke Enter-DeploymentLock -Times 0
                Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
                Should -Invoke New-ProductionDirectories -Times 0
                Test-Path -LiteralPath (Join-Path $target 'locks') | Should -BeFalse
                Get-Content -LiteralPath $sentinel -Raw | Should -Match 'redirect-target'
                (Get-Acl -LiteralPath $target).Sddl | Should -BeExactly $targetAcl
                (Get-Item -LiteralPath $target).LastWriteTimeUtc.Ticks |
                    Should -Be $targetWriteTime
            } finally {
                if (Test-Path -LiteralPath $root) {
                    [IO.Directory]::Delete($root, $false)
                }
            }
        }

        It 'cleans a partial staged root when protection fails before publication' {
            $parent = Join-Path $TestDrive 'partial-stage-parent'
            $root = Join-Path $parent 'production'
            New-Item -ItemType Directory -Path $parent | Out-Null
            Mock Assert-ProtectedProductionPath {
                if ((Split-Path -Leaf $Path) -like '.production.install-*') {
                    throw 'staged root protection failed'
                }
            }

            { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                Should -Throw '*staged root protection failed*'

            Test-Path -LiteralPath $root | Should -BeFalse
            @(Get-ChildItem -LiteralPath $parent -Force -Filter '.production.install-*').Count |
                Should -Be 0
            Should -Invoke Enter-DeploymentLock -Times 0
        }

        It 'rejects a disposable production-root junction before lock or downstream effect' {
            $target = Join-Path $TestDrive 'root-junction-target'
            $root = Join-Path $TestDrive 'root-junction'
            New-Item -ItemType Directory -Path $target | Out-Null
            New-Item -ItemType Junction -Path $root -Target $target | Out-Null
            try {
                { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                    Should -Throw '*reparse*'

                Should -Invoke Enter-DeploymentLock -Times 0
                Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
                Should -Invoke New-ProductionDirectories -Times 0
                Should -Invoke Install-ConfigurationExamples -Times 0
                Should -Invoke Install-CloudflaredService -Times 0
                Should -Invoke Install-WinSwBinary -Times 0
                Should -Invoke Copy-Item -Times 0
                Should -Invoke Publish-ProductionWebsiteWriterStartBundle -Times 0
                @(Get-ChildItem -LiteralPath $target -Force).Count | Should -Be 0
            } finally {
                if (Test-Path -LiteralPath $root) {
                    [IO.Directory]::Delete($root, $false)
                }
            }
        }

        It 'rejects a disposable locks junction before opening deploy.lock or touching its target' {
            $root = Join-Path $TestDrive 'locks-junction-root'
            $target = Join-Path $TestDrive 'locks-junction-target'
            New-Item -ItemType Directory -Path $root,$target | Out-Null
            $locks = Join-Path $root 'locks'
            New-Item -ItemType Junction -Path $locks -Target $target | Out-Null
            try {
                { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                    Should -Throw '*reparse*'

                Should -Invoke Enter-DeploymentLock -Times 0
                Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
                Should -Invoke Install-ConfigurationExamples -Times 0
                Should -Invoke Install-CloudflaredService -Times 0
                Should -Invoke Install-WinSwBinary -Times 0
                Should -Invoke Copy-Item -Times 0
                Should -Invoke Publish-ProductionWebsiteWriterStartBundle -Times 0
                @(Get-ChildItem -LiteralPath $target -Force).Count | Should -Be 0
            } finally {
                if (Test-Path -LiteralPath $locks) {
                    Remove-Item -LiteralPath $locks -Force
                }
            }
        }

        It 'fails closed when a missing root component cannot be created' {
            $parent = Join-Path $TestDrive 'create-failure-parent'
            $root = Join-Path $parent 'production'
            New-Item -ItemType Directory -Path $parent | Out-Null
            Mock New-ProductionInstallProtectedDirectoryNative {
                throw 'root component creation denied'
            }

            { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                Should -Throw '*root component creation denied*'

            Should -Invoke Enter-DeploymentLock -Times 0
            Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
            Test-Path -LiteralPath $root | Should -BeFalse
        }

        It 'detects a locks reparse replacement while establishing the protected boundary' {
            $root = Join-Path $TestDrive 'protect-race-root'
            $target = Join-Path $TestDrive 'protect-race-target'
            New-Item -ItemType Directory -Path $root,$target | Out-Null
            $locks = Join-Path $root 'locks'
            New-Item -ItemType Directory -Path $locks | Out-Null
            Mock Assert-ProtectedProductionPath {
                if ($Path -eq $root) {
                    Remove-Item -LiteralPath $locks
                    New-Item -ItemType Junction -Path $locks -Target $target | Out-Null
                }
            }
            try {
                { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                    Should -Throw '*reparse*'

                Should -Invoke Enter-DeploymentLock -Times 0
                Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
                @(Get-ChildItem -LiteralPath $target -Force).Count | Should -Be 0
            } finally {
                if (Test-Path -LiteralPath $locks) {
                    Remove-Item -LiteralPath $locks -Force
                }
            }
        }

        It 'revalidates the root and locks boundary under deploy.lock before downstream effect' {
            $root = Join-Path $TestDrive 'under-lock-race-root'
            $target = Join-Path $TestDrive 'under-lock-race-target'
            New-Item -ItemType Directory -Path $root,$target | Out-Null
            $locks = Join-Path $root 'locks'
            $lock = [pscustomobject]@{}
            $lock | Add-Member ScriptMethod Dispose {
                [void]$script:bootstrapEvents.Add('lock:release')
            }
            Mock Enter-DeploymentLock {
                Remove-Item -LiteralPath $locks
                New-Item -ItemType Junction -Path $locks -Target $target | Out-Null
                [void]$script:bootstrapEvents.Add('lock:acquire')
                $lock
            }
            try {
                { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                    Should -Throw '*reparse*'

                Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
                Should -Invoke New-ProductionDirectories -Times 0
                $script:bootstrapEvents | Should -Contain 'lock:release'
                @(Get-ChildItem -LiteralPath $target -Force).Count | Should -Be 0
            } finally {
                if (Test-Path -LiteralPath $locks) {
                    Remove-Item -LiteralPath $locks -Force
                }
            }
        }

        It 'accepts a competing protected root creator and removes its unused stage' {
            $parent = Join-Path $TestDrive 'benign-creator-parent'
            $root = Join-Path $parent 'production'
            New-Item -ItemType Directory -Path $parent | Out-Null
            Mock Move-ProductionInstallRootStageNew {
                New-Item -ItemType Directory -Path $Destination | Out-Null
                throw 'destination already exists'
            }

            $boundary = Initialize-ProductionDeploymentLockDirectory -Root $root

            $boundary.Root | Should -Be ([IO.Path]::GetFullPath($root))
            Test-Path -LiteralPath (Join-Path $root 'locks') | Should -BeTrue
            @(Get-ChildItem -LiteralPath $parent -Force `
                -Filter '.production.install-*').Count | Should -Be 0
        }

        It 'uses a fresh unpredictable sibling name for each staged root attempt' {
            $parent = Join-Path $TestDrive 'unique-stage-parent'
            $root = Join-Path $parent 'production'
            New-Item -ItemType Directory -Path $parent | Out-Null
            $stages = [Collections.Generic.List[string]]::new()
            Mock New-ProductionInstallProtectedDirectoryNative {
                [void]$stages.Add($Path)
                throw 'stop after capturing stage'
            }

            1..2 | ForEach-Object {
                { Initialize-ProductionInstallRootBoundary -Root $root } |
                    Should -Throw '*capturing stage*'
            }

            $stages.Count | Should -Be 2
            $stages[0] | Should -Not -BeExactly $stages[1]
            foreach ($stage in $stages) {
                Split-Path -Leaf $stage |
                    Should -Match '^\.production\.install-[0-9a-f]{32}$'
            }
        }

        It 'rejects an unsafe root published by a racing creator without touching its target' {
            $parent = Join-Path $TestDrive 'publish-race-parent'
            $root = Join-Path $parent 'production'
            $target = Join-Path $TestDrive 'publish-race-target'
            New-Item -ItemType Directory -Path $parent,$target | Out-Null
            $sentinel = Join-Path $target 'sentinel.txt'
            'publish-target' | Set-Content -LiteralPath $sentinel
            $targetAcl = (Get-Acl -LiteralPath $target).Sddl
            $targetWriteTime = (Get-Item -LiteralPath $target).LastWriteTimeUtc.Ticks
            Mock Move-ProductionInstallRootStageNew {
                New-Item -ItemType Junction -Path $Destination -Target $target |
                    Out-Null
                throw 'destination already exists'
            }
            try {
                { Invoke-ProductionRuntimeInstallAtRoot -Root $root } |
                    Should -Throw '*unsafe creation race*'

                Should -Invoke Enter-DeploymentLock -Times 0
                Should -Invoke Get-ProductionWebsiteServiceOrNull -Times 0
                Should -Invoke New-ProductionDirectories -Times 0
                Get-Content -LiteralPath $sentinel -Raw |
                    Should -Match 'publish-target'
                @(Get-ChildItem -LiteralPath $target -Force).Count | Should -Be 1
                (Get-Acl -LiteralPath $target).Sddl | Should -BeExactly $targetAcl
                (Get-Item -LiteralPath $target).LastWriteTimeUtc.Ticks |
                    Should -Be $targetWriteTime
                @(Get-ChildItem -LiteralPath $parent -Force `
                    -Filter '.production.install-*').Count | Should -Be 0
            } finally {
                if (Test-Path -LiteralPath $root) {
                    [IO.Directory]::Delete($root, $false)
                }
            }
        }
    }
}

Describe 'production install root parent boundary' {
    InModuleScope Production.Install {
        It 'rejects a real disposable parent with untrusted replacement control' {
            { Assert-ProductionInstallRootParentBoundary -Path $TestDrive } |
                Should -Throw '*replacement control*'
        }
    }
}

Describe 'production install root real Windows ACL boundary' {
    It 'publishes a protected disposable root before creating protected locks' `
            -Skip:($env:CBELL_RUN_INSTALL_ROOT_ACL_TESTS -ne '1') {
        InModuleScope Production.Install {
            $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
            $principal = [Security.Principal.WindowsPrincipal]$identity
            if (-not $principal.IsInRole(
                    [Security.Principal.WindowsBuiltInRole]::Administrator)) {
                throw 'Real install-root ACL integration requires elevated PowerShell.'
            }
            $parent = Join-Path ([IO.Path]::GetTempPath()) (
                'cbell-install-root-acl-' + [guid]::NewGuid().ToString('N'))
            $root = Join-Path $parent 'production'
            $boundary = $null
            New-Item -ItemType Directory -Path $parent -ErrorAction Stop |
                Out-Null
            try {
                Protect-ProductionPath -Path $parent
                $boundary = Initialize-ProductionDeploymentLockDirectory `
                    -Root $root

                Assert-ProductionInstallProtectedDirectoryState `
                    -Path $boundary.Root `
                    -ExpectedIdentity $boundary.RootIdentity | Out-Null
                Assert-ProductionInstallProtectedDirectoryState `
                    -Path $boundary.Locks `
                    -ExpectedIdentity $boundary.LocksIdentity | Out-Null
                @(Get-ChildItem -LiteralPath $parent -Force `
                    -Filter '.production.install-*').Count | Should -Be 0
            } finally {
                if ($null -ne $boundary -and
                    (Test-Path -LiteralPath $boundary.Locks)) {
                    Assert-ProductionInstallDirectoryIdentity `
                        -Path $boundary.Locks `
                        -ExpectedIdentity $boundary.LocksIdentity | Out-Null
                    [IO.Directory]::Delete($boundary.Locks, $false)
                }
                if ($null -ne $boundary -and
                    (Test-Path -LiteralPath $boundary.Root)) {
                    Assert-ProductionInstallDirectoryIdentity `
                        -Path $boundary.Root `
                        -ExpectedIdentity $boundary.RootIdentity | Out-Null
                    [IO.Directory]::Delete($boundary.Root, $false)
                }
                if (Test-Path -LiteralPath $parent) {
                    [IO.Directory]::Delete($parent, $false)
                }
            }
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
            Mock Initialize-ProductionDeploymentLockDirectory {
                [pscustomobject]@{
                    Root='C:\ProgramData\christopherbell.dev'
                    Locks='C:\ProgramData\christopherbell.dev\locks'
                    LockPath='C:\ProgramData\christopherbell.dev\locks\deploy.lock'
                }
            }
            Mock Assert-ProductionDeploymentLockBoundary { }
            Mock New-ProductionDirectories { }
            Mock Install-ConfigurationExamples { }
            Mock Read-ProductionConfig { $script:config }
            Mock Assert-ProductionConfiguredRootBoundary { }
            Mock Read-ProductionWebsiteStopPort { [int]$script:config.productionPort }
            Mock Read-ProductionEnvironment { @{} }
            Mock Protect-ProductionSecrets { }
            Mock Protect-ProductionWebsiteServiceDirectory { }
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
            Mock Stop-ProductionWebsiteServiceWithoutPort {
                $script:serviceStatus = 'Stopped'
                [void]$script:events.Add('stop-without-port')
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

        It 'reads only the legacy stop port before stop and upgrades before full validation' {
            $script:serviceStatus = 'Running'
            Mock Read-ProductionWebsiteStopPort {
                [void]$script:events.Add('legacy-port')
                8080
            }
            Mock Install-ConfigurationExamples {
                [void]$script:events.Add('upgrade-defaults')
            }
            Mock Read-ProductionConfig {
                [void]$script:events.Add('full-config')
                $script:config
            }

            Install-ProductionRuntime

            $script:events.IndexOf('startup:Disabled') |
                Should -BeLessThan $script:events.IndexOf('legacy-port')
            $script:events.IndexOf('legacy-port') |
                Should -BeLessThan $script:events.IndexOf('stop')
            $script:events.IndexOf('stop') |
                Should -BeLessThan $script:events.IndexOf('upgrade-defaults')
            $script:events.IndexOf('upgrade-defaults') |
                Should -BeLessThan $script:events.IndexOf('full-config')
        }

        It 'rejects an alternate configured root before any config-derived installation effect' {
            $alternateRoot = Join-Path $TestDrive 'alternate-configured-root'
            New-Item -ItemType Directory -Path $alternateRoot | Out-Null
            $sentinel = Join-Path $alternateRoot 'sentinel.txt'
            'alternate-root' | Set-Content -LiteralPath $sentinel
            $targetAcl = (Get-Acl -LiteralPath $alternateRoot).Sddl
            $targetWriteTime = (Get-Item -LiteralPath $alternateRoot).LastWriteTimeUtc.Ticks
            $script:config.programDataRoot = $alternateRoot
            Mock Assert-ProductionConfiguredRootBoundary {
                throw 'Configured production root does not match the locked production boundary.'
            }
            Mock Protect-ProductionWebsiteServiceDirectory {
                throw 'configured service ACL effect must not run'
            }
            Mock Install-CloudflaredService {
                throw 'configured cloudflared effect must not run'
            }
            Mock Install-WebsiteService {
                throw 'configured website effect must not run'
            }

            { Install-ProductionRuntime } |
                Should -Throw '*configured production root*locked*'

            Should -Invoke Protect-ProductionWebsiteServiceDirectory -Times 0
            Should -Invoke Install-CloudflaredService -Times 0
            Should -Invoke Install-WebsiteService -Times 0
            Get-Content -LiteralPath $sentinel -Raw | Should -Match 'alternate-root'
            (Get-Acl -LiteralPath $alternateRoot).Sddl | Should -BeExactly $targetAcl
            (Get-Item -LiteralPath $alternateRoot).LastWriteTimeUtc.Ticks |
                Should -Be $targetWriteTime
        }

        It 'rejects a malformed legacy production port before any port-targeted stop' {
            $script:serviceStatus = 'Running'
            Mock Read-ProductionWebsiteStopPort { throw 'productionPort is malformed' }

            { Install-ProductionRuntime } |
                Should -Throw '*productionPort is malformed*'

            Should -Invoke Stop-ProductionWebsiteService -Times 0
            Should -Invoke Stop-ProductionWebsiteServiceWithoutPort -Times 1
            Should -Invoke Install-ConfigurationExamples -Times 0
            $script:startupType | Should -Be 'Disabled'
            $script:serviceStatus | Should -Be 'Stopped'
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
            Should -Invoke Stop-ProductionWebsiteServiceWithoutPort
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

        It 'does not report service disappearance for a pre-registration install failure' `
                -ForEach @(
            @{ Failure='service directory ACL verification failed' },
            @{ Failure='WinSW digest verification failed' },
            @{ Failure='service XML digest verification failed' }
        ) {
            $script:serviceStatus = $null
            Mock Get-ProductionWebsiteServiceOrNull {
                [void]$script:events.Add('state:absent')
                $null
            }
            Mock Install-WebsiteService { throw $Failure }

            $caught = $null
            try { Install-ProductionRuntime } catch { $caught = $_.Exception }

            $caught | Should -BeOfType ([System.InvalidOperationException])
            $caught.Message | Should -Match 'no website service is registered'
            $caught.Message | Should -Match ([regex]::Escape($Failure))
            $caught.Message | Should -Not -Match 'disappeared'
        }

        It 'contains a service observed after registration when Disabled verification fails' {
            $script:serviceStatus = $null
            $script:serviceRegistered = $false
            Mock Get-ProductionWebsiteServiceOrNull {
                if (-not $script:serviceRegistered) { return $null }
                [pscustomobject]@{ Name='ChristopherBellDev'; Status=$script:serviceStatus }
            }
            Mock Install-WebsiteService {
                $RegistrationState.ServiceRegistered = $true
                $script:serviceRegistered = $true
                $script:serviceStatus = 'Stopped'
                throw 'Disabled startup readback failed'
            }

            { Install-ProductionRuntime } | Should -Throw '*Disabled startup readback failed*'

            $script:startupType | Should -Be 'Disabled'
            $script:serviceStatus | Should -Be 'Stopped'
            Should -Invoke Set-ProductionWebsiteStartupType -ParameterFilter {
                $StartupType -eq 'Automatic'
            } -Times 0
            Should -Invoke Start-Service -Times 0
        }
    }
}

Describe 'configured production root identity' {
    InModuleScope Production.Install {
        It 'rejects an alternate normal root before reading or changing that target' {
            $lockedRoot = Join-Path $TestDrive 'locked-root'
            $alternateRoot = Join-Path $TestDrive 'alternate-root'
            New-Item -ItemType Directory -Path $lockedRoot,$alternateRoot |
                Out-Null
            $sentinel = Join-Path $alternateRoot 'sentinel.txt'
            'alternate-root' | Set-Content -LiteralPath $sentinel
            $targetAcl = (Get-Acl -LiteralPath $alternateRoot).Sddl
            $targetWriteTime = (Get-Item -LiteralPath $alternateRoot).
                LastWriteTimeUtc.Ticks
            $configuration = [pscustomobject]@{
                programDataRoot = $alternateRoot
            }
            $boundary = [pscustomobject]@{
                Root = [IO.Path]::GetFullPath($lockedRoot)
                RootIdentity = [pscustomobject]@{
                    VolumeSerialNumber = 1
                    FileIndex = 1
                }
            }
            Mock Assert-ProductionInstallProtectedDirectoryState {
                throw 'alternate target must not be inspected'
            }

            { Assert-ProductionConfiguredRootBoundary `
                    -Configuration $configuration -Boundary $boundary } |
                Should -Throw '*does not match the locked production boundary*'

            Should -Invoke Assert-ProductionInstallProtectedDirectoryState `
                -Times 0
            Get-Content -LiteralPath $sentinel -Raw |
                Should -Match 'alternate-root'
            (Get-Acl -LiteralPath $alternateRoot).Sddl |
                Should -BeExactly $targetAcl
            (Get-Item -LiteralPath $alternateRoot).LastWriteTimeUtc.Ticks |
                Should -Be $targetWriteTime
        }

        It 'matches the locked canonical root with ordinal Windows casing' {
            $root = [IO.Path]::GetFullPath((Join-Path $TestDrive 'CaseRoot'))
            $configuration = [pscustomobject]@{
                programDataRoot = $root.ToUpperInvariant()
            }
            $identity = [pscustomobject]@{
                VolumeSerialNumber = 1
                FileIndex = 1
            }
            $boundary = [pscustomobject]@{
                Root = $root.ToLowerInvariant()
                RootIdentity = $identity
            }
            Mock Assert-ProductionInstallProtectedDirectoryState { }

            Assert-ProductionConfiguredRootBoundary `
                -Configuration $configuration -Boundary $boundary

            $configuration.programDataRoot |
                Should -BeExactly $boundary.Root
            Should -Invoke Assert-ProductionInstallProtectedDirectoryState `
                -Times 1 -ParameterFilter {
                    $Path -eq $boundary.Root -and
                    $ExpectedIdentity -eq $identity
                }
        }

        It 'rejects relative traversal in the configured root before boundary use' {
            $root = [IO.Path]::GetFullPath((Join-Path $TestDrive 'root'))
            $configuration = [pscustomobject]@{
                programDataRoot = Join-Path $root 'child\..'
            }
            $boundary = [pscustomobject]@{
                Root = $root
                RootIdentity = [pscustomobject]@{
                    VolumeSerialNumber = 1
                    FileIndex = 1
                }
            }
            Mock Assert-ProductionInstallProtectedDirectoryState {
                throw 'traversing config must not reach the boundary'
            }

            { Assert-ProductionConfiguredRootBoundary `
                    -Configuration $configuration -Boundary $boundary } |
                Should -Throw '*relative path traversal*'

            Should -Invoke Assert-ProductionInstallProtectedDirectoryState `
                -Times 0
        }
    }
}

Describe 'legacy website stop-port parser' {
    InModuleScope Production.Install {
        BeforeEach {
            $script:legacyParserRoot = Join-Path $TestDrive 'legacy-parser-root'
            $script:legacyParserConfig = Join-Path `
                $script:legacyParserRoot 'config\deploy.json'
            New-Item -ItemType Directory `
                -Path (Split-Path -Parent $script:legacyParserConfig) -Force | Out-Null
        }

        It 'accepts only integral JSON number boundary values' -ForEach @(
            @{ Json='1'; Expected=1 },
            @{ Json='8080'; Expected=8080 },
            @{ Json='65535'; Expected=65535 }
        ) {
            "{`"productionPort`":$Json}" |
                Set-Content -LiteralPath $script:legacyParserConfig

            Read-ProductionWebsiteStopPort -Root $script:legacyParserRoot |
                Should -Be $Expected
        }

        It 'rejects a non-integral, missing, or out-of-range productionPort' -ForEach @(
            @{ Json='{}' },
            @{ Json='{"productionPort":null}' },
            @{ Json='{"productionPort":"8080"}' },
            @{ Json='{"productionPort":true}' },
            @{ Json='{"productionPort":8080.0}' },
            @{ Json='{"productionPort":[8080]}' },
            @{ Json='{"productionPort":{"value":8080}}' },
            @{ Json='{"productionPort":0}' },
            @{ Json='{"productionPort":-1}' },
            @{ Json='{"productionPort":65536}' }
        ) {
            $Json | Set-Content -LiteralPath $script:legacyParserConfig

            { Read-ProductionWebsiteStopPort -Root $script:legacyParserRoot } |
                Should -Throw '*Legacy deploy config productionPort*'
        }

        It 'redacts raw configuration when malformed JSON cannot be parsed' {
            $secret = 'DO_NOT_LEAK_LEGACY_CONFIG_7d4266f5'
            "{`"productionPort`":8080,`"apiToken`":`"$secret`"" |
                Set-Content -LiteralPath $script:legacyParserConfig

            $caught = $null
            try {
                Read-ProductionWebsiteStopPort -Root $script:legacyParserRoot | Out-Null
            } catch {
                $caught = $_.Exception
            }

            $caught | Should -Not -BeNullOrEmpty
            $caught.Message | Should -Match 'could not be parsed for a safe website stop'
            $caught.ToString() | Should -Not -Match $secret
        }
    }
}

Describe 'legacy native runtime reinstall upgrade' {
    InModuleScope Production.Install {
        It 'stops a prior running writer then upgrades missing defaults and restores health' {
            $root = Join-Path $TestDrive 'legacy-runtime-root'
            New-ProductionDirectories -Root $root
            $repository = Join-Path $root 'repository'
            $backup = Join-Path $root 'backup'
            $mongoTools = Join-Path $root 'mongo-tools'
            New-Item -ItemType Directory -Path $repository,$backup,$mongoTools | Out-Null
            $java = Join-Path $root 'java.exe'
            $node = Join-Path $root 'node.exe'
            $mongoShell = Join-Path $root 'mongosh.exe'
            $cloudflared = Join-Path $root 'cloudflared.exe'
            foreach ($executable in $java,$node,$mongoShell,$cloudflared) {
                'test executable' | Set-Content -LiteralPath $executable
            }
            $deployPath = Join-Path $root 'config\deploy.json'
            [ordered]@{
                repositoryPath=$repository
                remote='origin'
                branch='main'
                programDataRoot=$root
                javaExe=$java
                nodeExe=$node
                mongoToolsPath=$mongoTools
                mongoShellExe=$mongoShell
                cloudflaredExe=$cloudflared
                backupRoot=$backup
                publicUrl='https://www.christopherbell.dev/'
                candidatePort=8081
                productionPort=8080
                smokeAccountEmail='admin@christopherbell.dev'
            } | ConvertTo-Json | Set-Content -LiteralPath $deployPath
            @(
                'APP_JWT_SECRET=0123456789abcdef0123456789abcdef'
                'SPRING_MONGODB_URI=mongodb://127.0.0.1:27017/christopherbell'
                'APP_MAIL_ENABLED=false'
            ) | Set-Content -LiteralPath (Join-Path $root 'config\app.env')

            $script:legacyEvents = [Collections.Generic.List[string]]::new()
            $script:legacyServiceStatus = 'Running'
            Mock Initialize-ProductionDeploymentLockDirectory {
                [pscustomobject]@{
                    Root = [IO.Path]::GetFullPath($root)
                    RootIdentity = [pscustomobject]@{
                        VolumeSerialNumber = 1
                        FileIndex = 1
                    }
                    Locks = [IO.Path]::GetFullPath((Join-Path $root 'locks'))
                    LockPath = [IO.Path]::GetFullPath(
                        (Join-Path $root 'locks\deploy.lock'))
                }
            }
            Mock Assert-ProductionDeploymentLockBoundary { }
            Mock Assert-ProductionInstallProtectedDirectoryState { }
            Mock Enter-DeploymentLock {
                $lock = [pscustomobject]@{}
                $lock | Add-Member ScriptMethod Dispose { }
                $lock
            }
            Mock Protect-ProductionPath { }
            Mock Assert-ProtectedProductionPath { }
            Mock Protect-ProductionSecrets { }
            Mock Get-ProductionWebsiteServiceOrNull {
                [pscustomobject]@{
                    Name='ChristopherBellDev'
                    Status=$script:legacyServiceStatus
                }
            }
            Mock Set-ProductionWebsiteStartupType {
                [void]$script:legacyEvents.Add("startup:$StartupType")
            }
            Mock Stop-ProductionWebsiteService {
                $beforeUpgrade = Get-Content -LiteralPath $deployPath -Raw |
                    ConvertFrom-Json
                $beforeUpgrade.PSObject.Properties.Name |
                    Should -Not -Contain 'sensorLibrariesEnabled'
                $script:legacyServiceStatus = 'Stopped'
                [void]$script:legacyEvents.Add("stop:$ProductionPort")
            }
            Mock Protect-ProductionWebsiteServiceDirectory { }
            Mock Install-CloudflaredService { }
            Mock Install-WebsiteService {
                [void]$script:legacyEvents.Add('website-install')
            }
            Mock Install-SharedFolderRuntime {
                [void]$script:legacyEvents.Add('shared-install')
            }
            Mock Assert-ProductionWebsiteServiceBoundary {
                [void]$script:legacyEvents.Add('boundary-verified')
            }
            Mock Start-Service {
                $script:legacyServiceStatus = 'Running'
                [void]$script:legacyEvents.Add('start')
            }
            Mock Test-ProductionEndpoints {
                [void]$script:legacyEvents.Add('health')
            }
            Mock Test-ProductionPublicEndpoints {
                [void]$script:legacyEvents.Add('public-health')
            }

            Invoke-ProductionRuntimeInstallAtRoot -Root $root

            $updated = Get-Content -LiteralPath $deployPath -Raw | ConvertFrom-Json
            $updated.sensorLibrariesEnabled | Should -BeFalse
            $updated.releaseRetention | Should -Be 5
            $updated.autoDeployPollSeconds | Should -Be 60
            $updated.autoDeployFailureBackoffSeconds | Should -Be 900
            $script:legacyEvents | Should -Be @(
                'startup:Disabled',
                'stop:8080',
                'website-install',
                'shared-install',
                'boundary-verified',
                'startup:Automatic',
                'start',
                'health',
                'public-health')
            $script:legacyServiceStatus | Should -Be 'Running'
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
