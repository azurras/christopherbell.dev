Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$moduleRoot = Join-Path $PSScriptRoot '..\modules'
Import-Module (Join-Path $moduleRoot 'Production.Deploy.psm1') -Force

Describe 'Domain collection migration artifact contracts' {
    It 'passes the executable raw JavaScript contract suite' {
        $node = if ($env:NODE_EXE) { $env:NODE_EXE } else { 'node.exe' }
        $test = Join-Path $PSScriptRoot 'domain-collection-migration.test.js'
        & $node '--test' $test
        $LASTEXITCODE | Should -Be 0
    }

    InModuleScope Production.Deploy {
        It 'marks only a V015 source release as TARGET domain schema' {
            $legacy = Join-Path $TestDrive 'legacy'
            $target = Join-Path $TestDrive 'target'
            New-Item -ItemType Directory -Force $legacy,$target | Out-Null
            $relative = 'website\src\main\java\dev\christopherbell\configuration\mongo\migration'
            New-Item -ItemType Directory -Force (Join-Path $target $relative) | Out-Null
            New-Item -ItemType File -Force (Join-Path (Join-Path $target $relative) `
                'V015RequireDomainCollectionSchema.java') | Out-Null

            (Get-ProductionReleaseDomainSchema -SourceTree $legacy) | Should -Be 'LEGACY'
            (Get-ProductionReleaseDomainSchema -SourceTree $target) | Should -Be 'TARGET'
        }
    }

    It 'proves forward interruption reverse restore and exact deletion on disposable Mongo' `
            -Skip:([string]::IsNullOrWhiteSpace($env:DOMAIN_COLLECTION_MIGRATION_TEST_URI)) {
        $uri = [string]$env:DOMAIN_COLLECTION_MIGRATION_TEST_URI
        if ($uri -notmatch '^mongodb://127\.0\.0\.1:(?!27017)[0-9]{4,5}/admin$') {
            throw 'Disposable Mongo URI is not an approved loopback test boundary.'
        }
        $shell = (Get-Command mongosh.exe -ErrorAction Stop).Source
        $manifest = Join-Path $PSScriptRoot '..\scripts\DomainCollectionManifest.js'
        $engine = Join-Path $PSScriptRoot '..\scripts\Invoke-DomainCollectionMigration.js'
        $matrix = Join-Path $PSScriptRoot 'domain-collection-migration.mongo.js'
        $databaseNames = @(
            'cbell_candidate_aaaaaaaaaaaa_aaaaaaaaaaaaaaaaaaaaaaaa',
            'cbell_candidate_bbbbbbbbbbbb_bbbbbbbbbbbbbbbbbbbbbbbb',
            'cbell_candidate_cccccccccccc_cccccccccccccccccccccccc',
            'cbell_candidate_dddddddddddd_dddddddddddddddddddddddd',
            'cbell_candidate_eeeeeeeeeeee_eeeeeeeeeeeeeeeeeeeeeeee',
            'cbell_candidate_ffffffffffff_ffffffffffffffffffffffff',
            'cbell_candidate_111111111111_111111111111111111111111',
            'cbell_candidate_222222222222_222222222222222222222222',
            'cbell_candidate_333333333333_333333333333333333333333',
            'cbell_candidate_444444444444_444444444444444444444444',
            'cbell_candidate_555555555555_555555555555555555555555',
            'cbell_candidate_666666666666_666666666666666666666666',
            'cbell_candidate_777777777777_777777777777777777777777',
            'cbell_candidate_888888888888_888888888888888888888888',
            'cbell_candidate_999999999999_999999999999999999999999',
            'cbell_candidate_abababababab_abababababababababababab',
            'cbell_candidate_acacacacacac_acacacacacacacacacacacac',
            'cbell_candidate_adadadadadad_adadadadadadadadadadadad',
            'cbell_candidate_aeaeaeaeaeae_aeaeaeaeaeaeaeaeaeaeaeae',
            'cbell_candidate_afafafafafaf_afafafafafafafafafafafaf'
        )
        try {
            $output = @(& $shell '--quiet' '--norc' $uri '--file' $manifest `
                '--file' $engine '--file' $matrix 2>&1)
            $LASTEXITCODE | Should -Be 0
            $result = $output[-1] | ConvertFrom-Json -ErrorAction Stop
            $result.complete | Should -BeTrue
            $result.kinds | Should -Be 52
            $result.indexes | Should -Be 126
            $result.collections | Should -Be 14
            $result.faultBoundaries | Should -Be 468

            $cliDatabase = 'cbell_candidate_bbbbbbbbbbbb_bbbbbbbbbbbbbbbbbbbbbbbb'
            $cliArguments = @(
                $cliDatabase,
                'preview',
                '576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24',
                ('b' * 32),
                ('a' * 40),
                ('b' * 64),
                ('0' * 64)) | ConvertTo-Json -Compress
            $bootstrap = "globalThis.DOMAIN_COLLECTION_ARGS=$cliArguments;void 0;"
            $cliOutput = @(& $shell '--quiet' '--norc' $uri `
                '--eval' $bootstrap '--file' $manifest '--file' $engine 2>&1)
            $LASTEXITCODE | Should -Be 0
            $cliResult = ($cliOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop
            $cliResult.database | Should -BeExactly $cliDatabase
            $cliResult.action | Should -BeExactly 'preview'
            $cliResult.complete | Should -BeTrue
        } finally {
            $quoted = ($databaseNames | ForEach-Object { "'$_'" }) -join ','
            & $shell '--quiet' '--norc' $uri '--eval' `
                "[$quoted].forEach((name) => db.getSiblingDB(name).dropDatabase())" `
                2>$null | Out-Null
        }
    }
}
