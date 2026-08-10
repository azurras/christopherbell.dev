Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Force

Describe 'production writer-start schema boundary' {
    InModuleScope Production.WriterStart {
        BeforeEach {
            $script:config = [pscustomobject]@{ programDataRoot=$TestDrive; mongoShellExe='mongosh.exe' }
            Mock Protect-ProductionPath {}
            Mock Assert-ProtectedProductionPath { $true }
            Mock Protect-ProductionWriterStartServiceDirectory { }
            Mock Assert-ProductionWriterStartServiceDirectory { }
            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            New-Item -ItemType Directory -Path (Split-Path -Parent $markerPath) -Force |
                Out-Null
            [ordered]@{
                version=1
                state='TARGET_ACTIVE'
                updatedAtEpochMillis=1
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            } | ConvertTo-Json | Set-Content -LiteralPath $markerPath
        }

        It 'allows only the exact release bound by a stable target marker' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='1111111111111111111111111111111111111111'; musicSchema='TARGET' }
            }
            Mock Read-ProductionMusicSchemaDirection {
                [pscustomobject]@{
                    state='TARGET_ACTIVE'
                    targetRelease='1111111111111111111111111111111111111111'
                    legacyRelease='2222222222222222222222222222222222222222'
                }
            }

            { Assert-ProductionWriterStartAllowed -Config $script:config } |
                Should -Not -Throw
        }

        It 'fails closed when a stable marker release differs and no authorization exists' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='3333333333333333333333333333333333333333'; musicSchema='TARGET' }
            }
            Mock Read-ProductionMusicSchemaDirection {
                [pscustomobject]@{
                    state='TARGET_ACTIVE'
                    targetRelease='1111111111111111111111111111111111111111'
                    legacyRelease='2222222222222222222222222222222222222222'
                }
            }

            { Assert-ProductionWriterStartAllowed -Config $script:config } |
                Should -Throw '*incompatible*blocked*'
        }

        It 'permits a fresh legacy start only after proving migration inactive' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='1111111111111111111111111111111111111111'; musicSchema='LEGACY' }
            }
            Mock Read-ProductionMusicSchemaDirection { $null }
            Mock Get-ProductionMusicMigrationActivationForWriterStart { $false }

            { Assert-ProductionWriterStartAllowed -Config $script:config } |
                Should -Not -Throw
        }

        It 'blocks an absent marker when migration is active or the probe is unknown' -ForEach @(
            @{ Mode='active' }, @{ Mode='unknown' }
        ) {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='1111111111111111111111111111111111111111'; musicSchema='LEGACY' }
            }
            Mock Read-ProductionMusicSchemaDirection { $null }
            if ($Mode -eq 'active') {
                Mock Get-ProductionMusicMigrationActivationForWriterStart { $true }
            } else {
                Mock Get-ProductionMusicMigrationActivationForWriterStart { throw 'unknown' }
            }

            { Assert-ProductionWriterStartAllowed -Config $script:config } | Should -Throw
        }

        It 'consumes an exact pending start authorization once' {
            $marker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY

            (Use-ProductionWriterStartAuthorization -Config $script:config `
                -Marker $marker -ReleaseIdentity $release) | Should -BeTrue
            (Use-ProductionWriterStartAuthorization -Config $script:config `
                -Marker $marker -ReleaseIdentity $release) | Should -BeFalse
        }

        It 'binds pending authorization to the live issuer process identity' {
            $authorization = Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE `
                -Release '3333333333333333333333333333333333333333' `
                -Purpose TARGET_DEPLOY
            $path = Get-ProductionWriterStartAuthorizationPath -Config $script:config
            $stored = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json

            $authorization.nonce | Should -Be $stored.nonce
            $authorization.issuerPid | Should -Be $PID
            $authorization.issuerStartTimeUtcTicks | Should -BeGreaterThan 0
            $stored.issuerPid | Should -Be $PID
            $stored.issuerStartTimeUtcTicks | Should -Be $authorization.issuerStartTimeUtcTicks
        }

        It 'rejects pending authorization when the issuer died or its PID was reused' -ForEach @(
            @{ Kind='dead' }, @{ Kind='reused' }
        ) {
            $marker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY | Out-Null
            if ($Kind -eq 'dead') {
                Mock Get-Process { throw 'missing issuer' }
            } else {
                Mock Get-Process {
                    [pscustomobject]@{ StartTime=[datetime]::UtcNow.AddHours(-12).ToLocalTime() }
                }
            }

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $marker -ReleaseIdentity $release } | Should -Throw '*blocked*'
            Test-Path (Get-ProductionWriterStartAuthorizationPath -Config $script:config) |
                Should -BeFalse
        }

        It 'revokes only the exact returned authorization token idempotently' {
            $authorization = Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE `
                -Release '3333333333333333333333333333333333333333' `
                -Purpose TARGET_DEPLOY
            $path = Get-ProductionWriterStartAuthorizationPath -Config $script:config

            Revoke-ProductionWriterStartAuthorization `
                -Config $script:config -Authorization $authorization
            { Revoke-ProductionWriterStartAuthorization `
                    -Config $script:config -Authorization $authorization } | Should -Not -Throw

            Test-Path -LiteralPath $path | Should -BeFalse
        }

        It 'rejects authorization after either marker release identity changes' {
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY | Out-Null
            $changedMarker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='4444444444444444444444444444444444444444'
                legacyRelease='2222222222222222222222222222222222222222'
            }

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $changedMarker -ReleaseIdentity $release } |
                Should -Throw '*blocked*'
        }

        It 'publishes and verifies the installed launcher guard as one crash-safe bundle' {
            $source = Join-Path $TestDrive 'source'
            $service = Join-Path $TestDrive 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'guarded launcher' | Set-Content -LiteralPath $launcher
            'guard module' | Set-Content -LiteralPath $module
            'old launcher' | Set-Content -LiteralPath (Join-Path $service 'Start-ChristopherBellDev.ps1')
            $bundleConfig = [pscustomobject]@{ programDataRoot=$TestDrive }

            $result = Publish-ProductionWriterStartGuardBundle `
                -Config $bundleConfig `
                -SourceLauncherPath $launcher `
                -SourceModulePath $module

            Assert-ProductionWriterStartGuardBundle `
                -Config $bundleConfig `
                -ExpectedLauncherSha256 $result.launcherSha256 `
                -ExpectedModuleSha256 $result.moduleSha256
            (Get-FileHash (Join-Path $service 'Start-ChristopherBellDev.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() |
                Should -Be $result.launcherSha256
            (Get-FileHash (Join-Path $service 'Production.WriterStart.psm1') -Algorithm SHA256).Hash.ToLowerInvariant() |
                Should -Be $result.moduleSha256
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') | Should -BeTrue
        }

        It 'defines the exact shared-compatible protected service-directory ACL' {
            $acl = New-ProductionWriterStartServiceDirectoryAcl
            $rules = @($acl.GetAccessRules(
                $true,
                $false,
                [Security.Principal.SecurityIdentifier]))

            $acl.AreAccessRulesProtected | Should -BeTrue
            @($rules.IdentityReference.Value | Sort-Object) | Should -Be @(
                'S-1-5-18','S-1-5-19','S-1-5-32-544')
            ($rules | Where-Object IdentityReference -eq 'S-1-5-19').FileSystemRights |
                Should -Be (
                    [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
                    [Security.AccessControl.FileSystemRights]::Synchronize)
        }

        It 'hashes, protects, and verifies WinSW and its service XML in the committed bundle' {
            $root = Join-Path $TestDrive 'complete-host-boundary'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $winSw = Join-Path $service 'ChristopherBellDev.exe'
            $serviceXml = Join-Path $service 'ChristopherBellDev.xml'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            'winsw' | Set-Content $winSw
            'service xml' | Set-Content $serviceXml
            $winSwSha = (Get-FileHash $winSw -Algorithm SHA256).Hash.ToLowerInvariant()
            $xmlSha = (Get-FileHash $serviceXml -Algorithm SHA256).Hash.ToLowerInvariant()

            $result = Publish-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -SourceLauncherPath $launcher `
                -SourceModulePath $module `
                -ExpectedWinSwSha256 $winSwSha `
                -ExpectedServiceXmlSha256 $xmlSha

            $manifest = Get-Content (Join-Path $service 'Production.WriterStart.bundle.json') `
                -Raw | ConvertFrom-Json
            $manifest.version | Should -Be 2
            $manifest.winSwSha256 | Should -Be $winSwSha
            $manifest.serviceXmlSha256 | Should -Be $xmlSha
            Assert-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -ExpectedLauncherSha256 $result.launcherSha256 `
                -ExpectedModuleSha256 $result.moduleSha256 `
                -ExpectedWinSwSha256 $winSwSha `
                -ExpectedServiceXmlSha256 $xmlSha | Out-Null
            Should -Invoke Protect-ProductionPath -ParameterFilter {
                $Path -eq $winSw
            }
            Should -Invoke Protect-ProductionPath -ParameterFilter {
                $Path -eq $serviceXml
            }
        }

        It 'protects and verifies the canonical service directory before staging' {
            $root = Join-Path $TestDrive 'protected-parent'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            $events = [Collections.Generic.List[string]]::new()
            Mock Protect-ProductionWriterStartServiceDirectory {
                [void]$events.Add("protect:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Assert-ProductionWriterStartServiceDirectory {
                [void]$events.Add("verify:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Protect-ProductionPath {
                [void]$events.Add("protect:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Assert-ProtectedProductionPath {
                [void]$events.Add("verify:$([IO.Path]::GetFullPath($Path))")
            }

            Publish-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -SourceLauncherPath $launcher `
                -SourceModulePath $module | Out-Null

            $canonicalService = [IO.Path]::GetFullPath($service)
            $events[0] | Should -Be "protect:$canonicalService"
            $events[1] | Should -Be "verify:$canonicalService"
        }

        It 'rejects an installed bundle when its parent service directory ACL is unprotected' {
            $root = Join-Path $TestDrive 'unprotected-parent'
            $source = Join-Path $root 'source'
            New-Item -ItemType Directory -Path $source -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            $config = [pscustomobject]@{ programDataRoot=$root }
            $result = Publish-ProductionWriterStartGuardBundle `
                -Config $config -SourceLauncherPath $launcher -SourceModulePath $module
            $service = [IO.Path]::GetFullPath((Join-Path $root 'service'))
            Mock Assert-ProductionWriterStartServiceDirectory {
                throw 'service directory ACL is unprotected'
            }

            { Assert-ProductionWriterStartGuardBundle `
                    -Config $config `
                    -ExpectedLauncherSha256 $result.launcherSha256 `
                    -ExpectedModuleSha256 $result.moduleSha256 } |
                Should -Throw '*service directory ACL is unprotected*'
        }

        It 'rejects a production root reached through a reparse point' {
            $target = Join-Path $TestDrive 'reparse-target'
            $alias = Join-Path $TestDrive 'reparse-alias'
            $source = Join-Path $TestDrive 'reparse-source'
            New-Item -ItemType Directory -Path $target,$source -Force | Out-Null
            New-Item -ItemType Junction -Path $alias -Target $target | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            try {
                { Publish-ProductionWriterStartGuardBundle `
                        -Config ([pscustomobject]@{ programDataRoot=$alias }) `
                        -SourceLauncherPath $launcher `
                        -SourceModulePath $module } | Should -Throw '*reparse*'
            } finally {
                if (Test-Path -LiteralPath $alias) {
                    Remove-Item -LiteralPath $alias -Force
                }
            }
        }

        It 'rejects an existing installed reparse destination before publication' {
            $root = Join-Path $TestDrive 'installed-reparse'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $installedLauncher = Join-Path $service 'Start-ChristopherBellDev.ps1'
            'new launcher' | Set-Content $launcher
            'new module' | Set-Content $module
            'old launcher' | Set-Content $installedLauncher
            Mock Assert-ProductionWriterStartPathNotReparseTraversal {
                $canonical = [IO.Path]::GetFullPath($Path)
                if ([string]::Equals(
                        $canonical,
                        [IO.Path]::GetFullPath($installedLauncher),
                        [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'installed destination is a reparse point'
                }
                return $canonical
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*installed destination is a reparse point*'

            Get-Content $installedLauncher -Raw | Should -Match 'old launcher'
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'does not publish any guard file when staged ACL verification fails' {
            $source = Join-Path $TestDrive 'failed-source'
            $failedRoot = Join-Path $TestDrive 'failed-root'
            $service = Join-Path $failedRoot 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'guarded launcher' | Set-Content -LiteralPath $launcher
            'guard module' | Set-Content -LiteralPath $module
            'old launcher' | Set-Content -LiteralPath (Join-Path $service 'Start-ChristopherBellDev.ps1')
            Mock Assert-ProtectedProductionPath { throw 'ACL verification failed' }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$failedRoot }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*ACL verification failed*'

            Get-Content (Join-Path $service 'Start-ChristopherBellDev.ps1') -Raw |
                Should -Match 'old launcher'
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'does not publish staged files when readback hash verification fails' {
            $root = Join-Path $TestDrive 'hash-failure'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            $script:hashRead = 0
            Mock Get-FileHash {
                $script:hashRead++
                if ($script:hashRead -le 2) { [pscustomobject]@{ Hash=('a' * 64) } }
                else { [pscustomobject]@{ Hash=('b' * 64) } }
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*SHA-256*'

            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'leaves a fail-closed launcher and no commit manifest after partial publication' {
            $root = Join-Path $TestDrive 'partial-publication'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'new guarded launcher' | Set-Content $launcher
            'new module' | Set-Content $module
            'old unguarded launcher' | Set-Content (Join-Path $service 'Start-ChristopherBellDev.ps1')
            $script:publishMove = 0
            Mock Publish-ProductionWriterStartGuardFile {
                param($Source, $Destination)
                $script:publishMove++
                if ($script:publishMove -eq 2) { throw 'partial module publication failed' }
                Copy-Item -LiteralPath $Source -Destination $Destination -Force
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*partial module publication*'

            Get-Content (Join-Path $service 'Start-ChristopherBellDev.ps1') -Raw |
                Should -Match 'new guarded launcher'
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'consumes and rejects expired, wrong-release, and wrong-state authorizations' -ForEach @(
            @{ Kind='expired' }, @{ Kind='release' }, @{ Kind='state' }
        ) {
            $marker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY
            $path = Get-ProductionWriterStartAuthorizationPath -Config $script:config
            $authorization = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            if ($Kind -eq 'expired') { $authorization.expiresAtEpochMillis = 1 }
            if ($Kind -eq 'release') { $authorization.release = '4444444444444444444444444444444444444444' }
            if ($Kind -eq 'state') { $authorization.markerState = 'TARGET_CUTOVER_IN_PROGRESS' }
            $authorization | ConvertTo-Json | Set-Content -LiteralPath $path

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $marker -ReleaseIdentity $release } | Should -Throw '*blocked*'
            Test-Path -LiteralPath $path | Should -BeFalse
        }

        It 'rejects marker property and state casing variants' -ForEach @(
            @{ Json='{"Version":1,"state":"TARGET_ACTIVE","updatedAtEpochMillis":1,"targetRelease":"1111111111111111111111111111111111111111","legacyRelease":"2222222222222222222222222222222222222222"}' },
            @{ Json='{"version":1,"state":"target_active","updatedAtEpochMillis":1,"targetRelease":"1111111111111111111111111111111111111111","legacyRelease":"2222222222222222222222222222222222222222"}' }
        ) {
            $path = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
            $Json | Set-Content -LiteralPath $path

            { Read-ProductionMusicSchemaDirection -Config $script:config } |
                Should -Throw '*marker is invalid*'
        }

        It 'rejects uppercase marker SHA casing' {
            $path = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
            [ordered]@{
                version=1
                state='TARGET_ACTIVE'
                updatedAtEpochMillis=1
                targetRelease=('A' * 40)
                legacyRelease=('2' * 40)
            } | ConvertTo-Json | Set-Content -LiteralPath $path

            { Read-ProductionMusicSchemaDirection -Config $script:config } |
                Should -Throw '*marker is invalid*'
        }
    }

    It 'guards the actual WinSW boot and recovery launch script' {
        $serviceRoot = Join-Path $PSScriptRoot '..\service'
        $scriptText = Get-Content (Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1') -Raw
        $winsw = Get-Content (Join-Path $serviceRoot 'ChristopherBellDev.xml') -Raw

        $scriptText | Should -Match 'Assert-ProductionWriterStartAllowed -Config \$config'
        $scriptText | Should -Match 'Production\.WriterStart\.bundle\.json'
        $scriptText | Should -Match 'ChristopherBellDev\.exe'
        $scriptText | Should -Match 'ChristopherBellDev\.xml'
        $scriptText | Should -Match 'winSwSha256'
        $scriptText | Should -Match 'serviceXmlSha256'
        $scriptText | Should -Match 'Get-FileHash'
        $scriptText | Should -Match 'Assert-InstalledWriterStartGuardAcl'
        $scriptText | Should -Match 'Assert-InstalledWriterStartGuardNotReparse'
        $scriptText.IndexOf('Assert-InstalledWriterStartGuardNotReparse -Path $root') |
            Should -BeLessThan $scriptText.IndexOf('config\deploy.json')
        $scriptText.IndexOf('Assert-InstalledWriterStartGuardAcl -Path $serviceRoot') |
            Should -BeLessThan $scriptText.IndexOf('config\deploy.json')
        $scriptText.IndexOf('Assert-InstalledWriterStartGuardAcl -Path') |
            Should -BeLessThan $scriptText.IndexOf('Import-Module')
        $scriptText.IndexOf('Get-FileHash') |
            Should -BeLessThan $scriptText.IndexOf('Import-Module')
        $scriptText.IndexOf('Assert-ProductionWriterStartAllowed') |
            Should -BeLessThan $scriptText.IndexOf('& $config.javaExe')
        $winsw | Should -Match 'Start-ChristopherBellDev\.ps1'
        $winsw | Should -Match '<onfailure action="restart"'
    }
}

Describe 'production writer-start real Windows ACL boundary' {
    It 'protects the disposable service directory and complete installed service boundary' `
            -Skip:($env:CBELL_RUN_WRITER_START_ACL_TESTS -ne '1') {
        InModuleScope Production.WriterStart {
            $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
            $principal = [Security.Principal.WindowsPrincipal]$identity
            if (-not $principal.IsInRole(
                    [Security.Principal.WindowsBuiltInRole]::Administrator)) {
                throw 'Real writer-start ACL integration requires elevated PowerShell.'
            }
            $root = Join-Path ([IO.Path]::GetTempPath()) (
                'cbell-writer-start-acl-' + [guid]::NewGuid().ToString('N'))
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $winSw = Join-Path $service 'ChristopherBellDev.exe'
            $serviceXml = Join-Path $service 'ChristopherBellDev.xml'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            'winsw' | Set-Content $winSw
            'service xml' | Set-Content $serviceXml
            try {
                $config = [pscustomobject]@{ programDataRoot=$root }
                $winSwSha = (Get-FileHash $winSw -Algorithm SHA256).Hash.ToLowerInvariant()
                $serviceXmlSha = (Get-FileHash $serviceXml -Algorithm SHA256).Hash.ToLowerInvariant()
                $result = Publish-ProductionWriterStartGuardBundle `
                    -Config $config `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module `
                    -ExpectedWinSwSha256 $winSwSha `
                    -ExpectedServiceXmlSha256 $serviceXmlSha
                Assert-ProductionWriterStartGuardBundle `
                    -Config $config `
                    -ExpectedLauncherSha256 $result.launcherSha256 `
                    -ExpectedModuleSha256 $result.moduleSha256 `
                    -ExpectedWinSwSha256 $winSwSha `
                    -ExpectedServiceXmlSha256 $serviceXmlSha | Out-Null
                Assert-ProductionWriterStartServiceDirectory -Path $service
                foreach ($name in @(
                    'ChristopherBellDev.exe',
                    'ChristopherBellDev.xml',
                    'Start-ChristopherBellDev.ps1',
                    'Production.WriterStart.psm1',
                    'Production.WriterStart.bundle.json')) {
                    Assert-ProtectedProductionPath -Path (Join-Path $service $name)
                }
            } finally {
                if (Test-Path -LiteralPath $root) {
                    Remove-Item -LiteralPath $root -Recurse -Force
                }
            }
        }
    }
}
