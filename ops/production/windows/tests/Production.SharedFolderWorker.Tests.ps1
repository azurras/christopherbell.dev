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

    It 'launches only through ArgumentList, bounds redirected logs, and kills a process tree' {
        $module = Get-Content (Join-Path $moduleRoot 'Production.SharedFolderWorker.psm1') -Raw

        $module | Should -Match '\.ArgumentList\.Add\(\$argument\)'
        $module | Should -Match 'BoundedTextReader'
        $module | Should -Match '\.Kill\(\$true\)'
        $module | Should -Not -Match 'Arguments\s*='
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
        New-Item -ItemType Directory -Path $toolRoot | Out-Null
        'fake' | Set-Content (Join-Path $toolRoot 'ffmpeg.exe')
        'fake' | Set-Content (Join-Path $toolRoot 'ffprobe.exe')
        $manifestPath = Join-Path $PSScriptRoot '..\config\media-tools-manifest.json'
        $manifest = Read-PinnedMediaToolManifest -Path $manifestPath
        [ordered]@{
            schemaVersion = 1
            packageVersion = $manifest.packageVersion
            packageSha256 = $manifest.sha256
            ffmpegSha256 = (Get-FileHash (Join-Path $toolRoot 'ffmpeg.exe') -Algorithm SHA256).Hash
            ffprobeSha256 = (Get-FileHash (Join-Path $toolRoot 'ffprobe.exe') -Algorithm SHA256).Hash
        } | ConvertTo-Json | Set-Content (Join-Path $toolRoot 'installed-media-tools.json')
        Mock Invoke-WebRequest { throw 'Media tools should not be downloaded again.' }

        $installed = Install-PinnedMediaTools -ManifestPath $manifestPath -ToolRoot $toolRoot

        $installed.Ffmpeg | Should -Be (Join-Path $toolRoot 'ffmpeg.exe')
        Should -Invoke Invoke-WebRequest -Times 0
    }

    It 'rejects a media tool changed after pinned installation' {
        $toolRoot = Join-Path $TestDrive 'pinned-media-tools'
        New-Item -ItemType Directory -Path $toolRoot | Out-Null
        'ffmpeg' | Set-Content (Join-Path $toolRoot 'ffmpeg.exe')
        'ffprobe' | Set-Content (Join-Path $toolRoot 'ffprobe.exe')
        [ordered]@{
            schemaVersion = 1
            packageVersion = 'test'
            packageSha256 = 'a' * 64
            ffmpegSha256 = (Get-FileHash (Join-Path $toolRoot 'ffmpeg.exe') -Algorithm SHA256).Hash
            ffprobeSha256 = (Get-FileHash (Join-Path $toolRoot 'ffprobe.exe') -Algorithm SHA256).Hash
        } | ConvertTo-Json | Set-Content (Join-Path $toolRoot 'installed-media-tools.json')

        Assert-PinnedMediaToolSet -ToolRoot $toolRoot | Should -Not -BeNullOrEmpty
        'changed' | Set-Content (Join-Path $toolRoot 'ffmpeg.exe')

        { Assert-PinnedMediaToolSet -ToolRoot $toolRoot } | Should -Throw '*hash verification*'
    }
}
