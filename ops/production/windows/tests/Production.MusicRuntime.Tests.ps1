$moduleRoot = Join-Path $PSScriptRoot '..\modules'
Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
Import-Module (Join-Path $moduleRoot 'Production.MusicRuntime.psm1') -Force

BeforeAll {
function Assert-DisposableMusicRuntimeMongoUri {
    param(
        [Parameter(Mandatory)][string]$UriText,
        [Parameter(Mandatory)][string]$MarkerPath
    )

    try {
        $match = [regex]::Match(
            $UriText,
            '^mongodb://(127\.0\.0\.1|localhost):([0-9]{1,5})/admin$',
            [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        $uri = $null
        if (-not $match.Success -or
            -not [uri]::TryCreate($UriText, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -cne 'mongodb' -or
            -not [string]::IsNullOrEmpty($uri.UserInfo) -or
            $uri.Host -notin @('127.0.0.1','localhost') -or
            $uri.AbsolutePath -cne '/admin' -or
            -not [string]::IsNullOrEmpty($uri.Query) -or
            -not [string]::IsNullOrEmpty($uri.Fragment)) {
            throw 'URI shape is unsafe.'
        }
        $port = 0
        if (-not [int]::TryParse($match.Groups[2].Value, [ref]$port) -or
            $port -lt 1 -or $port -gt 65535 -or $port -eq 27017 -or
            $uri.Port -ne $port) {
            throw 'URI port is unsafe.'
        }
        if ([string]::IsNullOrWhiteSpace($MarkerPath) -or
            -not (Test-Path -LiteralPath $MarkerPath -PathType Leaf)) {
            throw 'Disposable ownership marker is missing.'
        }
        $marker = Get-Content -LiteralPath $MarkerPath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $names = @($marker.PSObject.Properties.Name)
        if ($names.Count -ne 4 -or
            @('uri','port','processId','dataPath').Where({ $_ -notin $names }).Count -ne 0 -or
            $marker.uri -isnot [string] -or [string]$marker.uri -cne $UriText -or
            ($marker.port -isnot [int] -and $marker.port -isnot [long]) -or
            [int]$marker.port -ne $port -or
            ($marker.processId -isnot [int] -and $marker.processId -isnot [long]) -or
            [long]$marker.processId -lt 1 -or
            $marker.dataPath -isnot [string] -or
            -not (Test-Path -LiteralPath $marker.dataPath -PathType Container)) {
            throw 'Disposable ownership marker is invalid.'
        }
        $dataPath = [IO.Path]::GetFullPath([string]$marker.dataPath)
        $markerParent = [IO.Path]::GetFullPath((Split-Path -Parent $MarkerPath))
        if (-not $markerParent.Equals($dataPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Disposable ownership marker is outside its data path.'
        }
        $process = Get-Process -Id ([int]$marker.processId) -ErrorAction Stop
        if ([string]$process.ProcessName -cne 'mongod') {
            throw 'Disposable ownership process is invalid.'
        }
        $listeners = @(Get-NetTCPConnection `
            -LocalPort $port `
            -State Listen `
            -ErrorAction Stop)
        if ($listeners.Count -lt 1 -or
            @($listeners | Where-Object {
                [int]$_.OwningProcess -ne [int]$marker.processId -or
                [string]$_.LocalAddress -notin @('127.0.0.1','::1')
            }).Count -ne 0) {
            throw 'Disposable ownership listener is invalid.'
        }
        return [pscustomobject][ordered]@{
            uri = $UriText
            port = $port
            processId = [int]$marker.processId
            dataPath = $dataPath
        }
    } catch {
        throw 'Disposable MongoDB URI is unsafe.'
    }
}

function Invoke-ValidatedDisposableMusicRuntimeAction {
    param(
        [Parameter(Mandatory)][string]$UriText,
        [Parameter(Mandatory)][string]$MarkerPath,
        [Parameter(Mandatory)][scriptblock]$Action
    )

    $context = Assert-DisposableMusicRuntimeMongoUri `
        -UriText $UriText `
        -MarkerPath $MarkerPath
    & $Action
    return $context
}
}

Describe 'Music runtime disposable MongoDB URI safety' {
    It 'rejects unsafe URI <Name> before invoking the Mongo action' -TestCases @(
        @{ Name = 'production port'; Uri = 'mongodb://127.0.0.1:27017/admin' }
        @{ Name = 'omitted port'; Uri = 'mongodb://127.0.0.1/admin' }
        @{ Name = 'malformed input'; Uri = 'not a mongodb uri' }
        @{ Name = 'remote host'; Uri = 'mongodb://db.example.test:27159/admin' }
        @{ Name = 'userinfo'; Uri = 'mongodb://operator:secret@127.0.0.1:27159/admin' }
        @{ Name = 'ambiguous hosts'; Uri = 'mongodb://127.0.0.1:27159,localhost:27160/admin' }
    ) {
        param($Name, $Uri)
        $script:mongoActionInvoked = $false

        {
            Invoke-ValidatedDisposableMusicRuntimeAction `
                -UriText $Uri `
                -MarkerPath (Join-Path $TestDrive 'missing-marker.json') `
                -Action { $script:mongoActionInvoked = $true }
        } | Should -Throw 'Disposable MongoDB URI is unsafe.'

        $script:mongoActionInvoked | Should -BeFalse
    }

    It 'accepts a marker-owned credential-free loopback URI with an explicit non-production port' {
        $uri = 'mongodb://localhost:27159/admin'
        $dataPath = Join-Path $TestDrive 'owned-mongo-data'
        $markerPath = Join-Path $dataPath 'codex-disposable-mongo.json'
        New-Item -ItemType Directory -Path $dataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            processId = 4242
            dataPath = $dataPath
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process { [pscustomobject]@{ Id = 4242; ProcessName = 'mongod' } }
        Mock Get-NetTCPConnection {
            [pscustomobject]@{
                LocalAddress = '127.0.0.1'
                LocalPort = 27159
                OwningProcess = 4242
                State = 'Listen'
            }
        }
        $script:mongoActionInvoked = $false

        $context = Invoke-ValidatedDisposableMusicRuntimeAction `
            -UriText $uri `
            -MarkerPath $markerPath `
            -Action { $script:mongoActionInvoked = $true }

        $script:mongoActionInvoked | Should -BeTrue
        $context.uri | Should -Be $uri
        $context.port | Should -Be 27159
    }

    It 'rejects a marker whose mongod process does not own the loopback listener' {
        $uri = 'mongodb://127.0.0.1:27159/admin'
        $dataPath = Join-Path $TestDrive 'stale-mongo-data'
        $markerPath = Join-Path $dataPath 'codex-disposable-mongo.json'
        New-Item -ItemType Directory -Path $dataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            processId = 4242
            dataPath = $dataPath
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process { [pscustomobject]@{ Id = 4242; ProcessName = 'mongod' } }
        Mock Get-NetTCPConnection {
            [pscustomobject]@{
                LocalAddress = '127.0.0.1'
                LocalPort = 27159
                OwningProcess = 9999
                State = 'Listen'
            }
        }
        $script:mongoActionInvoked = $false

        {
            Invoke-ValidatedDisposableMusicRuntimeAction `
                -UriText $uri `
                -MarkerPath $markerPath `
                -Action { $script:mongoActionInvoked = $true }
        } | Should -Throw 'Disposable MongoDB URI is unsafe.'

        $script:mongoActionInvoked | Should -BeFalse
    }
}

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

            function New-TestDeploymentLock {
                param([Collections.Generic.List[string]]$Events)

                $lock = [pscustomobject]@{
                    disposed = $false
                    events = $Events
                }
                $lock | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
                    $this.disposed = $true
                    if ($null -ne $this.events) {
                        [void]$this.events.Add('lock-release')
                    }
                }
                return $lock
            }
        }

        BeforeEach {
            $script:lastDeploymentLock = New-TestDeploymentLock
            Mock Enter-DeploymentLock { $script:lastDeploymentLock }
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
            foreach ($failure in @(
                @{ Phase = 'preflight'; Code = 'PREFLIGHT_FAILED' }
                @{ Phase = 'queue-replacement'; Code = 'QUEUE_REPLACEMENT_FAILED' }
                @{ Phase = 'radio-replacement'; Code = 'RADIO_REPLACEMENT_FAILED' }
                @{ Phase = 'readback'; Code = 'READBACK_FAILED' }
            )) {
                $script | Should -Match ([regex]::Escape("'$($failure.Phase)': '$($failure.Code)'"))
            }
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
                    programDataRoot = 'C:\production'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Running' } }
            Mock New-ProductionBackup { throw 'must not run' }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*must be stopped*'
            Should -Invoke New-ProductionBackup -Times 0
            Should -Invoke Invoke-CheckedProcess -Times 0
            $script:lastDeploymentLock.disposed | Should -BeTrue
        }

        It 'acquires the fixed deploy lock before inspecting the writer' {
            $events = [Collections.Generic.List[string]]::new()
            $lock = New-TestDeploymentLock -Events $events
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                    programDataRoot = 'C:\production'
                }
            }
            Mock Enter-DeploymentLock {
                [void]$events.Add('lock-acquire')
                $lock
            }
            Mock Get-Service {
                [void]$events.Add('service-check')
                [pscustomobject]@{ Status = 'Running' }
            }
            Mock New-ProductionBackup { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*must be stopped*'

            $events | Should -Be @('lock-acquire','service-check','lock-release')
            Should -Invoke Enter-DeploymentLock -Times 1 -Exactly -ParameterFilter {
                $LockPath -eq 'C:\production\locks\deploy.lock'
            }
        }

        It 'allows no service backup or Mongo effect when deploy lock acquisition contends' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                    programDataRoot = 'C:\production'
                }
            }
            Mock Enter-DeploymentLock { throw 'Another production operation is already running.' }
            Mock Get-Service { throw 'must not run' }
            Mock New-ProductionBackup { throw 'must not run' }
            Mock Invoke-CheckedProcess { throw 'must not run' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw 'Another production operation is already running.'

            Should -Invoke Get-Service -Times 0
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
                    programDataRoot = 'C:\production'
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
                    programDataRoot = 'C:\production'
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
                    programDataRoot = 'C:\production'
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
                    programDataRoot = 'C:\production'
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

            $failure = $null
            try {
                Invoke-ProductionMusicRuntimeStateRollback -Confirm
            } catch {
                $failure = $_.Exception
            }

            $failure.Message | Should -Match 'phase=writer-check'
            $failure.Message | Should -Match 'error=WRITER_NOT_STOPPED'
            $failure.Message | Should -Match ([regex]::Escape($archive))
            $failure.InnerException.Message | Should -Match 'must remain stopped'
            Should -Invoke Invoke-CheckedProcess -Times 0
            $script:lastDeploymentLock.disposed | Should -BeTrue
        }

        It 'backs up and verifies before fixed-loopback mongosh then returns bounded metadata' {
            $archive = New-VerifiedTestBackup -Name 'success.archive.gz'
            $script:events = [Collections.Generic.List[string]]::new()
            $lock = New-TestDeploymentLock -Events $script:events
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                    programDataRoot = 'C:\production'
                }
            }
            Mock Enter-DeploymentLock {
                [void]$script:events.Add('lock-acquire')
                $lock
            }
            Mock Get-Service {
                [void]$script:events.Add('service-check')
                [pscustomobject]@{ Status = 'Stopped' }
            }
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

            $script:events | Should -Be @(
                'lock-acquire','service-check','backup','checksum',
                'service-check','mongosh','lock-release')
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

        It 'releases the deploy lock and redacts a native process failure after verified backup' {
            $archive = New-VerifiedTestBackup -Name 'process-failure.archive.gz'
            $sensitive = 'mongodb://operator:secret@remote.example:27017/private-track-token'
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                    programDataRoot = 'C:\production'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess { throw $sensitive }

            $failure = $null
            try {
                Invoke-ProductionMusicRuntimeStateRollback -Confirm
            } catch {
                $failure = $_.Exception
            }

            $failure.Message | Should -Match 'phase=process'
            $failure.Message | Should -Match 'error=MONGOSH_PROCESS_FAILED'
            $failure.Message | Should -Match ([regex]::Escape($archive))
            $failure.Message | Should -Not -Match 'secret|remote|private-track-token|27017'
            $failure.InnerException.Message | Should -Be $sensitive
            $script:lastDeploymentLock.disposed | Should -BeTrue
        }

        It 'reports allowlisted generated-script failure phase <Phase>' -TestCases @(
            @{ Phase = 'preflight'; ErrorCode = 'PREFLIGHT_FAILED' }
            @{ Phase = 'queue-replacement'; ErrorCode = 'QUEUE_REPLACEMENT_FAILED' }
            @{ Phase = 'radio-replacement'; ErrorCode = 'RADIO_REPLACEMENT_FAILED' }
            @{ Phase = 'readback'; ErrorCode = 'READBACK_FAILED' }
        ) {
            param($Phase, $ErrorCode)
            $archive = New-VerifiedTestBackup -Name "$Phase.archive.gz"
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                    programDataRoot = 'C:\production'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess {
                [ordered]@{
                    complete = $false
                    phase = $Phase
                    errorCode = $ErrorCode
                } | ConvertTo-Json -Compress
            }

            $failure = $null
            try {
                Invoke-ProductionMusicRuntimeStateRollback -Confirm
            } catch {
                $failure = $_.Exception
            }

            $failure.Message | Should -Match "phase=$([regex]::Escape($Phase))"
            $failure.Message | Should -Match "error=$ErrorCode"
            $failure.Message | Should -Match ([regex]::Escape($archive))
            $failure.InnerException | Should -Not -BeNullOrEmpty
            $script:lastDeploymentLock.disposed | Should -BeTrue
        }

        It 'reports and redacts malformed metadata after verified backup' {
            $archive = New-VerifiedTestBackup -Name 'metadata-failure.archive.gz'
            $sensitive = 'private-bson-track-token'
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                    programDataRoot = 'C:\production'
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status = 'Stopped' } }
            Mock New-ProductionBackup { $archive }
            Mock Invoke-CheckedProcess { "{invalid:$sensitive}" }

            $failure = $null
            try {
                Invoke-ProductionMusicRuntimeStateRollback -Confirm
            } catch {
                $failure = $_.Exception
            }

            $failure.Message | Should -Match 'phase=metadata'
            $failure.Message | Should -Match 'error=METADATA_INVALID'
            $failure.Message | Should -Match ([regex]::Escape($archive))
            $failure.Message | Should -Not -Match $sensitive
            $failure.InnerException.Message | Should -Be (
                'Music runtime rollback returned invalid metadata.')
            $failure.ToString() | Should -Not -Match $sensitive
            $script:lastDeploymentLock.disposed | Should -BeTrue
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
        $disposableContext = Assert-DisposableMusicRuntimeMongoUri `
            -UriText $env:MUSIC_RUNTIME_ROLLBACK_TEST_URI `
            -MarkerPath $env:MUSIC_RUNTIME_ROLLBACK_TEST_MARKER
        $moduleRoot = Join-Path $PSScriptRoot '..\modules'
        $mongoShell = if ([string]::IsNullOrWhiteSpace($env:MONGOSH_EXE)) {
            (Get-Command mongosh.exe -ErrorAction Stop).Source
        } else {
            $env:MONGOSH_EXE
        }

        function Invoke-DisposableMusicRuntimeMongo {
            param([Parameter(Mandatory)][string]$Script)

            $output = & $mongoShell --quiet --norc $disposableContext.uri `
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

        $json = Invoke-CheckedProcess `
            -FilePath $mongoShell `
            -ArgumentList @(
                '--quiet'
                '--norc'
                $disposableContext.uri
                '--eval'
                (Get-ProductionMusicRuntimeRollbackScript)
            ) `
            -WorkingDirectory $PSScriptRoot
        $metadata = $json | ConvertFrom-Json -ErrorAction Stop
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

        $failureOutput = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript)
        $failureMetadata = $failureOutput[-1] | ConvertFrom-Json -ErrorAction Stop
        $failureMetadata.complete | Should -BeFalse
        $failureMetadata.phase | Should -Be 'preflight'
        $failureMetadata.errorCode | Should -Be 'PREFLIGHT_FAILED'

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

        $failureOutput = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript)
        $failureMetadata = $failureOutput[-1] | ConvertFrom-Json -ErrorAction Stop
        $failureMetadata.complete | Should -BeFalse
        $failureMetadata.phase | Should -Be 'preflight'
        $failureMetadata.errorCode | Should -Be 'PREFLIGHT_FAILED'

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
