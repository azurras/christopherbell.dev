$moduleRoot = Join-Path $PSScriptRoot '..\modules'
Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
Import-Module (Join-Path $moduleRoot 'Production.MusicRuntime.psm1') -Force

Describe 'Music runtime rollback operation' {
    InModuleScope Production.MusicRuntime {
        BeforeAll {
            function New-VerifiedTestBackup {
                param(
                    [string]$Name = 'verified.archive.gz',
                    [datetime]$CreatedAt = (Get-Date).ToUniversalTime()
                )

                $archive = Join-Path $TestDrive $Name
                [IO.File]::WriteAllBytes($archive, [byte[]](1, 2, 3, 4))
                [ordered]@{
                    archive = $archive
                    sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
                    createdAt = $CreatedAt.ToString('o')
                } | ConvertTo-Json | Set-Content -LiteralPath "$archive.sha256.json" -Encoding utf8
                return $archive
            }
        }

        It 'generates a fixed reverse-copy script without destructive collection operations' {
            $script = Get-ProductionMusicRuntimeRollbackScript

            $script | Should -Match "getSiblingDB\('christopherbell'\)"
            $script | Should -Match "getCollection\('music_runtime_state'\)"
            $script | Should -Match "getCollection\('music_queue_state'\)"
            $script | Should -Match "getCollection\('music_radio_state'\)"
            ([regex]::Matches($script, '\.replaceOne\s*\(')).Count | Should -Be 2
            $script | Should -Not -Match '\.(drop|dropDatabase|deleteOne|deleteMany|remove|renameCollection)\s*\('
            $script | Should -Not -Match 'runCommand\s*\(\s*\{\s*(drop|renameCollection)'
            $script | Should -Not -Match 'getCollectionNames|listCollections|\$out|\$merge'
        }

        It 'returns a no-write exact preview without reading protected configuration' {
            Mock Read-ProductionConfig { throw 'preview must not read protected config' }
            Mock Get-Service { throw 'preview must not inspect services' }
            Mock New-ProductionBackup { throw 'preview must not create a backup' }
            Mock Invoke-CheckedProcess { throw 'preview must not invoke mongosh' }

            $preview = Invoke-ProductionMusicRuntimeStateRollback -WhatIf

            @($preview.PSObject.Properties.Name) | Should -Be @(
                'database','destination','sources','mutates',
                'requiresStoppedWriter','requiresFreshVerifiedBackup')
            $preview.database | Should -Be 'christopherbell'
            $preview.destination | Should -Be 'music_runtime_state'
            $preview.sources | Should -Be @('music_queue_state','music_radio_state')
            $preview.mutates | Should -BeFalse
            $preview.requiresStoppedWriter | Should -BeTrue
            $preview.requiresFreshVerifiedBackup | Should -BeTrue
        }

        It 'requires explicit confirmation before reading configuration' {
            Mock Read-ProductionConfig { throw 'must not read configuration' }

            { Invoke-ProductionMusicRuntimeStateRollback } |
                Should -Throw '*explicit confirmation*'
            Should -Invoke Read-ProductionConfig -Times 0
        }

        It 'requires the website writer to be stopped before backup or mutation' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Running' } }
            Mock New-ProductionBackup { throw 'must not run' }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*must be stopped*'
            Should -Invoke New-ProductionBackup -Times 0
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'rejects a missing backup checksum sidecar before mutation' {
            $archive = Join-Path $TestDrive 'missing-sidecar.archive.gz'
            [IO.File]::WriteAllBytes($archive, [byte[]](1, 2, 3, 4))
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*backup verification failed*'
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'rejects a checksum mismatch before mutation' {
            $archive = New-VerifiedTestBackup -Name 'checksum-mismatch.archive.gz'
            $sidecarPath = "$archive.sha256.json"
            $sidecar = Get-Content -LiteralPath $sidecarPath -Raw | ConvertFrom-Json
            $sidecar.sha256 = 'A' * 64
            $sidecar | ConvertTo-Json | Set-Content -LiteralPath $sidecarPath -Encoding utf8
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*backup verification failed*'
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'rejects a backup not created for the current operation before mutation' {
            $archive = New-VerifiedTestBackup `
                -Name 'stale.archive.gz' `
                -CreatedAt ((Get-Date).ToUniversalTime().AddMinutes(-10))
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*backup verification failed*'
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'rechecks the stopped writer after backup verification' {
            $archive = New-VerifiedTestBackup -Name 'writer-restarted.archive.gz'
            $script:serviceCheck = 0
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Get-Service {
                $script:serviceCheck++
                if ($script:serviceCheck -eq 1) {
                    return [pscustomobject]@{ Status = 'Stopped' }
                }
                return [pscustomobject]@{ Status = 'Running' }
            }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*must remain stopped*'
            Should -Invoke Invoke-CheckedProcess -Times 0
        }

        It 'backs up and verifies before fixed-loopback mongosh then returns bounded metadata' {
            $archive = New-VerifiedTestBackup -Name 'success.archive.gz'
            $script:events = [Collections.Generic.List[string]]::new()
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup {
                [void]$script:events.Add('backup')
                $archive
            }
            Mock Get-FileHash {
                [void]$script:events.Add('checksum')
                $stream = [IO.File]::OpenRead($LiteralPath)
                $algorithmProvider = [Security.Cryptography.SHA256]::Create()
                try {
                    $bytes = $algorithmProvider.ComputeHash($stream)
                } finally {
                    $algorithmProvider.Dispose()
                    $stream.Dispose()
                }
                [pscustomobject]@{
                    Hash = [BitConverter]::ToString($bytes).Replace('-', '')
                }
            }
            Mock Invoke-CheckedProcess {
                [void]$script:events.Add('mongosh')
                '{"complete":true,"database":"christopherbell","destinationCount":2,"restoredCollections":["music_queue_state","music_radio_state"]}'
            }

            $result = Invoke-ProductionMusicRuntimeStateRollback -Confirm

            $script:events | Should -Be @('backup','checksum','mongosh')
            @($result.PSObject.Properties.Name) | Should -Be @(
                'complete','database','destinationCount','restoredCollections','backup')
            $result.complete | Should -BeTrue
            $result.database | Should -Be 'christopherbell'
            $result.destinationCount | Should -Be 2
            $result.restoredCollections | Should -Be @(
                'music_queue_state','music_radio_state')
            $result.backup | Should -Be $archive
            Should -Invoke Get-Service -Times 2 -Exactly -ParameterFilter {
                $Name -eq 'ChristopherBellDev' -and $ErrorAction -eq 'Stop'
            }
            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $FilePath -eq 'C:\tools\mongosh.exe' -and
                $WorkingDirectory -eq 'C:\repo' -and
                $ArgumentList.Count -eq 5 -and
                $ArgumentList[0] -eq '--quiet' -and
                $ArgumentList[1] -eq '--norc' -and
                $ArgumentList[2] -eq 'mongodb://127.0.0.1:27017/admin' -and
                $ArgumentList[3] -eq '--eval'
            }
        }

        It 'rejects malformed command metadata without echoing document content' {
            $sensitive = 'private-track-token'
            $json = '{"complete":true,"database":"christopherbell",' +
                '"destinationCount":2,"restoredCollections":[' +
                '"music_queue_state","music_radio_state"],"document":"' + $sensitive + '"}'

            $failure = $null
            try {
                ConvertFrom-ProductionMusicRuntimeRollback -Json $json
            } catch {
                $failure = $_.Exception
            }

            $failure.Message | Should -Be 'Music runtime rollback returned invalid metadata.'
            $failure.Message | Should -Not -Match $sensitive
        }

        It 'rejects malformed metadata types and collection order' -TestCases @(
            @{ Json = '{"complete":"true","database":"christopherbell","destinationCount":2,"restoredCollections":["music_queue_state","music_radio_state"]}' }
            @{ Json = '{"complete":true,"database":"other","destinationCount":2,"restoredCollections":["music_queue_state","music_radio_state"]}' }
            @{ Json = '{"complete":true,"database":"christopherbell","destinationCount":"2","restoredCollections":["music_queue_state","music_radio_state"]}' }
            @{ Json = '{"complete":true,"database":"christopherbell","destinationCount":2,"restoredCollections":["music_radio_state","music_queue_state"]}' }
        ) {
            param($Json)

            { ConvertFrom-ProductionMusicRuntimeRollback -Json $Json } |
                Should -Throw 'Music runtime rollback returned invalid metadata.'
        }
    }
}

Describe 'Music runtime rollback disposable MongoDB boundary' -Skip:(
    [string]::IsNullOrWhiteSpace($env:MUSIC_RUNTIME_ROLLBACK_TEST_URI)) {
    BeforeAll {
        $moduleRoot = Join-Path $PSScriptRoot '..\modules'
        $mongoShell = if ([string]::IsNullOrWhiteSpace($env:MONGOSH_EXE)) {
            (Get-Command mongosh.exe -ErrorAction Stop).Source
        } else {
            $env:MONGOSH_EXE
        }

        function Invoke-DisposableMusicRuntimeMongo {
            param([Parameter(Mandatory)][string]$Script)

            $output = & $mongoShell --quiet --norc $env:MUSIC_RUNTIME_ROLLBACK_TEST_URI `
                --eval $Script 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw 'Disposable MongoDB script failed.'
            }
            return ,@($output)
        }

        function Reset-DisposableMusicRuntimeState {
            $script = @'
const target = db.getSiblingDB('christopherbell');
target.getCollection('music_runtime_state').deleteMany({});
target.getCollection('music_queue_state').deleteMany({});
target.getCollection('music_radio_state').deleteMany({});
'@
            $null = Invoke-DisposableMusicRuntimeMongo -Script $script
        }

        function Set-DisposableValidMusicRuntimeState {
            param([string]$LegacyQueueSuffix = '')

            $script = @"
const target = db.getSiblingDB('christopherbell');
target.getCollection('music_runtime_state').insertMany([
  {_id:'queue',kind:'QUEUE',queue:{entries:[{id:'entry-1',trackId:'track-new',observedToken:'token-new',enqueuedByAccountId:'account-1',enqueuedAt:new Date('2026-08-09T12:00:00Z')}] }},
  {_id:'radio',kind:'RADIO',radio:{stationSequence:NumberLong('7'),trackId:'track-radio-new',observedToken:'radio-token-new',startedAt:new Date('2026-08-09T12:01:00Z'),durationSeconds:180.5,source:'QUEUE',queueEntryId:'entry-1'},version:NumberLong('9')}
]);
target.getCollection('music_queue_state').insertOne({_id:'global',entries:[],_class:'dev.christopherbell.music.radio.MusicQueueState'$LegacyQueueSuffix});
target.getCollection('music_radio_state').insertOne({_id:'global',stationSequence:NumberLong('1'),trackId:'track-old',observedToken:'token-old',startedAt:new Date('2026-08-09T11:00:00Z'),durationSeconds:60.5,source:'RADIO'});
"@
            $null = Invoke-DisposableMusicRuntimeMongo -Script $script
        }
    }

    BeforeEach {
        Reset-DisposableMusicRuntimeState
    }

    It 'reverse-copies exact valid state and proves lossless optional-field preservation' {
        Set-DisposableValidMusicRuntimeState

        $output = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript)
        $metadata = $output[-1] | ConvertFrom-Json -ErrorAction Stop
        $metadata.complete | Should -BeTrue
        $metadata.restoredCollections | Should -Be @(
            'music_queue_state','music_radio_state')

        $readback = Invoke-DisposableMusicRuntimeMongo -Script @'
const target = db.getSiblingDB('christopherbell');
const queue = target.getCollection('music_queue_state').findOne({_id:'global'});
const radio = target.getCollection('music_radio_state').findOne({_id:'global'});
print(JSON.stringify({
  queueTrackCopied: queue.entries[0].trackId === 'track-new',
  queueVersionAbsent: !Object.prototype.hasOwnProperty.call(queue, 'version'),
  queueClassPreserved: queue._class === 'dev.christopherbell.music.radio.MusicQueueState',
  radioTrackCopied: radio.trackId === 'track-radio-new',
  radioVersionPreserved: radio.version.toString() === '9',
  radioClassAbsent: !Object.prototype.hasOwnProperty.call(radio, '_class'),
  queueEntryIdCopied: radio.queueEntryId === 'entry-1'
}));
'@
        $proof = $readback[-1] | ConvertFrom-Json -ErrorAction Stop
        @($proof.PSObject.Properties.Value | Where-Object { $_ -ne $true }) |
            Should -BeNullOrEmpty
    }

    It 'rejects malformed target shape before changing either legacy singleton' {
        Set-DisposableValidMusicRuntimeState
        $null = Invoke-DisposableMusicRuntimeMongo -Script @'
db.getSiblingDB('christopherbell').music_runtime_state.updateOne(
  {_id:'queue'}, {$set:{unexpected:'private-value'}});
'@

        { Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript) } |
            Should -Throw 'Disposable MongoDB script failed.'

        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
const target = db.getSiblingDB('christopherbell');
print(JSON.stringify({
  queueUnchanged: target.music_queue_state.findOne({_id:'global'}).entries.length === 0,
  radioUnchanged: target.music_radio_state.findOne({_id:'global'}).trackId === 'track-old'
}));
'@
        ($proof[-1] | ConvertFrom-Json).queueUnchanged | Should -BeTrue
        ($proof[-1] | ConvertFrom-Json).radioUnchanged | Should -BeTrue
    }

    It 'rejects conflicting retained legacy shape before changing either singleton' {
        Set-DisposableValidMusicRuntimeState -LegacyQueueSuffix ",unexpected:'private-value'"

        { Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript) } |
            Should -Throw 'Disposable MongoDB script failed.'

        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
const target = db.getSiblingDB('christopherbell');
print(JSON.stringify({
  queueUnchanged: target.music_queue_state.findOne({_id:'global'}).entries.length === 0,
  radioUnchanged: target.music_radio_state.findOne({_id:'global'}).trackId === 'track-old'
}));
'@
        ($proof[-1] | ConvertFrom-Json).queueUnchanged | Should -BeTrue
        ($proof[-1] | ConvertFrom-Json).radioUnchanged | Should -BeTrue
    }
}
