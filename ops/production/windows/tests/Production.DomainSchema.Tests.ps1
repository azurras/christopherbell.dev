Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Force

Describe 'domain schema direction compatibility boundary' {
    InModuleScope Production.WriterStart {
        It 'reads a protected exact domain marker without breaking the Music projection' {
            $root = Join-Path $TestDrive 'marker'
            $state = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $state -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            [ordered]@{
                version = 2
                state = 'TARGET_ACTIVE'
                updatedAtEpochMillis = 1
                targetRelease = 'a' * 40
                legacyRelease = 'b' * 40
                manifestDigest = '576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24'
                evidenceDigest = 'c' * 64
                backupIdentity = 'd' * 64
                legacyDropped = $true
            } | ConvertTo-Json | Set-Content `
                (Join-Path $state 'music-runtime-schema-direction.json')

            $domain = Read-ProductionDomainSchemaDirection -Config $config
            $music = Read-ProductionMusicSchemaDirection -Config $config

            $domain.version | Should -Be 2
            $domain.legacyDropped | Should -BeTrue
            $music.version | Should -Be 2
            $music.targetRelease | Should -BeExactly ('a' * 40)
        }

        It 'continues to read the legacy Music v1 marker and reports no domain marker' {
            $root = Join-Path $TestDrive 'music-v1'
            $state = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $state -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            [ordered]@{
                version = 1
                state = 'TARGET_ACTIVE'
                updatedAtEpochMillis = 1
                targetRelease = 'a' * 40
                legacyRelease = 'b' * 40
            } | ConvertTo-Json | Set-Content `
                (Join-Path $state 'music-runtime-schema-direction.json')

            (Read-ProductionMusicSchemaDirection -Config $config).version | Should -Be 1
            Read-ProductionDomainSchemaDirection -Config $config | Should -BeNullOrEmpty
        }

        It 'rejects extra marker fields fail closed' {
            $root = Join-Path $TestDrive 'invalid'
            $state = Join-Path $root 'state'
            New-Item -ItemType Directory -Path $state -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot = $root }
            '{"version":2,"state":"TARGET_ACTIVE","unexpected":true}' |
                Set-Content (Join-Path $state 'music-runtime-schema-direction.json')

            { Read-ProductionDomainSchemaDirection -Config $config } |
                Should -Throw '*domain schema-direction marker is invalid*'
        }
    }
}
