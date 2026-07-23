BeforeAll {
    $moduleRoot = Join-Path $PSScriptRoot '..\modules'
    $serviceRoot = Join-Path $PSScriptRoot '..\service'
    Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
    Import-Module (Join-Path $moduleRoot 'Production.SharedFolderWorker.psm1') -Force
    Import-Module (Join-Path $moduleRoot 'Production.SharedFolder.psm1') -Force

    function New-TestMediaJobFixture {
    param(
        [Parameter(Mandatory)][string]$Root,
        [string]$Profile = 'VIDEO_MP4'
    )

    $sharedRoot = Join-Path $Root 'Shared'
    $systemRoot = Join-Path $Root 'Shared-System'
    $directories = Get-SharedFolderRuntimePaths -SharedRoot $sharedRoot -SystemRoot $systemRoot
    foreach ($path in @(
        $directories.SharedRoot,
        $directories.SystemRoot,
        $directories.JobRoot,
        $directories.StagingRoot,
        $directories.PartialRoot,
        $directories.CacheRoot,
        $directories.StatusRoot,
        $directories.CancellationRoot
    )) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }

    $source = Join-Path $sharedRoot 'clip source.mkv'
    [IO.File]::WriteAllBytes($source, [byte[]](1..32))
    $sourceItem = Get-Item -LiteralPath $source
    $jobId = 'job_123'
    $cacheId = 'a' * 64
    $extension = if ($Profile -eq 'AUDIO_M4A') { 'm4a' } else { 'mp4' }
    $jobPath = Join-Path $directories.JobRoot "$jobId.json"
    $job = [ordered]@{
        schemaVersion = 1
        jobId = $jobId
        cacheId = $cacheId
        sourcePath = $sourceItem.FullName
        partialOutputPath = Join-Path $directories.PartialRoot "$jobId.$extension.part"
        readyOutputPath = Join-Path $directories.CacheRoot "$cacheId.$extension"
        statusPath = Join-Path $directories.StatusRoot "$jobId.json"
        cancellationPath = Join-Path $directories.CancellationRoot "$jobId.cancel"
        sourceSize = [long]$sourceItem.Length
        sourceModifiedAt = $sourceItem.LastWriteTimeUtc.ToString('o')
        profile = $Profile
        deadline = [DateTime]::UtcNow.AddMinutes(10).ToString('o')
        maxOutputBytes = 1048576
        initialBufferBytes = 65536
    }
    $job | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $jobPath -Encoding utf8
        return [pscustomobject]@{
            Job = $job
            JobPath = $jobPath
            SharedRoot = $sharedRoot
            SystemRoot = $systemRoot
            Paths = $directories
        }
    }

    function New-TestMediaToolSet {
        param([Parameter(Mandatory)][string]$Root)
        New-Item -ItemType Directory -Path $Root -Force | Out-Null
        $ffmpeg = Join-Path $Root 'ffmpeg.exe'
        $ffprobe = Join-Path $Root 'ffprobe.exe'
        'test-ffmpeg' | Set-Content -LiteralPath $ffmpeg
        'test-ffprobe' | Set-Content -LiteralPath $ffprobe
        [pscustomobject]@{
            Ffmpeg = $ffmpeg
            Ffprobe = $ffprobe
            FfmpegSha256 = (Get-FileHash -LiteralPath $ffmpeg -Algorithm SHA256).Hash
            FfprobeSha256 = (Get-FileHash -LiteralPath $ffprobe -Algorithm SHA256).Hash
        }
    }

    function Get-TestPowerShellMediaToolSet {
        $executable = (Get-Command pwsh.exe).Source
        $hash = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash
        [pscustomobject]@{
            Ffmpeg = $executable
            Ffprobe = $executable
            FfmpegSha256 = $hash
            FfprobeSha256 = $hash
        }
    }

    function ConvertTo-TestEncodedCommand {
        param([Parameter(Mandatory)][string]$Script)
        [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
    }

    function Set-TestMediaSourceBytes {
        param(
            [Parameter(Mandatory)]$Fixture,
            [Parameter(Mandatory)][int]$Length
        )
        $bytes = [byte[]]::new($Length)
        for ($index = 0; $index -lt $bytes.Length; $index++) {
            $bytes[$index] = $index % 251
        }
        [IO.File]::WriteAllBytes($Fixture.Job.sourcePath, $bytes)
        $source = Get-Item -LiteralPath $Fixture.Job.sourcePath
        $Fixture.Job.sourceSize = [long]$source.Length
        $Fixture.Job.sourceModifiedAt = $source.LastWriteTimeUtc.ToString('o')
        $Fixture.Job | ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath $Fixture.JobPath -Encoding utf8
    }

    function New-TestMediaArchive {
        param(
            [Parameter(Mandatory)][string]$Path,
            [Parameter(Mandatory)][string]$Label
        )
        Add-Type -AssemblyName System.IO.Compression
        $archive = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in 'ffmpeg.exe','ffprobe.exe') {
                $entry = $archive.CreateEntry("package/bin/$name")
                $writer = [IO.StreamWriter]::new($entry.Open())
                try { $writer.Write("$Label-$name") } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }

    function New-TestMediaManifest {
        param(
            [Parameter(Mandatory)][string]$Path,
            [Parameter(Mandatory)][string]$ArchivePath,
            [Parameter(Mandatory)][string]$Version
        )
        [ordered]@{
            schemaVersion = 1
            packageVersion = $Version
            uri = 'https://example.invalid/media-tools.zip'
            sha256 = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
        } | ConvertTo-Json | Set-Content -LiteralPath $Path
    }
}

Describe 'shared-folder media worker service definition' {
    It 'uses LocalService, below-normal priority, delayed automatic startup, recovery, and bounded logs' {
        [xml]$service = Get-Content (Join-Path $serviceRoot 'ChristopherBellMediaWorker.xml') -Raw

        [string]$service.service.id | Should -Be 'ChristopherBellMediaWorker'
        [string]$service.service.serviceaccount.username | Should -Be 'NT AUTHORITY\LocalService'
        [string]$service.service.priority | Should -Be 'belownormal'
        [string]$service.service.startmode | Should -Be 'Automatic'
        [string]$service.service.delayedAutoStart | Should -Be 'true'
        @($service.service.onfailure).Count | Should -BeGreaterThan 0
        [string]$service.service.log.mode | Should -Be 'roll-by-size'
        [int]$service.service.log.sizeThreshold | Should -Be 10240
        [int]$service.service.log.keepFiles | Should -Be 14
    }
}

Describe 'shared-folder media job boundary validation' {
    It 'accepts the exact Task 6 descriptor schema' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive

        $result = Read-ValidatedMediaJob -Path $fixture.JobPath `
            -SharedRoot $fixture.SharedRoot -SystemRoot $fixture.SystemRoot

        $result.jobId | Should -Be 'job_123'
        $result.profile | Should -Be 'VIDEO_MP4'
    }

    It 'rejects an unknown schema version' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.schemaVersion = 2
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*schemaVersion*'
    }

    It 'rejects an unknown output profile' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.profile = 'COPY_CLIENT_FLAGS'
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*profile*'
    }

    It 'rejects extra descriptor fields' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job['arguments'] = '-f concat -i attacker.txt'
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*fields*'
    }

    It 'rejects expired deadlines' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.deadline = [DateTime]::UtcNow.AddSeconds(-1).ToString('o')
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*deadline*'
    }

    It 'rejects a deadline beyond the fixed two-hour worker horizon' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.deadline = [DateTime]::UtcNow.AddHours(3).ToString('o')
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*two-hour*'
    }

    It 'rejects an output ceiling above the Task 6 fifty-gigabyte contract' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.maxOutputBytes = 53687091201
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*maxOutputBytes*'
    }

    It 'rejects a source above the fixed ten-gigabyte transcode ceiling' {
        { Assert-MediaSourceSizeAllowed -SourceSizeBytes (10GB + 1) } |
            Should -Throw '*ten-gigabyte*'
    }

    It 'rejects a sourceSize outside signed 64-bit arithmetic without overflowing' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'source-overflow')
        $fixture.Job.sourceSize = [decimal]::Parse('9223372036854775808')
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw 'sourceSize must be an integer.'
    }

    It 'rejects an initial buffer above the Task 6 two-megabyte contract' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.maxOutputBytes = 4194304
        $fixture.Job.initialBufferBytes = 2097153
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*initialBufferBytes*'
    }

    It 'rejects an initial buffer larger than the requested output ceiling' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.maxOutputBytes = 1024
        $fixture.Job.initialBufferBytes = 2048
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*must not exceed*'
    }

    It 'rejects traversal outside the visible root' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $outside = Join-Path $TestDrive 'outside.mkv'
        'outside' | Set-Content $outside
        $outsideItem = Get-Item $outside
        $fixture.Job.sourcePath = $outsideItem.FullName
        $fixture.Job.sourceSize = $outsideItem.Length
        $fixture.Job.sourceModifiedAt = $outsideItem.LastWriteTimeUtc.ToString('o')
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*sourcePath*'
    }

    It 'rejects altered source size' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.sourceSize++
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*size*'
    }

    It 'rejects altered source modification time' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.sourceModifiedAt = [DateTime]::UtcNow.AddDays(-1).ToString('o')
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*modified*'
    }

    It 'rejects a source reached through a reparse point' -Skip:(-not $IsWindows) {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $outside = Join-Path $TestDrive 'outside-directory'
        New-Item -ItemType Directory -Path $outside | Out-Null
        'outside' | Set-Content (Join-Path $outside 'clip.mkv')
        $link = Join-Path $fixture.SharedRoot 'linked'
        New-Item -ItemType Junction -Path $link -Target $outside | Out-Null
        $source = Get-Item (Join-Path $link 'clip.mkv')
        $fixture.Job.sourcePath = $source.FullName
        $fixture.Job.sourceSize = $source.Length
        $fixture.Job.sourceModifiedAt = $source.LastWriteTimeUtc.ToString('o')
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath

        { Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot } |
            Should -Throw '*reparse*'
    }

    It 'rejects a duplicate active worker lock' {
        $lockPath = Join-Path $TestDrive 'worker.lock'
        $first = Enter-SharedFolderWorkerLock -Path $lockPath
        try {
            { Enter-SharedFolderWorkerLock -Path $lockPath } | Should -Throw '*active*'
        } finally {
            $first.Dispose()
        }
    }

    It 'returns nonzero from validate-only mode for a bad path' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $fixture.Job.readyOutputPath = Join-Path $TestDrive 'escaped.mp4'
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath
        $worker = Join-Path $serviceRoot 'Start-SharedFolderMediaWorker.ps1'

        & pwsh.exe -NoLogo -NoProfile -File $worker -ValidateOnly -JobPath $fixture.JobPath `
            -SharedRoot $fixture.SharedRoot -SystemRoot $fixture.SystemRoot 2>$null

        $LASTEXITCODE | Should -Not -Be 0
    }
}

Describe 'fixed media tool arguments' {
    It 'constructs a fixed fragmented H.264/AAC MP4 profile without client flags' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot

        $arguments = New-FixedFfmpegArguments -Job $job

        ($arguments -join ' ') | Should -Match 'libx264'
        ($arguments -join ' ') | Should -Match 'aac'
        ($arguments -join ' ') | Should -Match 'frag_keyframe\+empty_moov\+default_base_moof'
        $arguments | Should -Contain '-fs'
        $arguments[([array]::IndexOf($arguments, '-fs') + 1)] | Should -Be ([string]$job.maxOutputBytes)
        $arguments[-1] | Should -Be $job.partialOutputPath
        $arguments | Should -Not -Contain '-f concat -i attacker.txt'
    }

    It 'constructs a fixed AAC M4A profile' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive -Profile AUDIO_M4A
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot

        $arguments = New-FixedFfmpegArguments -Job $job

        $arguments | Should -Contain '-vn'
        $arguments | Should -Contain 'aac'
        $arguments[-1] | Should -Be $job.partialOutputPath
    }

    It 'uses a bounded JSON-only ffprobe contract' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot

        $arguments = New-FixedFfprobeArguments -Job $job

        $arguments | Should -Contain 'json'
        $arguments | Should -Contain '-show_streams'
        $arguments | Should -Contain '-show_format'
        $arguments[-1] | Should -Be $job.sourcePath
    }

    It 'launches through centralized argument escaping, bounds logs, and kills a process tree' {
        $module = Get-Content (Join-Path $moduleRoot 'Production.SharedFolderWorker.psm1') -Raw

        $module | Should -Match 'New-ProductionProcessStartInfo'
        $module | Should -Match '-ArgumentList \$ArgumentList'
        $module | Should -Match 'BoundedTextReader'
        $module | Should -Match '\.Kill\(\$true\)'
        $module | Should -Not -Match 'ProcessStartInfo]::new'
    }

    It 'captures child output through the bounded process runner' {
        $pwsh = (Get-Command pwsh.exe).Source

        $result = Invoke-PinnedMediaTool -Executable $pwsh `
            -ArgumentList @('-NoLogo','-NoProfile','-Command',"[Console]::Out.WriteLine('x' * 5000)") `
            -Deadline ([DateTimeOffset]::UtcNow.AddSeconds(10)) -MaxLogCharacters 1024

        $result.ExitCode | Should -Be 0
        $result.StandardOutput.Length | Should -Be 1024
        $result.OutputTruncated | Should -BeTrue
    }

    It 'kills the child process tree when the deadline expires' {
        $pwsh = (Get-Command pwsh.exe).Source

        { Invoke-PinnedMediaTool -Executable $pwsh `
            -ArgumentList @('-NoLogo','-NoProfile','-Command','Start-Sleep -Seconds 10') `
            -Deadline ([DateTimeOffset]::UtcNow.AddMilliseconds(100)) } |
            Should -Throw '*deadline*'
    }

    It 'does not start a child when cancellation already exists' {
        $marker = Join-Path $TestDrive 'pre-canceled-child-started.txt'
        $cancellation = Join-Path $TestDrive 'pre-canceled.cancel'
        Set-Content -LiteralPath $cancellation -Value canceled
        $encoded = ConvertTo-TestEncodedCommand -Script @"
[IO.File]::WriteAllText('$($marker.Replace("'", "''"))', 'started')
[Threading.Thread]::Sleep(30000)
"@

        { Invoke-PinnedMediaTool -Executable (Get-Command pwsh.exe).Source `
            -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$encoded) `
            -Deadline ([DateTimeOffset]::UtcNow.AddMinutes(1)) `
            -CancellationPath $cancellation } |
            Should -Throw '*canceled*'
        Test-Path -LiteralPath $marker | Should -BeFalse
    }

    It 'does not start a child when the deadline is already expired' {
        $marker = Join-Path $TestDrive 'expired-child-started.txt'
        $encoded = ConvertTo-TestEncodedCommand -Script @"
[IO.File]::WriteAllText('$($marker.Replace("'", "''"))', 'started')
[Threading.Thread]::Sleep(30000)
"@

        { Invoke-PinnedMediaTool -Executable (Get-Command pwsh.exe).Source `
            -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$encoded) `
            -Deadline ([DateTimeOffset]::UtcNow.AddMilliseconds(-1)) } |
            Should -Throw '*deadline*'
        Test-Path -LiteralPath $marker | Should -BeFalse
    }

    It 'publishes the exact bounded status schema with atomic replacement' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot

        Write-MediaJobStatusAtomic -Job $job -Status BUFFERING -OutputBytes 123
        $status = Get-Content $job.statusPath -Raw | ConvertFrom-Json

        @($status.PSObject.Properties.Name | Sort-Object) |
            Should -Be @('failureCategory','jobId','outputBytes','schemaVersion','status')
        $status.schemaVersion | Should -Be 1
        $status.jobId | Should -Be $job.jobId
        $status.status | Should -Be 'BUFFERING'
        $status.outputBytes | Should -Be 123
        $status.failureCategory | Should -BeNullOrEmpty
        @(Get-ChildItem -LiteralPath $fixture.Paths.StatusRoot -Filter '*.tmp').Count | Should -Be 0
    }
}

Describe 'retained source and private publication ownership' {
    It 'stages bytes from the retained source handle and prevents source substitution' -Skip:(-not $IsWindows) {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        New-Item -ItemType Directory -Path (Join-Path $fixture.SystemRoot 'shared-folder-media-staging') -Force |
            Out-Null
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $owned = Open-ValidatedMediaSource -Job $job
        try {
            { Move-Item -LiteralPath $job.sourcePath -Destination "$($job.sourcePath).old" -ErrorAction Stop } |
                Should -Throw
            $staged = Copy-ValidatedMediaSourceToStage -Job $job -OwnedSource $owned
            try {
                [IO.File]::ReadAllBytes($staged.Path) | Should -Be ([byte[]](1..32))
                { Assert-StagedMediaSourceUnchanged -Job $job -StagedSource $staged } |
                    Should -Not -Throw
                $job.sourceSize++
                { Assert-StagedMediaSourceUnchanged -Job $job -StagedSource $staged } |
                    Should -Throw '*identity*'
            } finally { $staged.Dispose() }
        } finally { $owned.Dispose() }
    }

    It 'holds private output ancestors against coordinated substitution' -Skip:(-not $IsWindows) {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        New-Item -ItemType Directory -Path (Join-Path $fixture.SystemRoot 'shared-folder-media-staging') -Force |
            Out-Null
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $lease = Enter-MediaPrivateRootLease -Job $job
        try {
            { Move-Item -LiteralPath $fixture.Paths.PartialRoot `
                -Destination "$($fixture.Paths.PartialRoot)-swapped" -ErrorAction Stop } | Should -Throw
        } finally { $lease.Dispose() }
    }

    It 'rejects a reparse output ancestor at the last publication boundary' -Skip:(-not $IsWindows) {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        [IO.File]::WriteAllBytes($job.partialOutputPath, [byte[]](1..16))
        $outside = Join-Path $TestDrive 'outside-ready'
        New-Item -ItemType Directory -Path $outside | Out-Null
        Move-Item -LiteralPath $fixture.Paths.CacheRoot -Destination "$($fixture.Paths.CacheRoot)-original"
        New-Item -ItemType Junction -Path $fixture.Paths.CacheRoot -Target $outside | Out-Null

        { Complete-MediaJobAtomically -Job $job } | Should -Throw '*reparse*'
        Test-Path -LiteralPath (Join-Path $outside ([IO.Path]::GetFileName($job.readyOutputPath))) |
            Should -BeFalse
    }
}

Describe 'media job effect boundaries' {
    It 'requires the fixed free-space reserve in addition to the output ceiling' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot

        { Assert-MediaOutputCapacity -Job $job `
            -AvailableFreeSpaceBytes (100GB + $job.sourceSize + $job.maxOutputBytes - 1) } |
            Should -Throw '*reserve*'
    }

    It 'rejects aggregate capacity arithmetic that would overflow' {
        { Get-RequiredMediaCapacityBytes -SourceSizeBytes ([long]::MaxValue) `
            -MaxOutputBytes 1 } | Should -Throw '*overflow*'
    }

    It 'represents staging and output as one private-store capacity domain' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'same-store')
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot

        Assert-MediaJobUsesSinglePrivateStore -Job $job | Should -Be $job.systemRoot
    }

    It 'cancels a bounded staged copy between chunks and removes partial staged bytes' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'stage-cancel')
        Set-TestMediaSourceBytes -Fixture $fixture -Length 196608
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $owned = Open-ValidatedMediaSource -Job $job
        $state = @{ Checks = 0 }
        $clock = {
            $state.Checks++
            if ($state.Checks -eq 3) {
                [IO.File]::WriteAllText($job.cancellationPath, 'cancel')
            }
            [DateTimeOffset]::UtcNow
        }.GetNewClosure()
        try {
            { Copy-ValidatedMediaSourceToStage -Job $job -OwnedSource $owned `
                -GetNowUtc $clock -GetAvailableFreeSpace { param($path) 200GB } } |
                Should -Throw '*canceled*'
        } finally { $owned.Dispose() }

        Test-Path -LiteralPath (Join-Path $job.stagingRoot "$($job.jobId).source") |
            Should -BeFalse
    }

    It 'times out a bounded staged copy between chunks and removes partial staged bytes' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'stage-timeout')
        Set-TestMediaSourceBytes -Fixture $fixture -Length 196608
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $owned = Open-ValidatedMediaSource -Job $job
        $state = @{ Checks = 0 }
        $clock = {
            $state.Checks++
            if ($state.Checks -lt 3) { [DateTimeOffset]::UtcNow } else { $job.deadline.AddTicks(1) }
        }.GetNewClosure()
        try {
            { Copy-ValidatedMediaSourceToStage -Job $job -OwnedSource $owned `
                -GetNowUtc $clock -GetAvailableFreeSpace { param($path) 200GB } } |
                Should -Throw '*deadline*'
        } finally { $owned.Dispose() }

        Test-Path -LiteralPath (Join-Path $job.stagingRoot "$($job.jobId).source") |
            Should -BeFalse
    }

    It 'fails a staged copy when the remaining same-store reserve is consumed' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'stage-space')
        Set-TestMediaSourceBytes -Fixture $fixture -Length 65536
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $owned = Open-ValidatedMediaSource -Job $job
        $state = @{ Queries = 0 }
        $space = {
            param($path)
            $state.Queries++
            if ($state.Queries -eq 1) { 200GB } else { 100GB + $job.maxOutputBytes - 1 }
        }.GetNewClosure()
        try {
            { Copy-ValidatedMediaSourceToStage -Job $job -OwnedSource $owned `
                -GetAvailableFreeSpace $space } | Should -Throw '*reserve*'
        } finally { $owned.Dispose() }

        Test-Path -LiteralPath (Join-Path $job.stagingRoot "$($job.jobId).source") |
            Should -BeFalse
    }

    It 'revalidates exact tool hashes immediately before the probe launch boundary' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        New-Item -ItemType Directory -Path $fixture.Paths.StagingRoot -Force | Out-Null
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $tools = New-TestMediaToolSet -Root (Join-Path $TestDrive 'changed-launch-tools')
        'changed' | Set-Content -LiteralPath $tools.Ffprobe
        $called = @{ Probe = $false }

        { Invoke-ValidatedMediaJob -Job $job -ToolSet $tools `
            -GetAvailableFreeSpace { param($path) 200GB } `
            -ProbeAction { param($context) $called.Probe = $true } `
            -TranscodeAction { param($context) } } |
            Should -Throw '*hash verification*'
        $called.Probe | Should -BeFalse
    }

    It 'cleans staged and partial files after cancellation at the transcode boundary' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        New-Item -ItemType Directory -Path (Join-Path $fixture.SystemRoot 'shared-folder-media-staging') -Force |
            Out-Null
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $tools = New-TestMediaToolSet -Root (Join-Path $TestDrive 'cancel-tools')
        $probe = { param($context) [pscustomobject]@{ StandardOutput='{}'; OutputTruncated=$false } }
        $transcode = {
            param($context)
            [IO.File]::WriteAllBytes($context.Job.partialOutputPath, [byte[]](1..16))
            throw [OperationCanceledException]::new('coordinated cancellation')
        }

        { Invoke-ValidatedMediaJob -Job $job -ToolSet $tools `
            -GetAvailableFreeSpace { param($path) 200GB } `
            -ProbeAction $probe -TranscodeAction $transcode } |
            Should -Throw '*cancellation*'
        Test-Path -LiteralPath $job.partialOutputPath | Should -BeFalse
        @(Get-ChildItem -LiteralPath (Join-Path $fixture.SystemRoot 'shared-folder-media-staging') -File).Count |
            Should -Be 0
    }

    It 'fails closed and cleans up when a transcode rapidly overshoots the output ceiling' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        New-Item -ItemType Directory -Path (Join-Path $fixture.SystemRoot 'shared-folder-media-staging') -Force |
            Out-Null
        $fixture.Job.maxOutputBytes = 8
        $fixture.Job.initialBufferBytes = 4
        $fixture.Job | ConvertTo-Json | Set-Content $fixture.JobPath
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $tools = New-TestMediaToolSet -Root (Join-Path $TestDrive 'overshoot-tools')
        $probe = { param($context) [pscustomobject]@{ StandardOutput='{}'; OutputTruncated=$false } }
        $transcode = {
            param($context)
            [IO.File]::WriteAllBytes($context.Job.partialOutputPath, [byte[]](1..9))
            & $context.PollAction
        }

        { Invoke-ValidatedMediaJob -Job $job -ToolSet $tools `
            -GetAvailableFreeSpace { param($path) 200GB } `
            -ProbeAction $probe -TranscodeAction $transcode } |
            Should -Throw '*maxOutputBytes*'
        Test-Path -LiteralPath $job.readyOutputPath | Should -BeFalse
        Test-Path -LiteralPath $job.partialOutputPath | Should -BeFalse
    }

    It 'publishes completed output and READY status through the real filesystem boundary' {
        $fixture = New-TestMediaJobFixture -Root $TestDrive
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        [IO.File]::WriteAllBytes($job.partialOutputPath, [byte[]](1..16))

        Complete-MediaJobAtomically -Job $job

        Test-Path -LiteralPath $job.partialOutputPath | Should -BeFalse
        [IO.File]::ReadAllBytes($job.readyOutputPath) | Should -Be ([byte[]](1..16))
        (Get-Content $job.statusPath -Raw | ConvertFrom-Json).status | Should -Be 'READY'
    }
}

Describe 'real controlled media child-process pipeline' -Skip:(-not $IsWindows) {
    It 'kills a real transcode process tree after post-start cancellation and cleans private bytes' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'real-cancel')
        Set-TestMediaSourceBytes -Fixture $fixture -Length 131072
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $tools = Get-TestPowerShellMediaToolSet
        $started = Join-Path $TestDrive 'real-cancel-transcode-started.txt'
        $childPidPath = Join-Path $TestDrive 'real-cancel-child.pid'
        $release = Join-Path $TestDrive 'real-cancel-release.txt'
        $pwsh = (Get-Command pwsh.exe).Source
        $childCommand = ConvertTo-TestEncodedCommand -Script @"
`$watcher = [IO.FileSystemWatcher]::new('$($TestDrive.Replace("'", "''"))', '$([IO.Path]::GetFileName($release))')
`$watcher.EnableRaisingEvents = `$true
try {
    if (-not (Test-Path -LiteralPath '$($release.Replace("'", "''"))')) {
        `$change = `$watcher.WaitForChanged([IO.WatcherChangeTypes]::Created, 30000)
        if (`$change.TimedOut) { exit 4 }
    }
} finally { `$watcher.Dispose() }
"@
        $transcodeCommand = ConvertTo-TestEncodedCommand -Script @"
`$child = Start-Process -FilePath '$($pwsh.Replace("'", "''"))' -WindowStyle Hidden -PassThru -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand','$childCommand')
[IO.File]::WriteAllText('$($childPidPath.Replace("'", "''"))', [string]`$child.Id)
[IO.File]::WriteAllBytes('$($job.partialOutputPath.Replace("'", "''"))', [byte[]](1..32))
[IO.File]::WriteAllText('$($started.Replace("'", "''"))', 'started')
[IO.File]::WriteAllText('$($job.cancellationPath.Replace("'", "''"))', 'cancel')
`$watcher = [IO.FileSystemWatcher]::new('$($TestDrive.Replace("'", "''"))', '$([IO.Path]::GetFileName($release))')
`$watcher.EnableRaisingEvents = `$true
try {
    if (-not (Test-Path -LiteralPath '$($release.Replace("'", "''"))')) {
        `$change = `$watcher.WaitForChanged([IO.WatcherChangeTypes]::Created, 30000)
        if (`$change.TimedOut) { exit 5 }
    }
} finally { `$watcher.Dispose() }
"@
        $probeCommand = ConvertTo-TestEncodedCommand -Script "[Console]::Out.Write('{}')"
        $probe = {
            param($context)
            Invoke-PinnedMediaTool -Executable $context.ToolSet.Ffprobe `
                -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$probeCommand) `
                -Deadline $context.Job.deadline -CancellationPath $context.Job.cancellationPath
        }.GetNewClosure()
        $transcode = {
            param($context)
            Invoke-PinnedMediaTool -Executable $context.ToolSet.Ffmpeg `
                -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$transcodeCommand) `
                -Deadline $context.Job.deadline -CancellationPath $context.Job.cancellationPath `
                -OnPoll $context.PollAction
        }.GetNewClosure()

        { Invoke-ValidatedMediaJob -Job $job -ToolSet $tools `
            -GetAvailableFreeSpace { param($path) 200GB } `
            -ProbeAction $probe -TranscodeAction $transcode } |
            Should -Throw '*canceled*'

        Test-Path -LiteralPath $started | Should -BeTrue
        Test-Path -LiteralPath $job.partialOutputPath | Should -BeFalse
        Test-Path -LiteralPath $job.readyOutputPath | Should -BeFalse
        Test-Path -LiteralPath (Join-Path $job.stagingRoot "$($job.jobId).source") |
            Should -BeFalse
        (Get-Content -LiteralPath $job.statusPath -Raw | ConvertFrom-Json).status |
            Should -Not -Be 'READY'
        $childProcessId = [int](Get-Content -LiteralPath $childPidPath -Raw)
        try {
            $child = [Diagnostics.Process]::GetProcessById($childProcessId)
            $child.WaitForExit(5000) | Should -BeTrue
            $child.HasExited | Should -BeTrue
        } catch [ArgumentException] {
            $true | Should -BeTrue
        }
    }

    It 'publishes only after a real bounded-output transcode process exits' {
        $fixture = New-TestMediaJobFixture -Root (Join-Path $TestDrive 'real-success')
        Set-TestMediaSourceBytes -Fixture $fixture -Length 131072
        $job = Read-ValidatedMediaJob $fixture.JobPath $fixture.SharedRoot $fixture.SystemRoot
        $tools = Get-TestPowerShellMediaToolSet
        $started = Join-Path $TestDrive 'real-success-transcode-started.txt'
        $release = Join-Path $TestDrive 'real-success-release.txt'
        $observation = Join-Path $TestDrive 'real-success-ready-observation.txt'
        $exitMarker = Join-Path $TestDrive 'real-success-exited.txt'
        $boundedResult = Join-Path $TestDrive 'real-success-bounded.json'
        $coordinatorCommand = ConvertTo-TestEncodedCommand -Script @"
`$deadline = [DateTime]::UtcNow.AddSeconds(15)
while (-not (Test-Path -LiteralPath '$($started.Replace("'", "''"))')) {
    if ([DateTime]::UtcNow -ge `$deadline) { exit 6 }
    Start-Sleep -Milliseconds 25
}
`$state = if (Test-Path -LiteralPath '$($job.readyOutputPath.Replace("'", "''"))') { 'present' } else { 'absent' }
[IO.File]::WriteAllText('$($observation.Replace("'", "''"))', `$state)
[IO.File]::WriteAllText('$($release.Replace("'", "''"))', 'release')
"@
        $transcodeCommand = ConvertTo-TestEncodedCommand -Script @"
[IO.File]::WriteAllBytes('$($job.partialOutputPath.Replace("'", "''"))', [byte[]](1..64))
[IO.File]::WriteAllText('$($started.Replace("'", "''"))', 'started')
`$deadline = [DateTime]::UtcNow.AddSeconds(15)
while (-not (Test-Path -LiteralPath '$($release.Replace("'", "''"))')) {
    if ([DateTime]::UtcNow -ge `$deadline) { exit 7 }
    Start-Sleep -Milliseconds 25
}
[Console]::Out.Write('x' * 200000)
[Console]::Error.Write('y' * 200000)
[IO.File]::WriteAllText('$($exitMarker.Replace("'", "''"))', 'exited')
"@
        $probeCommand = ConvertTo-TestEncodedCommand -Script "[Console]::Out.Write('{}')"
        $probe = {
            param($context)
            Invoke-PinnedMediaTool -Executable $context.ToolSet.Ffprobe `
                -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$probeCommand) `
                -Deadline $context.Job.deadline -CancellationPath $context.Job.cancellationPath
        }.GetNewClosure()
        $transcode = {
            param($context)
            $coordinator = Start-Process -FilePath $context.ToolSet.Ffmpeg -WindowStyle Hidden -PassThru `
                -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$coordinatorCommand)
            try {
                $result = Invoke-PinnedMediaTool -Executable $context.ToolSet.Ffmpeg `
                    -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$transcodeCommand) `
                    -Deadline $context.Job.deadline -CancellationPath $context.Job.cancellationPath `
                    -OnPoll $context.PollAction
                [ordered]@{
                    outputLength = $result.StandardOutput.Length
                    errorLength = $result.StandardError.Length
                    outputTruncated = $result.OutputTruncated
                    errorTruncated = $result.ErrorTruncated
                } | ConvertTo-Json | Set-Content -LiteralPath $boundedResult
            } finally {
                if (-not $coordinator.WaitForExit(5000)) {
                    $coordinator.Kill($true)
                    throw 'Success coordinator did not exit within its bounded wait.'
                }
                if ($coordinator.ExitCode -ne 0) {
                    throw "Success coordinator exited with code $($coordinator.ExitCode)."
                }
                $coordinator.Dispose()
            }
        }.GetNewClosure()

        Invoke-ValidatedMediaJob -Job $job -ToolSet $tools `
            -GetAvailableFreeSpace { param($path) 200GB } `
            -ProbeAction $probe -TranscodeAction $transcode

        Get-Content -LiteralPath $observation -Raw | Should -Be 'absent'
        Test-Path -LiteralPath $exitMarker | Should -BeTrue
        Test-Path -LiteralPath $job.readyOutputPath | Should -BeTrue
        (Get-Content -LiteralPath $job.statusPath -Raw | ConvertFrom-Json).status |
            Should -Be 'READY'
        $bounded = Get-Content -LiteralPath $boundedResult -Raw | ConvertFrom-Json
        $bounded.outputLength | Should -Be 65536
        $bounded.errorLength | Should -Be 65536
        $bounded.outputTruncated | Should -BeTrue
        $bounded.errorTruncated | Should -BeTrue
    }
}

Describe 'shared-folder runtime isolation' {
    It 'uses the exact default visible and private roots' {
        $paths = Get-SharedFolderRuntimePaths

        $paths.SharedRoot | Should -Be 'A:\Shared'
        $paths.SystemRoot | Should -Be 'A:\Shared-System'
        $paths.JobRoot | Should -Be 'A:\Shared-System\shared-folder-media-jobs'
        $paths.PartialRoot | Should -Be 'A:\Shared-System\shared-folder-media-partial'
        $paths.CacheRoot | Should -Be 'A:\Shared-System\shared-folder-media-cache'
        $paths.StatusRoot | Should -Be 'A:\Shared-System\shared-folder-media-status'
        $paths.CancellationRoot | Should -Be 'A:\Shared-System\shared-folder-media-cancel'
    }

    It 'builds an inherited-access-free visible-root ACL with SYSTEM full control and LocalService read only' {
        $acl = New-SharedFolderAcl -WorkerAccess ReadAndExecute
        $rules = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))
        $system = $rules | Where-Object IdentityReference -eq 'S-1-5-18'
        $worker = $rules | Where-Object IdentityReference -eq 'S-1-5-19'

        $acl.AreAccessRulesProtected | Should -BeTrue
        ($system.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) |
            Should -Be ([Security.AccessControl.FileSystemRights]::FullControl)
        ($worker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) |
            Should -Not -Be ([Security.AccessControl.FileSystemRights]::Modify)
        ($worker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadAndExecute) |
            Should -Be ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    }

    It 'builds worker-private ACLs with LocalService modify access' {
        $acl = New-SharedFolderAcl -WorkerAccess Modify
        $worker = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier])) |
            Where-Object IdentityReference -eq 'S-1-5-19'

        ($worker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) |
            Should -Be ([Security.AccessControl.FileSystemRights]::Modify)
    }

    It 'keeps LocalService out of protected production control files' {
        $acl = New-ProtectedProductionAcl
        $rules = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))

        $rules.IdentityReference.Value | Should -Not -Contain 'S-1-5-19'
        $rules.IdentityReference.Value | Should -Contain 'S-1-5-18'
    }

    It 'creates the private runtime directories idempotently without changing the requested roots' {
        $sharedRoot = Join-Path $TestDrive 'Shared'
        $systemRoot = Join-Path $TestDrive 'Shared-System'

        New-SharedFolderRuntimeDirectories -SharedRoot $sharedRoot -SystemRoot $systemRoot
        New-SharedFolderRuntimeDirectories -SharedRoot $sharedRoot -SystemRoot $systemRoot
        $paths = Get-SharedFolderRuntimePaths -SharedRoot $sharedRoot -SystemRoot $systemRoot

        foreach ($path in $paths.PSObject.Properties.Value) {
            Test-Path -LiteralPath $path -PathType Container | Should -BeTrue
        }
    }

    It 'applies the expected ACL objects through one effect seam' {
        $sharedRoot = Join-Path $TestDrive 'acl-shared'
        $systemRoot = Join-Path $TestDrive 'acl-system'
        $paths = New-SharedFolderRuntimeDirectories -SharedRoot $sharedRoot -SystemRoot $systemRoot
        $requests = [Collections.Generic.List[object]]::new()

        Set-SharedFolderRuntimeAcls -SharedRoot $sharedRoot -SystemRoot $systemRoot `
            -SetAclAction { param($path,$acl) $requests.Add([pscustomobject]@{Path=$path;Acl=$acl}) }

        $requests.Path | Should -Contain $paths.SharedRoot
        $requests.Path | Should -Contain $paths.StagingRoot
        $requests.Path | Should -Contain $paths.ToolRoot
        ($requests | Where-Object Path -eq $paths.SharedRoot).Acl.AreAccessRulesProtected |
            Should -BeTrue
    }

    It 'rejects archive traversal before extracting any outside entry' {
        Add-Type -AssemblyName System.IO.Compression
        $archivePath = Join-Path $TestDrive 'traversal.zip'
        $destination = Join-Path $TestDrive 'expanded'
        $outside = Join-Path $TestDrive 'escaped.txt'
        $archive = [IO.Compression.ZipFile]::Open($archivePath, [IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $archive.CreateEntry('../escaped.txt')
            $writer = [IO.StreamWriter]::new($entry.Open())
            try { $writer.Write('outside') } finally { $writer.Dispose() }
        } finally { $archive.Dispose() }

        { Expand-ValidatedMediaArchive -ArchivePath $archivePath -Destination $destination } |
            Should -Throw '*unsafe path*'
        Test-Path -LiteralPath $outside | Should -BeFalse
    }

    It 'extracts nested media tools under Windows PowerShell 5.1' {
        $archivePath = Join-Path $TestDrive 'legacy-media-tools.zip'
        $destination = Join-Path $TestDrive 'legacy-expanded'
        $probe = Join-Path $TestDrive 'legacy-archive-probe.ps1'
        New-TestMediaArchive -Path $archivePath -Label legacy
        @'
param(
    [Parameter(Mandatory)][string]$ModulePath,
    [Parameter(Mandatory)][string]$ArchivePath,
    [Parameter(Mandatory)][string]$Destination
)
$ErrorActionPreference = 'Stop'
Import-Module $ModulePath -Force
Expand-ValidatedMediaArchive -ArchivePath $ArchivePath -Destination $Destination
'@ | Set-Content -LiteralPath $probe
        $modulePath = (Resolve-Path (
            Join-Path $moduleRoot 'Production.SharedFolder.psm1')).Path

        & powershell.exe -NoProfile -File $probe `
            -ModulePath $modulePath `
            -ArchivePath $archivePath `
            -Destination $destination

        $LASTEXITCODE | Should -Be 0
        Join-Path $destination 'package\bin\ffmpeg.exe' |
            Should -Exist
        Join-Path $destination 'package\bin\ffprobe.exe' |
            Should -Exist
    }

    It 'rejects duplicate canonical archive paths before extracting files' {
        Add-Type -AssemblyName System.IO.Compression
        $archivePath = Join-Path $TestDrive 'duplicate-paths.zip'
        $destination = Join-Path $TestDrive 'duplicate-expanded'
        $archive = [IO.Compression.ZipFile]::Open(
            $archivePath,
            [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entryName in @(
                'package/bin/ffmpeg.exe',
                'PACKAGE/BIN/FFMPEG.EXE')) {
                $entry = $archive.CreateEntry($entryName)
                $writer = [IO.StreamWriter]::new($entry.Open())
                try { $writer.Write($entryName) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }

        { Expand-ValidatedMediaArchive `
                -ArchivePath $archivePath `
                -Destination $destination } |
            Should -Throw '*duplicate path*'
        Test-Path -LiteralPath (
            Join-Path $destination 'package\bin\ffmpeg.exe') |
            Should -BeFalse
    }

    It 'rejects a reparse-point archive destination before extracting files' {
        $archivePath = Join-Path $TestDrive 'reparse-destination.zip'
        $actualDestination = Join-Path $TestDrive 'actual-expanded'
        $linkedDestination = Join-Path $TestDrive 'linked-expanded'
        New-TestMediaArchive -Path $archivePath -Label reparse
        New-Item -ItemType Directory -Path $actualDestination | Out-Null
        New-Item -ItemType Junction `
            -Path $linkedDestination `
            -Target $actualDestination | Out-Null

        { Expand-ValidatedMediaArchive `
                -ArchivePath $archivePath `
                -Destination $linkedDestination } |
            Should -Throw '*reparse point*'
        Test-Path -LiteralPath (
            Join-Path $actualDestination 'package\bin\ffmpeg.exe') |
            Should -BeFalse
    }

    It 'reads the exact pinned HTTPS media tool manifest' {
        $manifestPath = Join-Path $PSScriptRoot '..\config\media-tools-manifest.json'

        $manifest = Read-PinnedMediaToolManifest -Path $manifestPath

        $manifest.schemaVersion | Should -Be 1
        $manifest.packageVersion | Should -Be '8.0.1-essentials'
        $manifest.uri | Should -Match '^https://'
        $manifest.sha256 | Should -Match '^[A-F0-9]{64}$'
    }

    It 'reuses an already verified media tool installation without downloading' {
        $toolRoot = Join-Path $TestDrive 'media-tools'
        $archive = Join-Path $TestDrive 'reused-tools.zip'
        $manifestPath = Join-Path $TestDrive 'reused-tools.json'
        New-TestMediaArchive -Path $archive -Label reused
        New-TestMediaManifest -Path $manifestPath -ArchivePath $archive -Version reused
        $first = Install-PinnedMediaTools -ManifestPath $manifestPath -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive $destination }

        $installed = Install-PinnedMediaTools -ManifestPath $manifestPath -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) throw 'Media tools should not be downloaded again.' }

        $installed.Ffmpeg | Should -Be $first.Ffmpeg
        $installed.Ffmpeg | Should -Match '[\\/]versions[\\/]'
    }

    It 'rejects a media tool changed after pinned installation' {
        $toolRoot = Join-Path $TestDrive 'pinned-media-tools'
        $archive = Join-Path $TestDrive 'pinned-tools.zip'
        $manifestPath = Join-Path $TestDrive 'pinned-tools.json'
        New-TestMediaArchive -Path $archive -Label pinned
        New-TestMediaManifest -Path $manifestPath -ArchivePath $archive -Version pinned
        $active = Install-PinnedMediaTools -ManifestPath $manifestPath -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive $destination }
        Assert-PinnedMediaToolSet -ToolRoot $toolRoot | Should -Not -BeNullOrEmpty
        'changed' | Set-Content $active.Ffmpeg

        { Assert-PinnedMediaToolSet -ToolRoot $toolRoot } | Should -Throw '*hash verification*'
    }

    It 'grants LocalService non-inheriting service-directory traversal before WinSW install' {
        $productionRoot = Join-Path $TestDrive 'worker-service-traversal'
        $serviceRoot = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $serviceRoot -Force | Out-Null
        'winsw' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe')
        'website xml' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.xml')
        'website script' | Set-Content (Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1')
        $requests = [Collections.Generic.List[object]]::new()
        $events = [Collections.Generic.List[string]]::new()
        $state = @{ Existing = $false }

        Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction {
                param($name)
                if ($state.Existing) {
                    [pscustomobject]@{ Name = $name; Status = 'Stopped' }
                }
            } `
            -InvokeWinSwAction {
                param($binary, $command)
                $events.Add($command)
                if ($command -eq 'install') { $state.Existing = $true }
                0
            } `
            -WaitForServicePresenceAction {
                param($name, $shouldExist, $timeoutSeconds)
                $state.Existing | Should -Be $shouldExist
            } `
            -StopServiceAction { param($name) } `
            -SetServiceIdentityAction { param($name, $identity) } `
            -GetServiceIdentityAction { param($name) 'NT AUTHORITY\LocalService' } `
            -ProtectPathAction { param($path) } `
            -SetAclAction {
                param($path, $acl)
                $requests.Add([pscustomobject]@{ Path = $path; Acl = $acl })
                $events.Add("acl:$([IO.Path]::GetFileName($path))")
            }

        $serviceRootRequests = @($requests | Where-Object Path -eq $serviceRoot)
        $serviceRootRequests.Count | Should -Be 1
        $acl = $serviceRootRequests[0].Acl
        $acl.AreAccessRulesProtected | Should -BeTrue
        $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value |
            Should -Be 'S-1-5-32-544'
        $rules = @($acl.GetAccessRules(
            $true, $false, [Security.Principal.SecurityIdentifier]))
        $worker = $rules | Where-Object IdentityReference -eq 'S-1-5-19'
        ($worker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadAndExecute) |
            Should -Be ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
        ($worker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) |
            Should -Not -Be ([Security.AccessControl.FileSystemRights]::Modify)
        $worker.InheritanceFlags |
            Should -Be ([Security.AccessControl.InheritanceFlags]::None)
        $worker.PropagationFlags |
            Should -Be ([Security.AccessControl.PropagationFlags]::None)
        $events.IndexOf('acl:service') | Should -BeLessThan ($events.IndexOf('install'))
    }

    It 'installs and reinstalls the worker through supported WinSW 2 commands' {
        $productionRoot = Join-Path $TestDrive 'service-runtime'
        $serviceRoot = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $serviceRoot -Force | Out-Null
        'winsw' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe')
        'website xml' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.xml')
        'website script' | Set-Content (Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1')
        $commands = [Collections.Generic.List[string]]::new()
        $waits = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()
        $identities = [Collections.Generic.List[string]]::new()
        $state = @{ Existing = $false; Status = 'Missing' }
        $getService = {
            param($name)
            if ($state.Existing) { [pscustomobject]@{ Name = $name } }
        }
        $invoke = {
            param($binary, $command)
            $commands.Add($command)
            $events.Add($command)
            if ($command -eq 'install') {
                $state.Existing = $true
                $state.Status = 'Stopped'
            } elseif ($command -eq 'uninstall') {
                $state.Existing = $false
                $state.Status = 'Missing'
            }
            0
        }
        $wait = {
            param($name, $shouldExist, $timeoutSeconds)
            $label = if ($shouldExist) { 'wait-present' } else { 'wait-absent' }
            $events.Add($label)
            $waits.Add("${name}=${shouldExist}:$timeoutSeconds")
            $state.Existing | Should -Be $shouldExist
        }
        $stopService = {
            param($name)
            $events.Add('stop')
            if ($state.Existing) { $state.Status = 'Stopped' }
        }
        $setIdentity = {
            param($name, $identity)
            $events.Add('set-identity')
            $identities.Add("$name=$identity")
        }
        $getIdentity = {
            param($name)
            $events.Add('get-identity')
            'NT AUTHORITY\LocalService'
        }

        $arguments = @{
            ProductionRoot = $productionRoot
            GetServiceAction = $getService
            InvokeWinSwAction = $invoke
            WaitForServicePresenceAction = $wait
            StopServiceAction = $stopService
            SetServiceIdentityAction = $setIdentity
            GetServiceIdentityAction = $getIdentity
            ProtectPathAction = { param($path) }
            SetAclAction = { param($path, $acl) }
        }
        Install-SharedFolderWorkerService @arguments
        $state.Status = 'Running'
        Install-SharedFolderWorkerService @arguments

        $commands | Should -Be @('install','uninstall','install')
        $waits | Should -Be @(
            'ChristopherBellMediaWorker=True:30',
            'ChristopherBellMediaWorker=False:30',
            'ChristopherBellMediaWorker=True:30')
        $events | Should -Be @(
            'install','wait-present','stop','set-identity','get-identity',
            'stop','uninstall','wait-absent','install','wait-present','stop',
            'set-identity','get-identity')
        $identities | Should -Be @(
            'ChristopherBellMediaWorker=NT AUTHORITY\LocalService',
            'ChristopherBellMediaWorker=NT AUTHORITY\LocalService')
        $state.Existing | Should -BeTrue
        $state.Status | Should -Be 'Stopped'
    }

    It 'fails before effects when the initial service query fails' {
        $productionRoot = Join-Path $TestDrive 'worker-initial-query-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $effects = [Collections.Generic.List[string]]::new()
        Mock Get-Service -ModuleName Production.SharedFolder {
            throw 'simulated initial service query failure'
        }

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -InvokeWinSwAction { param($binary, $command) $effects.Add($command); 0 } `
            -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) $effects.Add('wait') } `
            -StopServiceAction { param($name) $effects.Add('stop') } `
            -SetServiceIdentityAction { param($name, $identity) $effects.Add('set-identity') } `
            -GetServiceIdentityAction { param($name) $effects.Add('get-identity'); 'NT AUTHORITY\LocalService' } `
            -ProtectPathAction { param($path) $effects.Add('protect') } `
            -SetAclAction { param($path, $acl) $effects.Add('set-acl') } } |
            Should -Throw '*simulated initial service query failure*'

        $effects.Count | Should -Be 0
        Should -Invoke Get-Service -ModuleName Production.SharedFolder -Times 1 -Exactly `
            -ParameterFilter { $ErrorAction -eq 'Stop' }
    }

    It 'does not reinstall when the absence wait service query fails' {
        $productionRoot = Join-Path $TestDrive 'worker-absence-query-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $queryState = @{ Count = 0 }
        $commands = [Collections.Generic.List[string]]::new()
        $identityEffects = [Collections.Generic.List[string]]::new()
        $getService = {
            param($name)
            $queryState.Count++
            if ($queryState.Count -eq 1) { return [pscustomobject]@{ Name = $name } }
            throw 'simulated absence wait service query failure'
        }

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -ServiceWaitTimeoutSeconds 1 -GetServiceAction $getService `
            -InvokeWinSwAction { param($binary, $command) $commands.Add($command); 0 } `
            -StopServiceAction { param($name) } `
            -SetServiceIdentityAction { param($name, $identity) $identityEffects.Add('set') } `
            -GetServiceIdentityAction { param($name) $identityEffects.Add('get'); 'NT AUTHORITY\LocalService' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*simulated absence wait service query failure*'

        $queryState.Count | Should -Be 2
        $commands | Should -Be @('uninstall')
        $identityEffects.Count | Should -Be 0
    }

    It 'does not configure identity when the presence wait service query fails' {
        $productionRoot = Join-Path $TestDrive 'worker-presence-query-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $queryState = @{ Count = 0 }
        $commands = [Collections.Generic.List[string]]::new()
        $identityEffects = [Collections.Generic.List[string]]::new()
        $getService = {
            param($name)
            $queryState.Count++
            if ($queryState.Count -eq 1) { return $null }
            throw 'simulated presence wait service query failure'
        }

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -ServiceWaitTimeoutSeconds 1 -GetServiceAction $getService `
            -InvokeWinSwAction { param($binary, $command) $commands.Add($command); 0 } `
            -StopServiceAction { param($name) } `
            -SetServiceIdentityAction { param($name, $identity) $identityEffects.Add('set') } `
            -GetServiceIdentityAction { param($name) $identityEffects.Add('get'); 'NT AUTHORITY\LocalService' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*simulated presence wait service query failure*'

        $queryState.Count | Should -Be 2
        $commands | Should -Be @('install')
        $identityEffects.Count | Should -Be 0
    }

    It 'fails before file effects when the initial stop service query fails' {
        $productionRoot = Join-Path $TestDrive 'worker-initial-stop-query-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $queryState = @{ Count = 0 }
        $effects = [Collections.Generic.List[string]]::new()
        $getService = {
            param($name)
            $queryState.Count++
            if ($queryState.Count -eq 1) {
                return [pscustomobject]@{ Name = $name; Status = 'Running' }
            }
            if ($queryState.Count -eq 2) {
                throw 'simulated initial stop service query failure'
            }
            return $null
        }

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction $getService `
            -InvokeWinSwAction { param($binary, $command) $effects.Add($command); 0 } `
            -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) $effects.Add('wait') } `
            -SetServiceIdentityAction { param($name, $identity) $effects.Add('set-identity') } `
            -GetServiceIdentityAction { param($name) $effects.Add('get-identity'); 'NT AUTHORITY\LocalService' } `
            -ProtectPathAction { param($path) $effects.Add('protect') } `
            -SetAclAction { param($path, $acl) $effects.Add('set-acl') } } |
            Should -Throw '*simulated initial stop service query failure*'

        $queryState.Count | Should -Be 3
        $effects.Count | Should -Be 0
    }

    It 'preserves post-install and cleanup stop service query failures in order' {
        $productionRoot = Join-Path $TestDrive 'worker-post-install-stop-query-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $queryState = @{ Count = 0 }
        $commands = [Collections.Generic.List[string]]::new()
        $identityEffects = [Collections.Generic.List[string]]::new()
        $getService = {
            param($name)
            $queryState.Count++
            if ($queryState.Count -eq 1) { return $null }
            if ($queryState.Count -eq 2) {
                throw 'simulated post-install stop service query failure'
            }
            throw 'simulated cleanup stop service query failure'
        }
        $caught = $null

        try {
            Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
                -GetServiceAction $getService `
                -InvokeWinSwAction { param($binary, $command) $commands.Add($command); 0 } `
                -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) } `
                -SetServiceIdentityAction { param($name, $identity) $identityEffects.Add('set') } `
                -GetServiceIdentityAction { param($name) $identityEffects.Add('get'); 'NT AUTHORITY\LocalService' } `
                -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) }
        } catch {
            $caught = $_.Exception
        }

        $queryState.Count | Should -Be 3
        $commands | Should -Be @('install')
        $identityEffects.Count | Should -Be 0
        $caught | Should -BeOfType [System.AggregateException]
        $caught.InnerExceptions.Count | Should -Be 2
        $caught.InnerExceptions[0].Message |
            Should -Be 'simulated post-install stop service query failure'
        $caught.InnerExceptions[1].Message |
            Should -Be 'simulated cleanup stop service query failure'
    }

    It 'keeps an existing registration when worker file preparation fails' {
        $productionRoot = Join-Path $TestDrive 'worker-file-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        'website xml' | Set-Content (Join-Path $service 'ChristopherBellDev.xml')
        $state = @{ Existing = $true; Status = 'Running' }
        $commands = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()
        $stopService = {
            param($name)
            $events.Add('stop')
            $state.Status = 'Stopped'
        }
        $protectPath = {
            param($path)
            $events.Add('protect')
            $state.Existing | Should -BeTrue
            $state.Status | Should -Be 'Stopped'
            throw 'simulated file protection failure'
        }

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction { param($name) [pscustomobject]@{ Name = $name } } `
            -InvokeWinSwAction { param($binary, $command) $commands.Add($command); 0 } `
            -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) throw 'unexpected wait' } `
            -StopServiceAction $stopService `
            -SetServiceIdentityAction { param($name, $identity) throw 'unexpected identity change' } `
            -GetServiceIdentityAction { param($name) throw 'unexpected identity read' } `
            -ProtectPathAction $protectPath `
            -SetAclAction { param($path,$acl) } } |
            Should -Throw '*simulated file protection failure*'
        $events | Should -Be @('stop','protect','stop')
        $commands.Count | Should -Be 0
        $state.Existing | Should -BeTrue
        $state.Status | Should -Be 'Stopped'
    }

    It 'does not install when WinSW uninstall fails' {
        $productionRoot = Join-Path $TestDrive 'worker-uninstall-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $state = @{ Existing = $true; Status = 'Running' }
        $commands = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction { param($name) [pscustomobject]@{ Name = $name } } `
            -InvokeWinSwAction {
                param($binary, $command)
                $commands.Add($command)
                $events.Add($command)
                1
            } `
            -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) throw 'unexpected wait' } `
            -StopServiceAction {
                param($name)
                $events.Add('stop')
                $state.Status = 'Stopped'
            } `
            -SetServiceIdentityAction { param($name, $identity) throw 'unexpected identity change' } `
            -GetServiceIdentityAction { param($name) throw 'unexpected identity read' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*WinSW service uninstallation failed*'

        $commands | Should -Be @('uninstall')
        $events | Should -Be @('stop','uninstall','stop')
        $state.Existing | Should -BeTrue
        $state.Status | Should -Be 'Stopped'
    }

    It 'does not install when service disappearance times out' {
        $productionRoot = Join-Path $TestDrive 'worker-disappearance-timeout'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $state = @{ Existing = $true; Status = 'Running' }
        $commands = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction { param($name) [pscustomobject]@{ Name = $name } } `
            -InvokeWinSwAction {
                param($binary, $command)
                $commands.Add($command)
                $events.Add($command)
                if ($command -eq 'uninstall') {
                    $state.Existing = $false
                    $state.Status = 'Missing'
                }
                0
            } `
            -WaitForServicePresenceAction {
                param($name, $shouldExist, $timeoutSeconds)
                $events.Add('wait-absent')
                throw 'simulated disappearance timeout'
            } `
            -StopServiceAction { param($name) $events.Add('stop') } `
            -SetServiceIdentityAction { param($name, $identity) throw 'unexpected identity change' } `
            -GetServiceIdentityAction { param($name) throw 'unexpected identity read' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*simulated disappearance timeout*'

        $commands | Should -Be @('uninstall')
        $events | Should -Be @('stop','uninstall','wait-absent','stop')
        $state.Existing | Should -BeFalse
        $state.Status | Should -Be 'Missing'
    }

    It 'fails when WinSW install fails' {
        $productionRoot = Join-Path $TestDrive 'worker-install-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $state = @{ Existing = $false; Status = 'Missing' }
        $commands = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction { param($name) $null } `
            -InvokeWinSwAction {
                param($binary, $command)
                $commands.Add($command)
                $events.Add($command)
                1
            } `
            -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) throw 'unexpected wait' } `
            -StopServiceAction { param($name) $events.Add('stop') } `
            -SetServiceIdentityAction { param($name, $identity) throw 'unexpected identity change' } `
            -GetServiceIdentityAction { param($name) throw 'unexpected identity read' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*WinSW service installation failed*'

        $commands | Should -Be @('install')
        $events | Should -Be @('install','stop')
        $state.Existing | Should -BeFalse
        $state.Status | Should -Be 'Missing'
    }

    It 'fails when service presence times out' {
        $productionRoot = Join-Path $TestDrive 'worker-presence-timeout'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $state = @{ Existing = $false; Status = 'Missing' }
        $commands = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction { param($name) $null } `
            -InvokeWinSwAction {
                param($binary, $command)
                $commands.Add($command)
                $events.Add($command)
                $state.Existing = $true
                $state.Status = 'Running'
                0
            } `
            -WaitForServicePresenceAction {
                param($name, $shouldExist, $timeoutSeconds)
                $events.Add('wait-present')
                throw 'simulated presence timeout'
            } `
            -StopServiceAction {
                param($name)
                $events.Add('stop')
                $state.Status = 'Stopped'
            } `
            -SetServiceIdentityAction { param($name, $identity) throw 'unexpected identity change' } `
            -GetServiceIdentityAction { param($name) throw 'unexpected identity read' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*simulated presence timeout*'

        $commands | Should -Be @('install')
        $events | Should -Be @('install','wait-present','stop')
        $state.Existing | Should -BeTrue
        $state.Status | Should -Be 'Stopped'
    }

    It 'forwards the configured bounded timeout to every wait' {
        $productionRoot = Join-Path $TestDrive 'worker-custom-timeout'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $state = @{ Existing = $true; Status = 'Running' }
        $waits = [Collections.Generic.List[string]]::new()
        $commands = [Collections.Generic.List[string]]::new()

        Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -ServiceWaitTimeoutSeconds 17 `
            -GetServiceAction { param($name) [pscustomobject]@{ Name = $name } } `
            -InvokeWinSwAction {
                param($binary, $command)
                $commands.Add($command)
                $state.Existing = $command -eq 'install'
                $state.Status = if ($state.Existing) { 'Running' } else { 'Missing' }
                0
            } `
            -WaitForServicePresenceAction {
                param($name, $shouldExist, $timeoutSeconds)
                $waits.Add("${shouldExist}:$timeoutSeconds")
                $state.Existing | Should -Be $shouldExist
            } `
            -StopServiceAction { param($name) if ($state.Existing) { $state.Status = 'Stopped' } } `
            -SetServiceIdentityAction { param($name, $identity) } `
            -GetServiceIdentityAction { param($name) 'NT AUTHORITY\LocalService' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) }

        $commands | Should -Be @('uninstall','install')
        $waits | Should -Be @('False:17','True:17')
        $state.Existing | Should -BeTrue
        $state.Status | Should -Be 'Stopped'
    }

    It 'preserves setup and cleanup failures in causal order' {
        $productionRoot = Join-Path $TestDrive 'worker-aggregate-failure'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $events = [Collections.Generic.List[string]]::new()
        $caught = $null

        try {
            Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
                -GetServiceAction { param($name) $null } `
                -InvokeWinSwAction { param($binary, $command) $events.Add($command); 1 } `
                -WaitForServicePresenceAction { param($name, $shouldExist, $timeoutSeconds) throw 'unexpected wait' } `
                -StopServiceAction { param($name) $events.Add('stop'); throw 'simulated cleanup failure' } `
                -SetServiceIdentityAction { param($name, $identity) throw 'unexpected identity change' } `
                -GetServiceIdentityAction { param($name) throw 'unexpected identity read' } `
                -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) }
            throw 'expected worker setup to fail'
        } catch {
            $caught = $_.Exception
        }

        $events | Should -Be @('install','stop')
        $caught | Should -BeOfType [System.AggregateException]
        $caught.InnerExceptions.Count | Should -Be 2
        $caught.InnerExceptions[0].Message | Should -Be 'Media worker WinSW service installation failed.'
        $caught.InnerExceptions[1].Message | Should -Be 'simulated cleanup failure'
    }

    It 'fails closed when service control manager does not retain LocalService' {
        $productionRoot = Join-Path $TestDrive 'wrong-worker-identity'
        $service = Join-Path $productionRoot 'service'
        New-Item -ItemType Directory -Path $service -Force | Out-Null
        'winsw' | Set-Content (Join-Path $service 'ChristopherBellDev.exe')
        $state = @{ Existing = $true; Status = 'Running' }
        $commands = [Collections.Generic.List[string]]::new()
        $events = [Collections.Generic.List[string]]::new()

        { Install-SharedFolderWorkerService -ProductionRoot $productionRoot `
            -GetServiceAction { param($name) [pscustomobject]@{ Name = $name } } `
            -InvokeWinSwAction {
                param($binary, $command)
                $commands.Add($command)
                $events.Add($command)
                $state.Existing = $command -eq 'install'
                $state.Status = if ($state.Existing) { 'Running' } else { 'Missing' }
                0
            } `
            -WaitForServicePresenceAction {
                param($name, $shouldExist, $timeoutSeconds)
                $events.Add($(if ($shouldExist) { 'wait-present' } else { 'wait-absent' }))
                $state.Existing | Should -Be $shouldExist
            } `
            -StopServiceAction {
                param($name)
                $events.Add('stop')
                if ($state.Existing) { $state.Status = 'Stopped' }
            } `
            -SetServiceIdentityAction { param($name, $identity) $events.Add('set-identity') } `
            -GetServiceIdentityAction { param($name) $events.Add('get-identity'); 'LocalSystem' } `
            -ProtectPathAction { param($path) } -SetAclAction { param($path, $acl) } } |
            Should -Throw '*must run as LocalService*'

        $commands | Should -Be @('uninstall','install')
        $events | Should -Be @(
            'stop','uninstall','wait-absent','install','wait-present','stop',
            'set-identity','get-identity','stop')
        $state.Existing | Should -BeTrue
        $state.Status | Should -Be 'Stopped'
    }

    It 'publishes immutable tool versions and leaves an active version untouched' {
        $toolRoot = Join-Path $TestDrive 'versioned-tools'
        $archive1 = Join-Path $TestDrive 'tools-v1.zip'
        $archive2 = Join-Path $TestDrive 'tools-v2.zip'
        $manifest1 = Join-Path $TestDrive 'tools-v1.json'
        $manifest2 = Join-Path $TestDrive 'tools-v2.json'
        New-TestMediaArchive -Path $archive1 -Label v1
        New-TestMediaArchive -Path $archive2 -Label v2
        New-TestMediaManifest -Path $manifest1 -ArchivePath $archive1 -Version v1
        New-TestMediaManifest -Path $manifest2 -ArchivePath $archive2 -Version v2

        $active = Install-PinnedMediaTools -ManifestPath $manifest1 -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive1 $destination }
        $activeBytes = Get-Content -LiteralPath $active.Ffmpeg -Raw
        Install-PinnedMediaTools -ManifestPath $manifest2 -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive2 $destination } | Out-Null
        $current = Assert-PinnedMediaToolSet -ToolRoot $toolRoot

        $current.Ffmpeg | Should -Not -Be $active.Ffmpeg
        Get-Content -LiteralPath $active.Ffmpeg -Raw | Should -Be $activeBytes
        Test-Path -LiteralPath $active.Ffmpeg | Should -BeTrue
    }

    It 'preserves the active tool version when a refresh is interrupted' {
        $toolRoot = Join-Path $TestDrive 'interrupted-tools'
        $archive = Join-Path $TestDrive 'tools-stable.zip'
        $manifest = Join-Path $TestDrive 'tools-stable.json'
        New-TestMediaArchive -Path $archive -Label stable
        New-TestMediaManifest -Path $manifest -ArchivePath $archive -Version stable
        $active = Install-PinnedMediaTools -ManifestPath $manifest -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive $destination }
        $brokenManifest = Join-Path $TestDrive 'tools-broken.json'
        $broken = Get-Content $manifest -Raw | ConvertFrom-Json
        $broken.packageVersion = 'broken'
        $broken.sha256 = 'B' * 64
        $broken | ConvertTo-Json | Set-Content $brokenManifest

        { Install-PinnedMediaTools -ManifestPath $brokenManifest -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) throw 'interrupted download' } } |
            Should -Throw '*interrupted download*'
        (Assert-PinnedMediaToolSet -ToolRoot $toolRoot).Ffmpeg | Should -Be $active.Ffmpeg
        @(Get-ChildItem -LiteralPath $toolRoot -Filter '.install-*' -Force).Count | Should -Be 0
    }

    It 'recovers from a corrupt active marker by publishing a complete new version' {
        $toolRoot = Join-Path $TestDrive 'corrupt-active-tools'
        $archive = Join-Path $TestDrive 'tools-corrupt-recovery.zip'
        $manifest = Join-Path $TestDrive 'tools-corrupt-recovery.json'
        New-TestMediaArchive -Path $archive -Label recovered
        New-TestMediaManifest -Path $manifest -ArchivePath $archive -Version recovered
        $old = Install-PinnedMediaTools -ManifestPath $manifest -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive $destination }
        '{broken' | Set-Content -LiteralPath (Join-Path $toolRoot 'active-media-tools.json')

        $current = Install-PinnedMediaTools -ManifestPath $manifest -ToolRoot $toolRoot `
            -DownloadAction { param($uri,$destination) Copy-Item $archive $destination }

        $current.Ffmpeg | Should -Not -Be $old.Ffmpeg
        Test-Path -LiteralPath $old.Ffmpeg | Should -BeTrue
        (Assert-PinnedMediaToolSet -ToolRoot $toolRoot).Ffmpeg | Should -Be $current.Ffmpeg
    }
}
