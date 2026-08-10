Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Force

Describe 'production writer-start schema boundary' {
    InModuleScope Production.WriterStart {
        BeforeEach {
            $script:config = [pscustomobject]@{ programDataRoot=$TestDrive; mongoShellExe='mongosh.exe' }
            Mock Protect-ProductionPath {}
            Mock Assert-ProtectedProductionPath { $true }
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
        $scriptText.IndexOf('Assert-ProductionWriterStartAllowed') |
            Should -BeLessThan $scriptText.IndexOf('& $config.javaExe')
        $winsw | Should -Match 'Start-ChristopherBellDev\.ps1'
        $winsw | Should -Match '<onfailure action="restart"'
    }
}
