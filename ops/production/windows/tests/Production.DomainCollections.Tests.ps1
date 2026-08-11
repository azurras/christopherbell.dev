Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$moduleRoot = Join-Path $PSScriptRoot '..\modules'
Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Force
Import-Module (Join-Path $moduleRoot 'Production.Deploy.psm1') -Force
Import-Module (Join-Path $moduleRoot 'Production.DomainCollections.psm1') -Force

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
            'cbell_candidate_afafafafafaf_afafafafafafafafafafafaf',
            'cbell_candidate_b0b0b0b0b0b0_b0b0b0b0b0b0b0b0b0b0b0b0',
            'cbell_candidate_b1b1b1b1b1b1_b1b1b1b1b1b1b1b1b1b1b1b1',
            'cbell_candidate_b2b2b2b2b2b2_b2b2b2b2b2b2b2b2b2b2b2b2',
            'cbell_candidate_b3b3b3b3b3b3_b3b3b3b3b3b3b3b3b3b3b3b3'
        )
        try {
            $output = @(& $shell '--quiet' '--norc' $uri '--file' $manifest `
                '--file' $engine '--file' $matrix 2>&1)
            $LASTEXITCODE | Should -Be 0 -Because ($output -join "`n")
            $result = $output[-1] | ConvertFrom-Json -ErrorAction Stop
            $result.complete | Should -BeTrue
            $result.kinds | Should -Be 52
            $result.indexes | Should -Be 126
            $result.collections | Should -Be 14
            $result.faultBoundaries | Should -Be 468

            $cliDatabase = 'cbell_candidate_bbbbbbbbbbbb_bbbbbbbbbbbbbbbbbbbbbbbb'
            $mongoTools = [string]$env:DOMAIN_COLLECTION_MIGRATION_TEST_MONGO_TOOLS
            if ([string]::IsNullOrWhiteSpace($mongoTools)) {
                throw 'Disposable Mongo tools path is required for restore verification.'
            }
            $mongoToolsUri = $uri -replace '/admin$',''
            $archive = Join-Path $TestDrive 'legacy-domain-backup.archive.gz'
            & (Join-Path $mongoTools 'mongodump.exe') `
                '--quiet' `
                "--uri=$mongoToolsUri" `
                "--db=$cliDatabase" `
                "--archive=$archive" `
                '--gzip'
            $LASTEXITCODE | Should -Be 0
            (Get-Item -LiteralPath $archive).Length | Should -BeGreaterThan 0
            & (Join-Path $mongoTools 'mongorestore.exe') `
                '--quiet' `
                "--uri=$mongoToolsUri" `
                "--archive=$archive" `
                '--gzip' `
                '--dryRun'
            $LASTEXITCODE | Should -Be 0

            $domainModule = Get-Module Production.DomainCollections
            $config = [pscustomobject]@{
                mongoShellExe = $shell
                repositoryPath = [IO.Path]::GetFullPath(
                    (Join-Path $PSScriptRoot '..\..\..\..'))
            }
            $ownerToken = 'b' * 32
            $release = 'a' * 40
            $backupIdentity = ((Get-FileHash `
                -LiteralPath $archive -Algorithm SHA256).Hash).ToLowerInvariant()
            $invokeEngine = {
                param([string]$Action,$Evidence,[string]$EvidenceDigest)
                & $domainModule {
                    param($Config,$Database,$Action,$OwnerToken,$Release,
                        $BackupIdentity,$Evidence,$EvidenceDigest,$MongoUri)
                    Invoke-ProductionDomainCollectionEngine `
                        -Config $Config `
                        -Database $Database `
                        -Action $Action `
                        -OwnerToken $OwnerToken `
                        -Release $Release `
                        -BackupIdentity $BackupIdentity `
                        -Evidence $Evidence `
                        -EvidenceDigest $EvidenceDigest `
                        -MongoUri $MongoUri
                } $config $cliDatabase $Action $ownerToken $release `
                    $backupIdentity $Evidence $EvidenceDigest $uri
            }
            $wrapperPreview = & $invokeEngine 'preview' $null ('0' * 64)
            $wrapperPreview.complete | Should -BeTrue
            $wrapperPreview.evidence.collections.Count | Should -BeGreaterThan 0
            $collectionMetric = $wrapperPreview.evidence.collections[0]
            @($collectionMetric.PSObject.Properties.Name | Sort-Object) `
                | Should -Be @('checksum','count','indexDigest','name')
            ($collectionMetric.count -is [int] -or $collectionMetric.count -is [long]) |
                Should -BeTrue
            ($wrapperPreview.evidence.kinds[0].count -is [int] -or
                $wrapperPreview.evidence.kinds[0].count -is [long]) | Should -BeTrue

            $cliArguments = @(
                $cliDatabase,
                'preview',
                '576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24',
                $ownerToken,
                $release,
                $backupIdentity,
                ('0' * 64)) | ConvertTo-Json -Compress
            $bootstrap = "globalThis.DOMAIN_COLLECTION_ARGS=$cliArguments;void 0;"
            $cliOutput = @(& $shell '--quiet' '--norc' $uri `
                '--eval' $bootstrap '--file' $manifest '--file' $engine 2>&1)
            $LASTEXITCODE | Should -Be 0
            $cliResult = ($cliOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop
            $cliResult.database | Should -BeExactly $cliDatabase
            $cliResult.action | Should -BeExactly 'preview'
            $cliResult.complete | Should -BeTrue

            $evidence = $wrapperPreview.evidence
            $evidenceDigest = [string]$wrapperPreview.evidenceDigest
            for ($step = 0; $step -lt 200; $step++) {
                $stage = & $invokeEngine 'stage' $evidence $evidenceDigest
                if ($stage.complete) { break }
            }
            $stage.complete | Should -BeTrue
            (& $invokeEngine 'verify-stage' $evidence $evidenceDigest).complete |
                Should -BeTrue
            for ($step = 0; $step -lt 100; $step++) {
                $publish = & $invokeEngine 'publish-next' $evidence $evidenceDigest
                if ($publish.complete) { break }
            }
            $publish.complete | Should -BeTrue
            (& $invokeEngine 'verify-live' $evidence $evidenceDigest).complete |
                Should -BeTrue
            & $shell '--quiet' '--norc' $uri '--eval' `
                "const r=db.getSiblingDB('$cliDatabase').accounts.updateOne({_kind:'account'},{`$set:{'payload.__task7DelayedWrite':true}});quit(r.matchedCount===1?0:1)" `
                2>$null | Out-Null
            $LASTEXITCODE | Should -Be 0
            { & $invokeEngine 'verify-live' $evidence $evidenceDigest } |
                Should -Throw
            & $shell '--quiet' '--norc' $uri '--eval' `
                "const r=db.getSiblingDB('$cliDatabase').accounts.updateOne({_kind:'account'},{`$unset:{'payload.__task7DelayedWrite':''}});quit(r.matchedCount===1?0:1)" `
                2>$null | Out-Null
            $LASTEXITCODE | Should -Be 0
            (& $invokeEngine 'verify-live' $evidence $evidenceDigest).complete |
                Should -BeTrue
            for ($step = 0; $step -lt 100; $step++) {
                $drop = & $invokeEngine 'drop-legacy' $evidence $evidenceDigest
                if ($drop.complete) { break }
            }
            $drop.complete | Should -BeTrue

            for ($step = 0; $step -lt 100; $step++) {
                $prepare = & $invokeEngine 'prepare-restore' $evidence $evidenceDigest
                if ($prepare.complete) { break }
            }
            $prepare.complete | Should -BeTrue
            $catalogOutput = & $shell '--quiet' '--norc' $uri '--eval' `
                "print(db.getSiblingDB('$cliDatabase').getCollectionInfos().length)"
            $LASTEXITCODE | Should -Be 0
            [int]($catalogOutput | Select-Object -Last 1) | Should -Be 0

            & (Join-Path $mongoTools 'mongorestore.exe') `
                '--quiet' `
                "--uri=$mongoToolsUri" `
                "--archive=$archive" `
                '--gzip' `
                "--nsInclude=$cliDatabase.accounts"
            $LASTEXITCODE | Should -Be 0
            for ($step = 0; $step -lt 10; $step++) {
                $resumePrepare = & $invokeEngine `
                    'prepare-restore' $evidence $evidenceDigest
                if ($resumePrepare.complete) { break }
            }
            $resumePrepare.complete | Should -BeTrue

            & (Join-Path $mongoTools 'mongorestore.exe') `
                '--quiet' `
                "--uri=$mongoToolsUri" `
                "--archive=$archive" `
                '--gzip'
            $LASTEXITCODE | Should -Be 0
            $restored = & $invokeEngine 'restore-verify' $evidence $evidenceDigest
            $restored.complete | Should -BeTrue
            $restored.state | Should -BeExactly 'LEGACY_RESTORE_VERIFIED'
            & $shell '--quiet' '--norc' $uri '--eval' `
                "db.getSiblingDB('$cliDatabase').accounts.insertOne({_id:'post-rollback-write',marker:'post-rollback'})" `
                2>$null | Out-Null
            $LASTEXITCODE | Should -Be 0
            { & $invokeEngine 'restore-verify' $evidence $evidenceDigest } |
                Should -Throw
            $countAfterRejectedReplay = & $shell '--quiet' '--norc' $uri '--eval' `
                "print(db.getSiblingDB('$cliDatabase').accounts.countDocuments({_id:'post-rollback-write'}))"
            [int]($countAfterRejectedReplay | Select-Object -Last 1) | Should -Be 1
        } finally {
            $quoted = ($databaseNames | ForEach-Object { "'$_'" }) -join ','
            & $shell '--quiet' '--norc' $uri '--eval' `
                "[$quoted].forEach((name) => db.getSiblingDB(name).dropDatabase())" `
                2>$null | Out-Null
        }
    }
}
