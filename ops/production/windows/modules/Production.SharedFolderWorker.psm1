Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:DescriptorFields = @(
    'schemaVersion',
    'jobId',
    'cacheId',
    'sourcePath',
    'partialOutputPath',
    'readyOutputPath',
    'statusPath',
    'cancellationPath',
    'sourceSize',
    'sourceModifiedAt',
    'profile',
    'deadline',
    'maxOutputBytes',
    'initialBufferBytes'
)
$script:StatusValues = @(
    'QUEUED',
    'INSPECTING',
    'TRANSCODING',
    'BUFFERING',
    'READY',
    'FAILED',
    'CANCELED',
    'INSUFFICIENT_SPACE',
    'TIMED_OUT'
)

if (-not ('ChristopherBell.Dev.Production.BoundedTextReader' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;

namespace ChristopherBell.Dev.Production
{
    public sealed class BoundedTextResult
    {
        public BoundedTextResult(string text, bool truncated)
        {
            Text = text;
            Truncated = truncated;
        }

        public string Text { get; }
        public bool Truncated { get; }
    }

    public static class BoundedTextReader
    {
        public static Task<BoundedTextResult> ReadAsync(TextReader reader, int limit)
        {
            if (reader == null) throw new ArgumentNullException(nameof(reader));
            if (limit < 1) throw new ArgumentOutOfRangeException(nameof(limit));
            return ReadCoreAsync(reader, limit);
        }

        private static async Task<BoundedTextResult> ReadCoreAsync(TextReader reader, int limit)
        {
            var text = new StringBuilder(Math.Min(limit, 4096));
            var buffer = new char[4096];
            bool truncated = false;
            int count;
            while ((count = await reader.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false)) > 0)
            {
                int remaining = limit - text.Length;
                if (remaining > 0) text.Append(buffer, 0, Math.Min(remaining, count));
                if (count > remaining) truncated = true;
            }
            return new BoundedTextResult(text.ToString(), truncated);
        }
    }
}
'@
}

function Get-CanonicalPath {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathFullyQualified($Path)) {
        throw "Path must be absolute: $Path"
    }
    return [IO.Path]::GetFullPath($Path).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
}

function Test-PathBelowRoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Root
    )

    $candidate = Get-CanonicalPath -Path $Path
    $canonicalRoot = Get-CanonicalPath -Path $Root
    return $candidate.StartsWith(
        $canonicalRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)
}

function Assert-PathHasNoReparseComponent {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Root
    )

    $candidate = Get-CanonicalPath -Path $Path
    $canonicalRoot = Get-CanonicalPath -Path $Root
    if (-not (Test-PathBelowRoot -Path $candidate -Root $canonicalRoot)) {
        throw "Path is outside its configured root: $candidate"
    }

    $rootItem = Get-Item -LiteralPath $canonicalRoot -Force -ErrorAction Stop
    if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Path root contains a reparse point: $canonicalRoot"
    }

    $relative = [IO.Path]::GetRelativePath($canonicalRoot, $candidate)
    $current = $canonicalRoot
    foreach ($component in $relative.Split(
        [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar),
        [StringSplitOptions]::RemoveEmptyEntries)) {
        $current = Join-Path $current $component
        if (-not (Test-Path -LiteralPath $current)) { break }
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Path contains a reparse point: $current"
        }
    }
}

function Assert-ExactMediaPath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$FieldName,
        [Parameter(Mandatory)][string]$Root
    )

    $canonicalActual = Get-CanonicalPath -Path $Actual
    $canonicalExpected = Get-CanonicalPath -Path $Expected
    if (-not [string]::Equals(
        $canonicalActual,
        $canonicalExpected,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "$FieldName is not the expected path."
    }
    Assert-PathHasNoReparseComponent -Path $canonicalActual -Root $Root
    return $canonicalActual
}

function ConvertTo-StrictUtcTimestamp {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string]$FieldName
    )

    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) {
        throw "$FieldName must be an ISO-8601 timestamp."
    }
    $timestamp = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::AssumeUniversal -bor
        [Globalization.DateTimeStyles]::AdjustToUniversal
    if (-not [DateTimeOffset]::TryParse(
        $Value,
        [Globalization.CultureInfo]::InvariantCulture,
        $styles,
        [ref]$timestamp)) {
        throw "$FieldName must be an ISO-8601 timestamp."
    }
    return $timestamp.ToUniversalTime()
}

function Assert-PositiveInteger {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string]$FieldName,
        [switch]$AllowZero
    )

    if ($Value -is [string] -or $Value -is [bool] -or $Value -is [double] -or $Value -is [decimal]) {
        throw "$FieldName must be an integer."
    }
    $number = [long]$Value
    $minimum = if ($AllowZero) { 0 } else { 1 }
    if ($number -lt $minimum) { throw "$FieldName is outside its allowed range." }
    return $number
}

function Read-ValidatedMediaJob {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string]$Path,
        [Parameter(Mandatory, Position = 1)][string]$SharedRoot,
        [Parameter(Mandatory, Position = 2)][string]$SystemRoot,
        [DateTimeOffset]$NowUtc = [DateTimeOffset]::UtcNow
    )

    $jobPath = Get-CanonicalPath -Path $Path
    $canonicalSharedRoot = Get-CanonicalPath -Path $SharedRoot
    $canonicalSystemRoot = Get-CanonicalPath -Path $SystemRoot
    $jobRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-jobs'
    if (-not (Test-PathBelowRoot -Path $jobPath -Root $jobRoot)) {
        throw 'Job descriptor path is outside the configured queue root.'
    }
    Assert-PathHasNoReparseComponent -Path $jobPath -Root $canonicalSystemRoot

    $jobFile = Get-Item -LiteralPath $jobPath -Force -ErrorAction Stop
    if ($jobFile.PSIsContainer -or $jobFile.Length -gt 65536) {
        throw 'Job descriptor must be a bounded regular file.'
    }
    try {
        $job = Get-Content -LiteralPath $jobPath -Raw -Encoding utf8 |
            ConvertFrom-Json -DateKind String -ErrorAction Stop
    } catch {
        throw 'Job descriptor is not valid JSON.'
    }
    if ($null -eq $job -or $job -is [array]) { throw 'Job descriptor must be a JSON object.' }

    $actualFields = @($job.PSObject.Properties.Name | Sort-Object)
    $expectedFields = @($script:DescriptorFields | Sort-Object)
    if (($actualFields -join "`n") -cne ($expectedFields -join "`n")) {
        throw 'Job descriptor fields do not exactly match schema version 1.'
    }
    if ($job.schemaVersion -is [string] -or [int]$job.schemaVersion -ne 1) {
        throw 'Unsupported job schemaVersion.'
    }
    if ($job.jobId -isnot [string] -or $job.jobId -notmatch '^[A-Za-z0-9_-]{1,100}$') {
        throw 'jobId is invalid.'
    }
    if ($job.cacheId -isnot [string] -or $job.cacheId -notmatch '^[a-f0-9]{64}$') {
        throw 'cacheId is invalid.'
    }
    if ($job.profile -isnot [string] -or $job.profile -notin @('VIDEO_MP4', 'AUDIO_M4A')) {
        throw 'profile is unsupported.'
    }
    if ([IO.Path]::GetFileName($jobPath) -cne "$($job.jobId).json") {
        throw 'Job descriptor filename does not match jobId.'
    }

    $sourcePath = Get-CanonicalPath -Path ([string]$job.sourcePath)
    if (-not (Test-PathBelowRoot -Path $sourcePath -Root $canonicalSharedRoot)) {
        throw 'sourcePath is outside the configured shared root.'
    }
    Assert-PathHasNoReparseComponent -Path $sourcePath -Root $canonicalSharedRoot
    $source = Get-Item -LiteralPath $sourcePath -Force -ErrorAction Stop
    if ($source.PSIsContainer -or ($source.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'sourcePath must identify a regular non-reparse file.'
    }

    $sourceSize = Assert-PositiveInteger -Value $job.sourceSize -FieldName sourceSize -AllowZero
    if ([long]$source.Length -ne $sourceSize) { throw 'Source size changed after the job was queued.' }
    $sourceModifiedAt = ConvertTo-StrictUtcTimestamp -Value $job.sourceModifiedAt -FieldName sourceModifiedAt
    if ($source.LastWriteTimeUtc.Ticks -ne $sourceModifiedAt.UtcDateTime.Ticks) {
        throw 'Source modified time changed after the job was queued.'
    }

    $deadline = ConvertTo-StrictUtcTimestamp -Value $job.deadline -FieldName deadline
    if ($deadline -le $NowUtc.ToUniversalTime()) { throw 'Job deadline has expired.' }
    $maxOutputBytes = Assert-PositiveInteger -Value $job.maxOutputBytes -FieldName maxOutputBytes
    $initialBufferBytes = Assert-PositiveInteger -Value $job.initialBufferBytes -FieldName initialBufferBytes
    if ($initialBufferBytes -gt $maxOutputBytes) {
        throw 'initialBufferBytes must not exceed maxOutputBytes.'
    }

    $extension = if ($job.profile -eq 'VIDEO_MP4') { 'mp4' } else { 'm4a' }
    $partialRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-partial'
    $cacheRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-cache'
    $statusRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-status'
    $cancellationRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-cancel'
    $partialOutputPath = Assert-ExactMediaPath -Actual ([string]$job.partialOutputPath) `
        -Expected (Join-Path $partialRoot "$($job.jobId).$extension.part") `
        -FieldName partialOutputPath -Root $canonicalSystemRoot
    $readyOutputPath = Assert-ExactMediaPath -Actual ([string]$job.readyOutputPath) `
        -Expected (Join-Path $cacheRoot "$($job.cacheId).$extension") `
        -FieldName readyOutputPath -Root $canonicalSystemRoot
    $statusPath = Assert-ExactMediaPath -Actual ([string]$job.statusPath) `
        -Expected (Join-Path $statusRoot "$($job.jobId).json") `
        -FieldName statusPath -Root $canonicalSystemRoot
    $cancellationPath = Assert-ExactMediaPath -Actual ([string]$job.cancellationPath) `
        -Expected (Join-Path $cancellationRoot "$($job.jobId).cancel") `
        -FieldName cancellationPath -Root $canonicalSystemRoot

    return [pscustomobject]@{
        schemaVersion = 1
        jobId = [string]$job.jobId
        cacheId = [string]$job.cacheId
        sourcePath = $sourcePath
        partialOutputPath = $partialOutputPath
        readyOutputPath = $readyOutputPath
        statusPath = $statusPath
        cancellationPath = $cancellationPath
        sourceSize = $sourceSize
        sourceModifiedAt = $sourceModifiedAt
        profile = [string]$job.profile
        deadline = $deadline
        maxOutputBytes = $maxOutputBytes
        initialBufferBytes = $initialBufferBytes
    }
}

function Assert-MediaJobSourceUnchanged {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    $source = Get-Item -LiteralPath $Job.sourcePath -Force -ErrorAction Stop
    if ($source.PSIsContainer -or
        [long]$source.Length -ne [long]$Job.sourceSize -or
        $source.LastWriteTimeUtc.Ticks -ne $Job.sourceModifiedAt.UtcDateTime.Ticks) {
        throw 'Source metadata changed while the job was active.'
    }
}

function New-FixedFfprobeArguments {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    return @(
        '-nostdin',
        '-v', 'error',
        '-show_streams',
        '-show_format',
        '-of', 'json',
        '--',
        [string]$Job.sourcePath
    )
}

function New-FixedFfmpegArguments {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    $common = @('-nostdin', '-hide_banner', '-v', 'error', '-y', '-i', [string]$Job.sourcePath)
    if ($Job.profile -eq 'VIDEO_MP4') {
        return $common + @(
            '-map', '0:v:0',
            '-map', '0:a:0?',
            '-c:v', 'libx264',
            '-preset', 'veryfast',
            '-pix_fmt', 'yuv420p',
            '-c:a', 'aac',
            '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
            '-f', 'mp4',
            [string]$Job.partialOutputPath
        )
    }
    if ($Job.profile -eq 'AUDIO_M4A') {
        return $common + @(
            '-map', '0:a:0',
            '-vn',
            '-c:a', 'aac',
            '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
            '-f', 'mp4',
            [string]$Job.partialOutputPath
        )
    }
    throw 'profile is unsupported.'
}

function Enter-SharedFolderWorkerLock {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    try {
        return [IO.File]::Open($Path, 'OpenOrCreate', 'ReadWrite', 'None')
    } catch [IO.IOException] {
        throw 'Another media worker is active.'
    }
}

function Assert-PinnedMediaToolSet {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ToolRoot)

    $canonicalRoot = Get-CanonicalPath -Path $ToolRoot
    Assert-PathHasNoReparseComponent -Path (Join-Path $canonicalRoot 'ffmpeg.exe') -Root $canonicalRoot
    Assert-PathHasNoReparseComponent -Path (Join-Path $canonicalRoot 'ffprobe.exe') -Root $canonicalRoot
    $markerPath = Join-Path $canonicalRoot 'installed-media-tools.json'
    Assert-PathHasNoReparseComponent -Path $markerPath -Root $canonicalRoot
    $markerFile = Get-Item -LiteralPath $markerPath -Force -ErrorAction Stop
    if ($markerFile.Length -gt 16384) { throw 'Installed media tool manifest is too large.' }
    try {
        $marker = Get-Content -LiteralPath $markerPath -Raw -Encoding utf8 |
            ConvertFrom-Json -DateKind String -ErrorAction Stop
    } catch {
        throw 'Installed media tool manifest is invalid.'
    }
    $actual = @($marker.PSObject.Properties.Name | Sort-Object)
    $expected = @(@(
        'ffmpegSha256',
        'ffprobeSha256',
        'packageSha256',
        'packageVersion',
        'schemaVersion'
    ) | Sort-Object)
    if (($actual -join "`n") -cne ($expected -join "`n") -or [int]$marker.schemaVersion -ne 1) {
        throw 'Installed media tool manifest fields are invalid.'
    }
    foreach ($name in 'packageSha256', 'ffmpegSha256', 'ffprobeSha256') {
        if ($marker.$name -isnot [string] -or $marker.$name -notmatch '^[A-Fa-f0-9]{64}$') {
            throw 'Installed media tool manifest hashes are invalid.'
        }
    }
    $ffmpeg = Join-Path $canonicalRoot 'ffmpeg.exe'
    $ffprobe = Join-Path $canonicalRoot 'ffprobe.exe'
    if ((Get-FileHash -LiteralPath $ffmpeg -Algorithm SHA256).Hash -ne $marker.ffmpegSha256 -or
        (Get-FileHash -LiteralPath $ffprobe -Algorithm SHA256).Hash -ne $marker.ffprobeSha256) {
        throw 'Installed media tool executable hash verification failed.'
    }
    return [pscustomobject]@{ Ffmpeg = $ffmpeg; Ffprobe = $ffprobe }
}

function Invoke-PinnedMediaTool {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][DateTimeOffset]$Deadline,
        [string]$CancellationPath,
        [int]$MaxLogCharacters = 65536,
        [scriptblock]$OnPoll
    )

    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
        throw "Pinned media tool is missing: $Executable"
    }
    if ($MaxLogCharacters -lt 1024 -or $MaxLogCharacters -gt 1048576) {
        throw 'MaxLogCharacters is outside the allowed range.'
    }

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.WorkingDirectory = Split-Path -Parent $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) { [void]$start.ArgumentList.Add($argument) }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    $stdoutTask = $null
    $stderrTask = $null
    try {
        if (-not $process.Start()) { throw 'Media tool process did not start.' }
        $started = $true
        $stdoutTask = [ChristopherBell.Dev.Production.BoundedTextReader]::ReadAsync(
            $process.StandardOutput,
            $MaxLogCharacters)
        $stderrTask = [ChristopherBell.Dev.Production.BoundedTextReader]::ReadAsync(
            $process.StandardError,
            $MaxLogCharacters)
        while (-not $process.WaitForExit(250)) {
            if ($CancellationPath -and (Test-Path -LiteralPath $CancellationPath -PathType Leaf)) {
                throw [OperationCanceledException]::new('Media job was canceled.')
            }
            if ([DateTimeOffset]::UtcNow -ge $Deadline) {
                throw [TimeoutException]::new('Media job exceeded its deadline.')
            }
            if ($OnPoll) { & $OnPoll }
        }
        $process.WaitForExit()
        if ($CancellationPath -and (Test-Path -LiteralPath $CancellationPath -PathType Leaf)) {
            throw [OperationCanceledException]::new('Media job was canceled.')
        }
        if ([DateTimeOffset]::UtcNow -ge $Deadline) {
            throw [TimeoutException]::new('Media job exceeded its deadline.')
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Media tool exited with code $($process.ExitCode)."
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StandardOutput = $stdout.Text
            StandardError = $stderr.Text
            OutputTruncated = $stdout.Truncated
            ErrorTruncated = $stderr.Truncated
        }
    } catch {
        if ($started -and -not $process.HasExited) {
            try { $process.Kill($true) } catch [InvalidOperationException] { }
        }
        if ($started) {
            $process.WaitForExit()
            if ($stdoutTask) { [void]$stdoutTask.GetAwaiter().GetResult() }
            if ($stderrTask) { [void]$stderrTask.GetAwaiter().GetResult() }
        }
        throw
    } finally {
        $process.Dispose()
    }
}

function Write-MediaJobStatusAtomic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [Parameter(Mandatory)][ValidateScript({ $script:StatusValues -contains $_ })][string]$Status,
        [long]$OutputBytes = 0,
        [AllowNull()][string]$FailureCategory
    )

    if ($OutputBytes -lt 0) { throw 'outputBytes must not be negative.' }
    if ($FailureCategory -and $FailureCategory -notmatch '^[a-z_]{1,40}$') {
        throw 'failureCategory is invalid.'
    }
    $payload = [ordered]@{
        schemaVersion = 1
        jobId = [string]$Job.jobId
        status = $Status
        outputBytes = $OutputBytes
        failureCategory = $FailureCategory
    }
    $temporary = "$($Job.statusPath).$([Guid]::NewGuid().ToString('N')).tmp"
    $json = $payload | ConvertTo-Json -Compress
    try {
        [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary, [string]$Job.statusPath, $true)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Complete-MediaJobAtomically {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    Assert-MediaJobSourceUnchanged -Job $Job
    $partial = Get-Item -LiteralPath $Job.partialOutputPath -Force -ErrorAction Stop
    if ($partial.PSIsContainer -or $partial.Length -le 0) { throw 'Media output is empty.' }
    if ($partial.Length -gt [long]$Job.maxOutputBytes) { throw 'Media output exceeds maxOutputBytes.' }
    [IO.File]::Move([string]$Job.partialOutputPath, [string]$Job.readyOutputPath, $true)
    Write-MediaJobStatusAtomic -Job $Job -Status READY -OutputBytes $partial.Length
}

function Invoke-ValidatedMediaJob {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [Parameter(Mandatory)][string]$FfprobeExecutable,
        [Parameter(Mandatory)][string]$FfmpegExecutable
    )

    $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot([string]$Job.partialOutputPath))
    if ($drive.AvailableFreeSpace -lt [long]$Job.maxOutputBytes) {
        throw 'Insufficient space for maxOutputBytes.'
    }
    Write-MediaJobStatusAtomic -Job $Job -Status INSPECTING
    $probe = Invoke-PinnedMediaTool -Executable $FfprobeExecutable `
        -ArgumentList (New-FixedFfprobeArguments -Job $Job) `
        -Deadline $Job.deadline -CancellationPath $Job.cancellationPath
    if ($probe.OutputTruncated -or [Text.Encoding]::UTF8.GetByteCount($probe.StandardOutput) -gt 65536) {
        throw 'ffprobe JSON exceeded its bounded output limit.'
    }
    try { $probeJson = $probe.StandardOutput | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'ffprobe did not produce valid bounded JSON.' }
    if ($null -eq $probeJson -or $probeJson -is [array]) {
        throw 'ffprobe did not produce a JSON object.'
    }

    Write-MediaJobStatusAtomic -Job $Job -Status TRANSCODING
    $progress = @{ BufferingPublished = $false }
    $poll = {
        if (Test-Path -LiteralPath $Job.partialOutputPath -PathType Leaf) {
            $bytes = (Get-Item -LiteralPath $Job.partialOutputPath -Force).Length
            if ($bytes -gt [long]$Job.maxOutputBytes) { throw 'Media output exceeds maxOutputBytes.' }
            if (-not $progress.BufferingPublished -and $bytes -ge [long]$Job.initialBufferBytes) {
                Write-MediaJobStatusAtomic -Job $Job -Status BUFFERING -OutputBytes $bytes
                $progress.BufferingPublished = $true
            }
        }
    }
    Invoke-PinnedMediaTool -Executable $FfmpegExecutable `
        -ArgumentList (New-FixedFfmpegArguments -Job $Job) `
        -Deadline $Job.deadline -CancellationPath $Job.cancellationPath -OnPoll $poll | Out-Null
    Complete-MediaJobAtomically -Job $Job
}

Export-ModuleMember -Function Get-CanonicalPath,Test-PathBelowRoot,Assert-PathHasNoReparseComponent,Read-ValidatedMediaJob,Assert-MediaJobSourceUnchanged,New-FixedFfprobeArguments,New-FixedFfmpegArguments,Enter-SharedFolderWorkerLock,Assert-PinnedMediaToolSet,Invoke-PinnedMediaTool,Write-MediaJobStatusAtomic,Complete-MediaJobAtomically,Invoke-ValidatedMediaJob
