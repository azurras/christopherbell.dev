$moduleRoot = Join-Path $PSScriptRoot '..\modules'
Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
Import-Module (Join-Path $moduleRoot 'Production.WriterStart.psm1') -Global -Force
Import-Module (Join-Path $moduleRoot 'Production.MusicRuntime.psm1') -Force

BeforeAll {
function Resolve-DisposableMusicRuntimePath {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][bool]$Directory
    )

    if (-not [IO.Path]::IsPathRooted($Path) -or
        $Path -notmatch '^[A-Za-z]:[\\/]') {
        throw 'Disposable ownership path must be absolute.'
    }
    $fullPath = [IO.Path]::GetFullPath($Path)
    $pathRoot = [IO.Path]::GetPathRoot($fullPath)
    $canonical = if ($fullPath.Equals(
            $pathRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        $pathRoot
    } else {
        $fullPath.TrimEnd(
            [IO.Path]::DirectorySeparatorChar,
            [IO.Path]::AltDirectorySeparatorChar)
    }
    $item = Get-Item -LiteralPath $canonical -Force -ErrorAction Stop
    if ([bool]$item.PSIsContainer -ne $Directory) {
        throw 'Disposable ownership path has the wrong type.'
    }
    $current = $canonical
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        $component = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if ($component.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Disposable ownership path contains a reparse point.'
        }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) { break }
        $current = $parent.FullName
    }
    return [IO.Path]::GetFullPath([string]$item.FullName).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
}

function Test-DisposableMusicRuntimePathBelowRoot {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Root
    )

    return $Path.StartsWith(
        $Root.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)
}

function ConvertTo-DisposableMusicRuntimeEpochMillis {
    param([Parameter(Mandatory)][datetime]$Value)

    return [DateTimeOffset]::new($Value.ToUniversalTime()).ToUnixTimeMilliseconds()
}

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
        if ([string]::IsNullOrWhiteSpace($MarkerPath)) {
            throw 'Disposable ownership marker is missing.'
        }
        $canonicalMarker = Resolve-DisposableMusicRuntimePath `
            -Path $MarkerPath `
            -Directory $false
        $marker = Get-Content -LiteralPath $canonicalMarker -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $names = @($marker.PSObject.Properties.Name)
        $expectedNames = @(
            'uri','port','bindIp','processId','processStartTimeEpochMillis',
            'mongoStartTimeEpochMillis','mongoStartupId','dataPath',
            'disposableRoot')
        if ($names.Count -ne $expectedNames.Count -or
            @($expectedNames | Where-Object { $_ -notin $names }).Count -ne 0 -or
            $marker.uri -isnot [string] -or [string]$marker.uri -cne $UriText -or
            ($marker.port -isnot [int] -and $marker.port -isnot [long]) -or
            [int]$marker.port -ne $port -or
            $marker.bindIp -isnot [string] -or
            [string]$marker.bindIp -cne '127.0.0.1' -or
            ($marker.processId -isnot [int] -and $marker.processId -isnot [long]) -or
            [long]$marker.processId -lt 1 -or
            ($marker.processStartTimeEpochMillis -isnot [int] -and
                $marker.processStartTimeEpochMillis -isnot [long]) -or
            [long]$marker.processStartTimeEpochMillis -lt 1 -or
            ($marker.mongoStartTimeEpochMillis -isnot [int] -and
                $marker.mongoStartTimeEpochMillis -isnot [long]) -or
            [long]$marker.mongoStartTimeEpochMillis -lt 1 -or
            $marker.mongoStartupId -isnot [string] -or
            [string]::IsNullOrWhiteSpace([string]$marker.mongoStartupId) -or
            $marker.dataPath -isnot [string] -or
            $marker.disposableRoot -isnot [string]) {
            throw 'Disposable ownership marker is invalid.'
        }
        $ownedRoot = Resolve-DisposableMusicRuntimePath `
            -Path ([string]$marker.disposableRoot) `
            -Directory $true
        if ($ownedRoot.Equals(
                [IO.Path]::GetPathRoot($ownedRoot),
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Disposable ownership root must not be a filesystem root.'
        }
        $dataPath = Resolve-DisposableMusicRuntimePath `
            -Path ([string]$marker.dataPath) `
            -Directory $true
        if (-not (Test-DisposableMusicRuntimePathBelowRoot `
                -Path $canonicalMarker `
                -Root $ownedRoot) -or
            -not (Test-DisposableMusicRuntimePathBelowRoot `
                -Path $dataPath `
                -Root $ownedRoot)) {
            throw 'Disposable ownership paths are outside their root.'
        }
        $process = Get-Process -Id ([int]$marker.processId) -ErrorAction Stop
        if ([string]$process.ProcessName -cne 'mongod' -or
            (ConvertTo-DisposableMusicRuntimeEpochMillis -Value $process.StartTime) -ne
                [long]$marker.processStartTimeEpochMillis) {
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
            bindIp = [string]$marker.bindIp
            processId = [int]$marker.processId
            processStartTimeEpochMillis =
                [long]$marker.processStartTimeEpochMillis
            mongoStartTimeEpochMillis = [long]$marker.mongoStartTimeEpochMillis
            mongoStartupId = [string]$marker.mongoStartupId
            dataPath = $dataPath
            disposableRoot = $ownedRoot
            markerPath = $canonicalMarker
        }
    } catch {
        throw 'Disposable MongoDB URI is unsafe.'
    }
}

function New-DisposableMusicRuntimeGuardedScript {
    param(
        [Parameter(Mandatory)][psobject]$Context,
        [Parameter(Mandatory)][string]$Script
    )

    $expected = [ordered]@{
        processId = [int]$Context.processId
        mongoStartTimeEpochMillis = [long]$Context.mongoStartTimeEpochMillis
        mongoStartupId = [string]$Context.mongoStartupId
        port = [int]$Context.port
        bindIp = [string]$Context.bindIp
        dataPath = [string]$Context.dataPath
    } | ConvertTo-Json -Compress
    return @"
const __disposableExpected = $expected;
const __disposableFail = () => {
  throw new Error('Disposable MongoDB server ownership changed.');
};
const __disposableAdmin = db.getSiblingDB('admin');
const __disposableStatus = __disposableAdmin.runCommand({serverStatus: 1});
const __disposableOptions = __disposableAdmin.runCommand({getCmdLineOpts: 1});
const __disposableStartup = db.getSiblingDB('local')
  .getCollection('startup_log')
  .find({})
  .sort({startTime: -1})
  .limit(1)
  .toArray()[0];
const __disposableNumber = (value) => Number(value.toString());
if (__disposableStatus.ok !== 1 ||
    __disposableOptions.ok !== 1 ||
    !__disposableStartup ||
    !(__disposableStartup.startTime instanceof Date) ||
    __disposableNumber(__disposableStatus.pid) !== __disposableExpected.processId ||
    __disposableNumber(__disposableStartup.pid) !== __disposableExpected.processId ||
    __disposableStartup._id !== __disposableExpected.mongoStartupId ||
    __disposableStartup.startTime.valueOf() !==
      __disposableExpected.mongoStartTimeEpochMillis ||
    !__disposableOptions.parsed ||
    !__disposableOptions.parsed.net ||
    !__disposableOptions.parsed.storage ||
    __disposableOptions.parsed.net.port !== __disposableExpected.port ||
    __disposableOptions.parsed.net.bindIp !== __disposableExpected.bindIp ||
    __disposableOptions.parsed.storage.dbPath !== __disposableExpected.dataPath) {
  __disposableFail();
}
$Script
"@
}

function Invoke-DisposableMusicRuntimeGuardedProcess {
    param(
        [Parameter(Mandatory)][string]$MongoShell,
        [Parameter(Mandatory)][psobject]$Context,
        [Parameter(Mandatory)][string]$Script,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )

    $guardedScript = New-DisposableMusicRuntimeGuardedScript `
        -Context $Context `
        -Script $Script
    $output = Invoke-CheckedProcess `
        -FilePath $MongoShell `
        -ArgumentList @(
            '--quiet'
            '--norc'
            $Context.uri
            '--eval'
            $guardedScript
        ) `
        -WorkingDirectory $WorkingDirectory
    return $output.Trim()
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
        $ownedRoot = Join-Path $TestDrive 'owned-mongo-root'
        $dataPath = Join-Path $ownedRoot 'data'
        $markerPath = Join-Path $ownedRoot 'codex-disposable-mongo.json'
        $processStart = [datetime]'2026-08-09T12:00:00Z'
        New-Item -ItemType Directory -Path $dataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            bindIp = '127.0.0.1'
            processId = 4242
            processStartTimeEpochMillis =
                (ConvertTo-DisposableMusicRuntimeEpochMillis -Value $processStart)
            mongoStartTimeEpochMillis = 1786276801000
            mongoStartupId = 'test-host-1786276801000'
            dataPath = $dataPath
            disposableRoot = $ownedRoot
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process {
            [pscustomobject]@{
                Id = 4242
                ProcessName = 'mongod'
                StartTime = $processStart
            }
        }
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
        $context.processStartTimeEpochMillis | Should -Be 1786276800000
        $context.mongoStartupId | Should -Be 'test-host-1786276801000'
        $context.dataPath | Should -Be ([IO.Path]::GetFullPath($dataPath))
    }

    It 'rejects a marker whose mongod process does not own the loopback listener' {
        $uri = 'mongodb://127.0.0.1:27159/admin'
        $ownedRoot = Join-Path $TestDrive 'stale-mongo-root'
        $dataPath = Join-Path $ownedRoot 'data'
        $markerPath = Join-Path $ownedRoot 'codex-disposable-mongo.json'
        $processStart = [datetime]'2026-08-09T12:00:00Z'
        New-Item -ItemType Directory -Path $dataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            bindIp = '127.0.0.1'
            processId = 4242
            processStartTimeEpochMillis = 1786276800000
            mongoStartTimeEpochMillis = 1786276801000
            mongoStartupId = 'test-host-1786276801000'
            dataPath = $dataPath
            disposableRoot = $ownedRoot
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process {
            [pscustomobject]@{
                Id = 4242
                ProcessName = 'mongod'
                StartTime = $processStart
            }
        }
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

    It 'rejects process replacement after an earlier ownership validation' {
        $uri = 'mongodb://127.0.0.1:27159/admin'
        $ownedRoot = Join-Path $TestDrive 'replaced-process-root'
        $dataPath = Join-Path $ownedRoot 'data'
        $markerPath = Join-Path $ownedRoot 'codex-disposable-mongo.json'
        $originalStart = [datetime]'2026-08-09T12:00:00Z'
        $script:reportedProcessStart = $originalStart
        New-Item -ItemType Directory -Path $dataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            bindIp = '127.0.0.1'
            processId = 4242
            processStartTimeEpochMillis = 1786276800000
            mongoStartTimeEpochMillis = 1786276801000
            mongoStartupId = 'test-host-1786276801000'
            dataPath = $dataPath
            disposableRoot = $ownedRoot
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process {
            [pscustomobject]@{
                Id = 4242
                ProcessName = 'mongod'
                StartTime = $script:reportedProcessStart
            }
        }
        Mock Get-NetTCPConnection {
            [pscustomobject]@{
                LocalAddress = '127.0.0.1'
                LocalPort = 27159
                OwningProcess = 4242
                State = 'Listen'
            }
        }

        $null = Assert-DisposableMusicRuntimeMongoUri `
            -UriText $uri `
            -MarkerPath $markerPath
        $script:reportedProcessStart = $originalStart.AddSeconds(1)
        $script:mongoActionInvoked = $false

        {
            Invoke-ValidatedDisposableMusicRuntimeAction `
                -UriText $uri `
                -MarkerPath $markerPath `
                -Action { $script:mongoActionInvoked = $true }
        } | Should -Throw 'Disposable MongoDB URI is unsafe.'

        $script:mongoActionInvoked | Should -BeFalse
    }

    It 'rejects a reparse-backed data path before invoking the Mongo action' {
        $uri = 'mongodb://127.0.0.1:27159/admin'
        $ownedRoot = Join-Path $TestDrive 'reparse-root'
        $realDataPath = Join-Path $TestDrive 'real-data'
        $dataPath = Join-Path $ownedRoot 'linked-data'
        $markerPath = Join-Path $ownedRoot 'codex-disposable-mongo.json'
        New-Item -ItemType Directory -Path $ownedRoot | Out-Null
        New-Item -ItemType Directory -Path $realDataPath | Out-Null
        New-Item -ItemType Junction -Path $dataPath -Target $realDataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            bindIp = '127.0.0.1'
            processId = 4242
            processStartTimeEpochMillis = 1786276800000
            mongoStartTimeEpochMillis = 1786276801000
            mongoStartupId = 'test-host-1786276801000'
            dataPath = $dataPath
            disposableRoot = $ownedRoot
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process { throw 'must not inspect process' }
        $script:mongoActionInvoked = $false

        {
            Invoke-ValidatedDisposableMusicRuntimeAction `
                -UriText $uri `
                -MarkerPath $markerPath `
                -Action { $script:mongoActionInvoked = $true }
        } | Should -Throw 'Disposable MongoDB URI is unsafe.'

        $script:mongoActionInvoked | Should -BeFalse
        Should -Invoke Get-Process -Times 0
    }

    It 'rejects a filesystem root as the claimed disposable root' {
        $uri = 'mongodb://127.0.0.1:27159/admin'
        $dataPath = Join-Path $TestDrive 'root-claim-data'
        $markerPath = Join-Path $TestDrive 'root-claim-marker.json'
        $processStart = [datetime]'2026-08-09T12:00:00Z'
        New-Item -ItemType Directory -Path $dataPath | Out-Null
        [ordered]@{
            uri = $uri
            port = 27159
            bindIp = '127.0.0.1'
            processId = 4242
            processStartTimeEpochMillis = 1786276800000
            mongoStartTimeEpochMillis = 1786276801000
            mongoStartupId = 'test-host-1786276801000'
            dataPath = $dataPath
            disposableRoot = [IO.Path]::GetPathRoot($TestDrive)
        } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
        Mock Get-Process {
            [pscustomobject]@{
                Id = 4242
                ProcessName = 'mongod'
                StartTime = $processStart
            }
        }
        Mock Get-NetTCPConnection {
            [pscustomobject]@{
                LocalAddress = '127.0.0.1'
                LocalPort = 27159
                OwningProcess = 4242
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

        It 'treats an absent schema-direction marker as migration not activated' {
            $config = [pscustomobject]@{ programDataRoot = $TestDrive }

            Read-ProductionMusicSchemaDirection -Config $config |
                Should -BeNullOrEmpty
        }

        It 'rejects malformed schema-direction state without echoing marker content' {
            $config = [pscustomobject]@{ programDataRoot = $TestDrive }
            $stateRoot = Join-Path $TestDrive 'state'
            $path = Join-Path $stateRoot 'music-runtime-schema-direction.json'
            New-Item -ItemType Directory -Path $stateRoot | Out-Null
            '{"version":1,"state":"UNSAFE","private":"secret-track-token"}' |
                Set-Content -LiteralPath $path -Encoding utf8

            $failure = $null
            try { Read-ProductionMusicSchemaDirection -Config $config }
            catch { $failure = $_.Exception }

            $failure.Message | Should -Be 'Music runtime schema-direction marker is invalid.'
            $failure.ToString() | Should -Not -Match 'secret-track-token|UNSAFE'
        }

        It 'atomically protects and replaces the bounded schema-direction marker' {
            $config = [pscustomobject]@{ programDataRoot = $TestDrive }
            $events = [Collections.Generic.List[string]]::new()
            Mock Protect-ProductionPath { [void]$events.Add("protect:$Path") } `
                -ModuleName Production.WriterStart
            Mock Assert-ProtectedProductionPath { [void]$events.Add("verify:$Path") } `
                -ModuleName Production.WriterStart

            Write-ProductionMusicSchemaDirection `
                -Config $config `
                -State TARGET_ACTIVE `
                -TargetRelease '0123456789abcdef0123456789abcdef01234567' `
                -LegacyRelease '89abcdef0123456789abcdef0123456789abcdef'

            $value = Read-ProductionMusicSchemaDirection -Config $config
            $value.state | Should -Be 'TARGET_ACTIVE'
            @($value.PSObject.Properties.Name) | Should -Be @(
                'version','state','updatedAtEpochMillis','targetRelease','legacyRelease')
            ($events -join '|') | Should -Match 'protect:.*\.tmp\|verify:.*\.tmp'
            @(Get-ChildItem (Join-Path $TestDrive 'state') -Filter '*.tmp') |
                Should -BeNullOrEmpty
        }

        BeforeEach {
            $script:lastDeploymentLock = New-TestDeploymentLock
            Mock Enter-DeploymentLock { $script:lastDeploymentLock }
            Mock Enter-ProductionFixedRootDeploymentLock {
                [pscustomobject]@{
                    Lock = Enter-DeploymentLock `
                        -LockPath 'C:\ProgramData\christopherbell.dev\locks\deploy.lock'
                }
            }
        }

        It 'rejects an alternate rollback root before lock, service, backup, or database effects' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\attacker-controlled'
                    mongoShellExe = 'C:\attacker-controlled\mongosh.exe'
                    repositoryPath = 'C:\attacker-controlled\repository'
                }
            }
            Mock Enter-ProductionFixedRootDeploymentLock {
                if ($FixedRoot -cne 'C:\ProgramData\christopherbell.dev') {
                    throw 'wrong fixed root reached'
                }
                throw ('Production root boundary is not guarded. ' +
                    'Run guarded prod install before retrying.')
            }
            Mock Enter-DeploymentLock { throw 'unsafe lock was reached' }
            Mock Get-Service { throw 'service was reached' }
            Mock New-ProductionBackup { throw 'backup was reached' }
            Mock Invoke-CheckedProcess { throw 'database was reached' }

            { Invoke-ProductionMusicRuntimeStateRollback -Confirm } |
                Should -Throw '*Run guarded prod install before retrying*'
            Should -Invoke Enter-ProductionFixedRootDeploymentLock -Times 1 -Exactly
            Should -Invoke Enter-DeploymentLock -Times 0 -Exactly
            Should -Invoke Get-Service -Times 0 -Exactly
            Should -Invoke New-ProductionBackup -Times 0 -Exactly
            Should -Invoke Invoke-CheckedProcess -Times 0 -Exactly
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

        It 'generates an exact optional legacy-to-target reconciliation script without deletion' {
            $script = Get-ProductionMusicRuntimeReconciliationScript

            ([regex]::Matches($script, '\.replaceOne\s*\(')).Count | Should -Be 2
            $script | Should -Not -Match '\.(drop|dropDatabase|deleteOne|deleteMany|remove|renameCollection)\s*\('
            $script | Should -Match 'sourcePresence'
            $script | Should -Match "getCollection\('music_runtime_state'\)"
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
                $LockPath -eq 'C:\ProgramData\christopherbell.dev\locks\deploy.lock'
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
        $disposableUri = $env:MUSIC_RUNTIME_ROLLBACK_TEST_URI
        $disposableMarker = $env:MUSIC_RUNTIME_ROLLBACK_TEST_MARKER
        $moduleRoot = Join-Path $PSScriptRoot '..\modules'
        $mongoShell = if ([string]::IsNullOrWhiteSpace($env:MONGOSH_EXE)) {
            (Get-Command mongosh.exe -ErrorAction Stop).Source
        } else {
            $env:MONGOSH_EXE
        }

        function Invoke-DisposableMusicRuntimeMongo {
            param([Parameter(Mandatory)][string]$Script)

            $context = Assert-DisposableMusicRuntimeMongoUri `
                -UriText $disposableUri `
                -MarkerPath $disposableMarker
            return Invoke-DisposableMusicRuntimeGuardedProcess `
                -MongoShell $mongoShell `
                -Context $context `
                -Script $Script `
                -WorkingDirectory $PSScriptRoot
        }

        function Reset-DisposableMusicRuntimeState {
            $script = @'
const target = db.getSiblingDB('christopherbell');
target.getCollection('music_runtime_state').deleteMany({});
target.getCollection('music_queue_state').deleteMany({});
target.getCollection('music_radio_state').deleteMany({});
target.getCollection('ownership_guard_probe').deleteMany({});
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

        $json = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript)
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
        $proof = $readback | ConvertFrom-Json -ErrorAction Stop
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
        $failureMetadata = $failureOutput | ConvertFrom-Json -ErrorAction Stop
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
        ($proof | ConvertFrom-Json).queueUnchanged | Should -BeTrue
        ($proof | ConvertFrom-Json).radioUnchanged | Should -BeTrue
    }

    It 'rejects conflicting retained legacy shape before changing either singleton' {
        Set-DisposableValidMusicRuntimeState -LegacyQueueSuffix ",unexpected:'private-value'"

        $failureOutput = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeRollbackScript)
        $failureMetadata = $failureOutput | ConvertFrom-Json -ErrorAction Stop
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
        ($proof | ConvertFrom-Json).queueUnchanged | Should -BeTrue
        ($proof | ConvertFrom-Json).radioUnchanged | Should -BeTrue
    }

    It 'reconciles exact retained legacy presence queue=<Queue> radio=<Radio>' -TestCases @(
        @{ Queue=$true; Radio=$true }
        @{ Queue=$true; Radio=$false }
        @{ Queue=$false; Radio=$true }
        @{ Queue=$false; Radio=$false }
    ) {
        param($Queue, $Radio)
        $fixture = "const target=db.getSiblingDB('christopherbell');"
        if ($Queue) {
            $fixture += "target.music_queue_state.insertOne({_id:'global',entries:[{id:'entry-2',trackId:'legacy-track',observedToken:'legacy-token',enqueuedByAccountId:'account-2',enqueuedAt:new Date('2026-08-09T13:00:00Z')}],version:NumberLong('12')});"
        }
        if ($Radio) {
            $fixture += "target.music_radio_state.insertOne({_id:'global',stationSequence:NumberLong('13'),trackId:'legacy-radio',observedToken:'legacy-radio-token',startedAt:new Date('2026-08-09T13:01:00Z'),durationSeconds:91.5,source:'RADIO',version:NumberLong('14')});"
        }
        $null = Invoke-DisposableMusicRuntimeMongo -Script $fixture

        $metadata = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeReconciliationScript) |
            ConvertFrom-Json -ErrorAction Stop

        $metadata.complete | Should -BeTrue
        [bool]$metadata.sourcePresence.queue | Should -Be $Queue
        [bool]$metadata.sourcePresence.radio | Should -Be $Radio
        [int]$metadata.destinationCount | Should -Be ([int]$Queue + [int]$Radio)

        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
const target=db.getSiblingDB('christopherbell');
const queue=target.music_runtime_state.findOne({_id:'queue'});
const radio=target.music_runtime_state.findOne({_id:'radio'});
print(JSON.stringify({
  queuePresent:queue !== null,
  radioPresent:radio !== null,
  queueExact:queue === null || (queue.kind === 'QUEUE' && queue.queue.entries[0].trackId === 'legacy-track' && queue.version.toString() === '12'),
  radioExact:radio === null || (radio.kind === 'RADIO' && radio.radio.trackId === 'legacy-radio' && radio.version.toString() === '14')
}));
'@ | ConvertFrom-Json -ErrorAction Stop
        [bool]$proof.queuePresent | Should -Be $Queue
        [bool]$proof.radioPresent | Should -Be $Radio
        $proof.queueExact | Should -BeTrue
        $proof.radioExact | Should -BeTrue
    }

    It 'rejects an absent legacy singleton conflicting with retained target state before mutation' {
        $null = Invoke-DisposableMusicRuntimeMongo -Script @'
const target=db.getSiblingDB('christopherbell');
target.music_runtime_state.insertOne({_id:'queue',kind:'QUEUE',queue:{entries:[]}});
target.music_radio_state.insertOne({_id:'global',stationSequence:NumberLong('1'),trackId:'legacy-radio',observedToken:'token',startedAt:new Date('2026-08-09T13:01:00Z'),durationSeconds:91.5,source:'RADIO'});
'@

        $metadata = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeReconciliationScript) |
            ConvertFrom-Json -ErrorAction Stop

        $metadata.complete | Should -BeFalse
        $metadata.phase | Should -Be 'preflight'
        $metadata.errorCode | Should -Be 'PREFLIGHT_FAILED'
        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
const target=db.getSiblingDB('christopherbell');
print(JSON.stringify({queueCount:target.music_runtime_state.countDocuments({_id:'queue'}),radioCount:target.music_runtime_state.countDocuments({_id:'radio'})}));
'@ | ConvertFrom-Json -ErrorAction Stop
        [int]$proof.queueCount | Should -Be 1
        [int]$proof.radioCount | Should -Be 0
    }

    It 'rejects BSON <Kind> for legacy <Field> before target mutation' -TestCases @(
        @{ Kind='Double'; Encoding="NumberLong('7')"; Field='version' }
        @{ Kind='Decimal128'; Encoding="NumberDecimal('7')"; Field='version' }
        @{ Kind='Double'; Encoding="NumberLong('7')"; Field='stationSequence' }
        @{ Kind='Decimal128'; Encoding="NumberDecimal('7')"; Field='stationSequence' }
    ) {
        param($Kind, $Encoding, $Field)
        $fixture = "const target=db.getSiblingDB('christopherbell');" +
            "target.music_runtime_state.insertOne({_id:'queue',kind:'QUEUE',queue:{entries:[]}});"
        if ($Field -eq 'version') {
            $fixture += "target.music_queue_state.insertOne({_id:'global',entries:[],version:$Encoding});"
        } else {
            $fixture += "target.music_radio_state.insertOne({_id:'global',stationSequence:$Encoding,trackId:'legacy-radio',observedToken:'token',startedAt:new Date('2026-08-09T13:01:00Z'),durationSeconds:91.5,source:'RADIO'});"
        }
        if ($Kind -eq 'Double' -and $Field -eq 'version') {
            $fixture += "target.music_queue_state.updateOne({}, [{`$set:{version:{`$toDouble:'`$version'}}}]);"
        }
        if ($Kind -eq 'Double' -and $Field -eq 'stationSequence') {
            $fixture += "target.music_radio_state.updateOne({}, [{`$set:{stationSequence:{`$toDouble:'`$stationSequence'}}}]);"
        }
        $null = Invoke-DisposableMusicRuntimeMongo -Script $fixture

        $metadata = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeReconciliationScript) |
            ConvertFrom-Json -ErrorAction Stop

        $metadata.complete | Should -BeFalse
        $metadata.phase | Should -Be 'preflight'
        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
const target=db.getSiblingDB('christopherbell');
print(JSON.stringify({queueCount:target.music_runtime_state.countDocuments({_id:'queue'}),radioCount:target.music_runtime_state.countDocuments({_id:'radio'})}));
'@ | ConvertFrom-Json -ErrorAction Stop
        [int]$proof.queueCount | Should -Be 1
        [int]$proof.radioCount | Should -Be 0
    }

    It 'rejects BSON <Kind> in retained target <Field> before replacement' -TestCases @(
        @{ Kind='Double'; Encoding="NumberLong('7')"; Field='version' }
        @{ Kind='Decimal128'; Encoding="NumberDecimal('7')"; Field='version' }
        @{ Kind='Double'; Encoding="NumberLong('7')"; Field='stationSequence' }
        @{ Kind='Decimal128'; Encoding="NumberDecimal('7')"; Field='stationSequence' }
    ) {
        param($Kind, $Encoding, $Field)
        $fixture = "const target=db.getSiblingDB('christopherbell');"
        if ($Field -eq 'version') {
            $fixture += "target.music_runtime_state.insertOne({_id:'queue',kind:'QUEUE',queue:{entries:[{id:'retained',trackId:'retained-track',observedToken:'retained-token',enqueuedByAccountId:'account',enqueuedAt:new Date('2026-08-09T13:00:00Z')}]},version:$Encoding});"
            $fixture += "target.music_queue_state.insertOne({_id:'global',entries:[]});"
        } else {
            $fixture += "target.music_runtime_state.insertOne({_id:'radio',kind:'RADIO',radio:{stationSequence:$Encoding,trackId:'retained-radio',observedToken:'retained-token',startedAt:new Date('2026-08-09T13:01:00Z'),durationSeconds:91.5,source:'RADIO'}});"
            $fixture += "target.music_radio_state.insertOne({_id:'global',stationSequence:NumberLong('8'),trackId:'legacy-radio',observedToken:'legacy-token',startedAt:new Date('2026-08-09T13:02:00Z'),durationSeconds:92.5,source:'RADIO'});"
        }
        if ($Kind -eq 'Double' -and $Field -eq 'version') {
            $fixture += "target.music_runtime_state.updateOne({_id:'queue'}, [{`$set:{version:{`$toDouble:'`$version'}}}]);"
        }
        if ($Kind -eq 'Double' -and $Field -eq 'stationSequence') {
            $fixture += "target.music_runtime_state.updateOne({_id:'radio'}, [{`$set:{'radio.stationSequence':{`$toDouble:'`$radio.stationSequence'}}}]);"
        }
        $null = Invoke-DisposableMusicRuntimeMongo -Script $fixture

        $metadata = Invoke-DisposableMusicRuntimeMongo `
            -Script (Get-ProductionMusicRuntimeReconciliationScript) |
            ConvertFrom-Json -ErrorAction Stop

        $metadata.complete | Should -BeFalse
        $metadata.phase | Should -Be 'preflight'
        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
const target=db.getSiblingDB('christopherbell');
const queue=target.music_runtime_state.findOne({_id:'queue'});
const radio=target.music_runtime_state.findOne({_id:'radio'});
print(JSON.stringify({queueTrack:queue && queue.queue.entries[0].trackId,radioTrack:radio && radio.radio.trackId}));
'@ | ConvertFrom-Json -ErrorAction Stop
        if ($Field -eq 'version') { $proof.queueTrack | Should -Be 'retained-track' }
        else { $proof.radioTrack | Should -Be 'retained-radio' }
    }

    It 'rejects changed server process identity before destructive fixture mutation' {
        $null = Invoke-DisposableMusicRuntimeMongo -Script @'
db.getSiblingDB('christopherbell').ownership_guard_probe.insertOne({_id:'sentinel'});
'@
        $context = Assert-DisposableMusicRuntimeMongoUri `
            -UriText $disposableUri `
            -MarkerPath $disposableMarker
        $changedContext = $context.PSObject.Copy()
        $changedContext.mongoStartupId = 'replacement-process-startup-id'

        {
            Invoke-DisposableMusicRuntimeGuardedProcess `
                -MongoShell $mongoShell `
                -Context $changedContext `
                -Script @'
db.getSiblingDB('christopherbell').ownership_guard_probe.deleteMany({});
'@ `
                -WorkingDirectory $PSScriptRoot
        } | Should -Throw 'mongosh.exe exited with code *'

        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
print(db.getSiblingDB('christopherbell').ownership_guard_probe.countDocuments({}));
'@
        [int]$proof | Should -Be 1
    }

    It 'rejects changed server PID before destructive fixture mutation' {
        $null = Invoke-DisposableMusicRuntimeMongo -Script @'
db.getSiblingDB('christopherbell').ownership_guard_probe.insertOne({_id:'sentinel'});
'@
        $context = Assert-DisposableMusicRuntimeMongoUri `
            -UriText $disposableUri `
            -MarkerPath $disposableMarker
        $changedContext = $context.PSObject.Copy()
        $changedContext.processId++

        {
            Invoke-DisposableMusicRuntimeGuardedProcess `
                -MongoShell $mongoShell `
                -Context $changedContext `
                -Script @'
db.getSiblingDB('christopherbell').ownership_guard_probe.deleteMany({});
'@ `
                -WorkingDirectory $PSScriptRoot
        } | Should -Throw 'mongosh.exe exited with code *'

        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
print(db.getSiblingDB('christopherbell').ownership_guard_probe.countDocuments({}));
'@
        [int]$proof | Should -Be 1
    }

    It 'rejects changed getCmdLineOpts <Name> before destructive fixture mutation' -TestCases @(
        @{ Name = 'dbPath'; Property = 'dataPath'; Value = 'C:\wrong-disposable-db' }
        @{ Name = 'bindIp'; Property = 'bindIp'; Value = '127.0.0.2' }
        @{ Name = 'port'; Property = 'port'; Value = 27168 }
    ) {
        param($Name, $Property, $Value)
        $null = Invoke-DisposableMusicRuntimeMongo -Script @'
db.getSiblingDB('christopherbell').ownership_guard_probe.insertOne({_id:'sentinel'});
'@
        $context = Assert-DisposableMusicRuntimeMongoUri `
            -UriText $disposableUri `
            -MarkerPath $disposableMarker
        $changedContext = $context.PSObject.Copy()
        $changedContext.$Property = $Value

        {
            Invoke-DisposableMusicRuntimeGuardedProcess `
                -MongoShell $mongoShell `
                -Context $changedContext `
                -Script @'
db.getSiblingDB('christopherbell').ownership_guard_probe.deleteMany({});
'@ `
                -WorkingDirectory $PSScriptRoot
        } | Should -Throw 'mongosh.exe exited with code *'

        $proof = Invoke-DisposableMusicRuntimeMongo -Script @'
print(db.getSiblingDB('christopherbell').ownership_guard_probe.countDocuments({}));
'@
        [int]$proof | Should -Be 1
    }
}
