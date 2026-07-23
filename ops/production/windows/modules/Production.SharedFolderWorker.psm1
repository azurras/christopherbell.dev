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
$script:MaximumJobDuration = [TimeSpan]::FromHours(2)
$script:MaximumSourceBytes = 10GB
$script:MaximumOutputBytes = 50GB
$script:MaximumInitialBufferBytes = 2MB
$script:MinimumFreeSpaceReserveBytes = 100GB
$script:StagingCopyBufferBytes = 64KB

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

if (-not ('ChristopherBell.Dev.Production.NativeFileHandle' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace ChristopherBell.Dev.Production
{
    public sealed class NativeFileIdentity
    {
        public uint VolumeSerialNumber { get; set; }
        public ulong FileIndex { get; set; }
        public long FileSize { get; set; }
        public DateTime LastWriteTimeUtc { get; set; }
    }

    public static class NativeFileHandle
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct ByHandleFileInformation
        {
            public uint FileAttributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastAccessTime;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastWriteTime;
            public uint VolumeSerialNumber;
            public uint FileSizeHigh;
            public uint FileSizeLow;
            public uint NumberOfLinks;
            public uint FileIndexHigh;
            public uint FileIndexLow;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(
            SafeFileHandle handle,
            out ByHandleFileInformation information);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandle(
            SafeFileHandle handle,
            StringBuilder path,
            uint pathLength,
            uint flags);

        public static NativeFileIdentity GetIdentity(SafeFileHandle handle)
        {
            ByHandleFileInformation information;
            if (!GetFileInformationByHandle(handle, out information))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            long writeTime = ((long)(uint)information.LastWriteTime.dwHighDateTime << 32) |
                (uint)information.LastWriteTime.dwLowDateTime;
            return new NativeFileIdentity
            {
                VolumeSerialNumber = information.VolumeSerialNumber,
                FileIndex = ((ulong)information.FileIndexHigh << 32) | information.FileIndexLow,
                FileSize = ((long)information.FileSizeHigh << 32) | information.FileSizeLow,
                LastWriteTimeUtc = DateTime.FromFileTimeUtc(writeTime)
            };
        }

        public static string GetFinalPath(SafeFileHandle handle)
        {
            var path = new StringBuilder(32768);
            uint length = GetFinalPathNameByHandle(handle, path, (uint)path.Capacity, 0);
            if (length == 0 || length >= path.Capacity)
                throw new Win32Exception(Marshal.GetLastWin32Error());
            string value = path.ToString();
            if (value.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
                return @"\\" + value.Substring(8);
            if (value.StartsWith(@"\\?\", StringComparison.OrdinalIgnoreCase))
                return value.Substring(4);
            return value;
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

function Assert-MediaSourceSizeAllowed {
    [CmdletBinding()]
    param([Parameter(Mandatory)][long]$SourceSizeBytes)

    if ($SourceSizeBytes -lt 0 -or $SourceSizeBytes -gt $script:MaximumSourceBytes) {
        throw 'sourceSize exceeds the fixed ten-gigabyte transcode ceiling.'
    }
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
    Assert-MediaSourceSizeAllowed -SourceSizeBytes $sourceSize
    if ([long]$source.Length -ne $sourceSize) { throw 'Source size changed after the job was queued.' }
    $sourceModifiedAt = ConvertTo-StrictUtcTimestamp -Value $job.sourceModifiedAt -FieldName sourceModifiedAt
    if ($source.LastWriteTimeUtc.Ticks -ne $sourceModifiedAt.UtcDateTime.Ticks) {
        throw 'Source modified time changed after the job was queued.'
    }

    $deadline = ConvertTo-StrictUtcTimestamp -Value $job.deadline -FieldName deadline
    if ($deadline -le $NowUtc.ToUniversalTime()) { throw 'Job deadline has expired.' }
    if ($deadline -gt $NowUtc.ToUniversalTime().Add($script:MaximumJobDuration)) {
        throw 'Job deadline exceeds the fixed two-hour worker horizon.'
    }
    $maxOutputBytes = Assert-PositiveInteger -Value $job.maxOutputBytes -FieldName maxOutputBytes
    $initialBufferBytes = Assert-PositiveInteger -Value $job.initialBufferBytes -FieldName initialBufferBytes
    if ($maxOutputBytes -gt $script:MaximumOutputBytes) {
        throw 'maxOutputBytes exceeds the fixed worker limit.'
    }
    if ($initialBufferBytes -gt $script:MaximumInitialBufferBytes) {
        throw 'initialBufferBytes exceeds the fixed worker limit.'
    }
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
        sharedRoot = $canonicalSharedRoot
        systemRoot = $canonicalSystemRoot
        stagingRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-staging'
    }
}

function Open-ValidatedMediaSource {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    $stream = [IO.FileStream]::new(
        [string]$Job.sourcePath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read,
        65536,
        [IO.FileOptions]::SequentialScan)
    try {
        $finalPath = if ($IsWindows) {
            [ChristopherBell.Dev.Production.NativeFileHandle]::GetFinalPath($stream.SafeFileHandle)
        } else {
            $stream.Name
        }
        $canonicalFinalPath = Get-CanonicalPath -Path $finalPath
        if (-not [string]::Equals(
            $canonicalFinalPath,
            (Get-CanonicalPath -Path $Job.sourcePath),
            [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Opened source final location does not match sourcePath.'
        }
        if (-not (Test-PathBelowRoot -Path $canonicalFinalPath -Root $Job.sharedRoot)) {
            throw 'Opened source is outside the configured shared root.'
        }
        $identity = if ($IsWindows) {
            [ChristopherBell.Dev.Production.NativeFileHandle]::GetIdentity($stream.SafeFileHandle)
        } else {
            [pscustomobject]@{
                FileSize = $stream.Length
                LastWriteTimeUtc = (Get-Item -LiteralPath $stream.Name).LastWriteTimeUtc
            }
        }
        if ([long]$identity.FileSize -ne [long]$Job.sourceSize -or
            $identity.LastWriteTimeUtc.Ticks -ne $Job.sourceModifiedAt.UtcDateTime.Ticks) {
            throw 'Opened source identity does not match the queued source metadata.'
        }
        $owned = [pscustomobject]@{
            Path = $canonicalFinalPath
            Stream = $stream
            Identity = $identity
        }
        $owned | Add-Member -MemberType ScriptMethod -Name Dispose -Value { $this.Stream.Dispose() }
        return $owned
    } catch {
        $stream.Dispose()
        throw
    }
}

function Assert-MediaJobMayContinue {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [DateTimeOffset]$NowUtc = [DateTimeOffset]::UtcNow
    )

    if (Test-Path -LiteralPath $Job.cancellationPath -PathType Leaf) {
        throw [OperationCanceledException]::new('Media job was canceled.')
    }
    if ($NowUtc.ToUniversalTime() -ge $Job.deadline) {
        throw [TimeoutException]::new('Media job exceeded its deadline.')
    }
}

function Assert-MediaJobUsesSinglePrivateStore {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    $systemRoot = Get-CanonicalPath -Path $Job.systemRoot
    $expectedStagingRoot = Get-CanonicalPath -Path (
        Join-Path $systemRoot 'shared-folder-media-staging')
    if (-not [string]::Equals(
        (Get-CanonicalPath -Path $Job.stagingRoot),
        $expectedStagingRoot,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Media staging and output paths must use the configured private store.'
    }
    foreach ($path in @(
        $Job.stagingRoot,
        $Job.partialOutputPath,
        $Job.readyOutputPath
    )) {
        if (-not (Test-PathBelowRoot -Path $path -Root $systemRoot)) {
            throw 'Media staging and output paths must use one private store.'
        }
        if (-not [string]::Equals(
            [IO.Path]::GetPathRoot((Get-CanonicalPath -Path $path)),
            [IO.Path]::GetPathRoot($systemRoot),
            [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Media staging and output paths must use one private store.'
        }
    }
    return $systemRoot
}

function Copy-ValidatedMediaSourceToStage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [Parameter(Mandatory)]$OwnedSource,
        [scriptblock]$GetNowUtc = { [DateTimeOffset]::UtcNow },
        [scriptblock]$GetAvailableFreeSpace = {
            param($Path)
            ([IO.DriveInfo]::new([IO.Path]::GetPathRoot($Path))).AvailableFreeSpace
        }
    )

    Assert-MediaSourceSizeAllowed -SourceSizeBytes ([long]$Job.sourceSize)
    $privateStoreRoot = Assert-MediaJobUsesSinglePrivateStore -Job $Job
    Assert-PathHasNoReparseComponent -Path $Job.stagingRoot -Root $Job.systemRoot
    $stagedPath = Join-Path $Job.stagingRoot "$($Job.jobId).source"
    Assert-PathHasNoReparseComponent -Path $stagedPath -Root $Job.systemRoot
    $output = $null
    $copiedBytes = 0L
    try {
        Assert-MediaJobMayContinue -Job $Job -NowUtc (& $GetNowUtc)
        $output = [IO.FileStream]::new(
            $stagedPath,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None)
        $OwnedSource.Stream.Position = 0
        $buffer = [byte[]]::new($script:StagingCopyBufferBytes)
        while ($copiedBytes -lt [long]$Job.sourceSize) {
            Assert-MediaJobMayContinue -Job $Job -NowUtc (& $GetNowUtc)
            $remainingBytes = [long]$Job.sourceSize - $copiedBytes
            $availableBytes = [long](& $GetAvailableFreeSpace $privateStoreRoot)
            Assert-MediaRemainingCapacity -RemainingSourceBytes $remainingBytes `
                -MaxOutputBytes ([long]$Job.maxOutputBytes) `
                -AvailableFreeSpaceBytes $availableBytes
            $requestedBytes = [int][Math]::Min([long]$buffer.Length, $remainingBytes)
            $readBytes = $OwnedSource.Stream.Read($buffer, 0, $requestedBytes)
            if ($readBytes -le 0) {
                throw 'Retained source ended before the declared sourceSize.'
            }
            $output.Write($buffer, 0, $readBytes)
            $copiedBytes += $readBytes
            if ($copiedBytes -gt [long]$Job.sourceSize) {
                throw 'Staged source exceeds the declared sourceSize.'
            }
            $remainingBytes = [long]$Job.sourceSize - $copiedBytes
            $availableBytes = [long](& $GetAvailableFreeSpace $privateStoreRoot)
            Assert-MediaRemainingCapacity -RemainingSourceBytes $remainingBytes `
                -MaxOutputBytes ([long]$Job.maxOutputBytes) `
                -AvailableFreeSpaceBytes $availableBytes
            Assert-MediaJobMayContinue -Job $Job -NowUtc (& $GetNowUtc)
        }
        if ($OwnedSource.Stream.ReadByte() -ne -1) {
            throw 'Retained source exceeds the declared sourceSize.'
        }
        $output.Flush($true)
        $output.Dispose()
        $output = $null
        if ((Get-Item -LiteralPath $stagedPath -Force).Length -ne [long]$Job.sourceSize) {
            throw 'Staged source byte count does not match sourceSize.'
        }
        [IO.File]::SetLastWriteTimeUtc($stagedPath, $Job.sourceModifiedAt.UtcDateTime)
        $stream = [IO.FileStream]::new(
            $stagedPath,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            [IO.FileShare]::Read,
            65536,
            [IO.FileOptions]::SequentialScan)
        try {
            $finalPath = if ($IsWindows) {
                [ChristopherBell.Dev.Production.NativeFileHandle]::GetFinalPath($stream.SafeFileHandle)
            } else {
                $stream.Name
            }
            if (-not [string]::Equals(
                (Get-CanonicalPath -Path $finalPath),
                (Get-CanonicalPath -Path $stagedPath),
                [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Staged source final location changed before tool use.'
            }
            $identity = if ($IsWindows) {
                [ChristopherBell.Dev.Production.NativeFileHandle]::GetIdentity($stream.SafeFileHandle)
            } else {
                [pscustomobject]@{
                    FileSize = $stream.Length
                    LastWriteTimeUtc = (Get-Item -LiteralPath $stream.Name).LastWriteTimeUtc
                }
            }
            if ([long]$identity.FileSize -ne [long]$Job.sourceSize) {
                throw 'Staged source identity does not match sourceSize.'
            }
            $staged = [pscustomobject]@{
                Path = $stagedPath
                Stream = $stream
                Identity = $identity
            }
            $staged | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
                $this.Stream.Dispose()
            }
            return $staged
        } catch {
            $stream.Dispose()
            throw
        }
    } catch {
        if ($output) { $output.Dispose() }
        Remove-Item -LiteralPath $stagedPath -Force -ErrorAction SilentlyContinue
        throw
    }
}

function Assert-StagedMediaSourceUnchanged {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [Parameter(Mandatory)]$StagedSource
    )

    $finalPath = if ($IsWindows) {
        [ChristopherBell.Dev.Production.NativeFileHandle]::GetFinalPath(
            $StagedSource.Stream.SafeFileHandle)
    } else {
        $StagedSource.Stream.Name
    }
    if (-not [string]::Equals(
        (Get-CanonicalPath -Path $finalPath),
        (Get-CanonicalPath -Path $StagedSource.Path),
        [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Staged source final location changed before tool use.'
    }
    $identity = if ($IsWindows) {
        [ChristopherBell.Dev.Production.NativeFileHandle]::GetIdentity(
            $StagedSource.Stream.SafeFileHandle)
    } else {
        [pscustomobject]@{
            FileSize = $StagedSource.Stream.Length
            LastWriteTimeUtc = (Get-Item -LiteralPath $StagedSource.Path).LastWriteTimeUtc
        }
    }
    if ([long]$identity.FileSize -ne [long]$Job.sourceSize -or
        [long]$identity.FileSize -ne [long]$StagedSource.Identity.FileSize) {
        throw 'Staged source identity changed before tool use.'
    }
    if ($IsWindows -and
        ($identity.VolumeSerialNumber -ne $StagedSource.Identity.VolumeSerialNumber -or
        $identity.FileIndex -ne $StagedSource.Identity.FileIndex)) {
        throw 'Staged source identity changed before tool use.'
    }
}

function Assert-MediaJobPrivatePaths {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    foreach ($path in @(
        $Job.stagingRoot,
        (Split-Path -Parent $Job.partialOutputPath),
        (Split-Path -Parent $Job.readyOutputPath),
        (Split-Path -Parent $Job.statusPath),
        (Split-Path -Parent $Job.cancellationPath),
        $Job.partialOutputPath,
        $Job.readyOutputPath,
        $Job.statusPath,
        $Job.cancellationPath
    )) {
        Assert-PathHasNoReparseComponent -Path $path -Root $Job.systemRoot
    }
}

function Enter-MediaPrivateRootLease {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Job)

    Assert-MediaJobPrivatePaths -Job $Job
    $streams = [Collections.Generic.List[IO.FileStream]]::new()
    $leasePaths = [Collections.Generic.List[string]]::new()
    try {
        foreach ($directory in @(
            $Job.stagingRoot,
            (Split-Path -Parent $Job.partialOutputPath),
            (Split-Path -Parent $Job.readyOutputPath),
            (Split-Path -Parent $Job.statusPath),
            (Split-Path -Parent $Job.cancellationPath)
        ) | Select-Object -Unique) {
            $leasePath = Join-Path $directory ".lease-$($Job.jobId)"
            $stream = [IO.FileStream]::new(
                $leasePath,
                [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite,
                [IO.FileShare]::ReadWrite)
            $streams.Add($stream)
            $leasePaths.Add($leasePath)
        }
        $lease = [pscustomobject]@{ Streams = $streams; Paths = $leasePaths }
        $lease | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
            foreach ($stream in $this.Streams) { $stream.Dispose() }
            foreach ($path in $this.Paths) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        return $lease
    } catch {
        foreach ($stream in $streams) { $stream.Dispose() }
        foreach ($leasePath in $leasePaths) {
            Remove-Item -LiteralPath $leasePath -Force -ErrorAction SilentlyContinue
        }
        throw
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
            '-fs', [string]$Job.maxOutputBytes,
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
            '-fs', [string]$Job.maxOutputBytes,
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
    $markerPath = Join-Path $canonicalRoot 'active-media-tools.json'
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
        'schemaVersion',
        'versionDirectory'
    ) | Sort-Object)
    if (($actual -join "`n") -cne ($expected -join "`n") -or [int]$marker.schemaVersion -ne 1) {
        throw 'Installed media tool manifest fields are invalid.'
    }
    foreach ($name in 'packageSha256', 'ffmpegSha256', 'ffprobeSha256') {
        if ($marker.$name -isnot [string] -or $marker.$name -notmatch '^[A-Fa-f0-9]{64}$') {
            throw 'Installed media tool manifest hashes are invalid.'
        }
    }
    if ($marker.packageVersion -isnot [string] -or
        $marker.packageVersion -notmatch '^[A-Za-z0-9._-]{1,40}$' -or
        $marker.versionDirectory -isnot [string] -or
        $marker.versionDirectory -notmatch '^[A-Za-z0-9._-]{1,120}$') {
        throw 'Installed media tool manifest version values are invalid.'
    }
    $versionRoot = Join-Path (Join-Path $canonicalRoot 'versions') ([string]$marker.versionDirectory)
    Assert-PathHasNoReparseComponent -Path $versionRoot -Root $canonicalRoot
    $ffmpeg = @(Get-ChildItem -LiteralPath $versionRoot -Filter ffmpeg.exe -File -Recurse)
    $ffprobe = @(Get-ChildItem -LiteralPath $versionRoot -Filter ffprobe.exe -File -Recurse)
    if ($ffmpeg.Count -ne 1 -or $ffprobe.Count -ne 1) {
        throw 'Installed media tool version layout is invalid.'
    }
    $ffmpeg = $ffmpeg[0].FullName
    $ffprobe = $ffprobe[0].FullName
    Assert-PathHasNoReparseComponent -Path $ffmpeg -Root $canonicalRoot
    Assert-PathHasNoReparseComponent -Path $ffprobe -Root $canonicalRoot
    if ((Get-FileHash -LiteralPath $ffmpeg -Algorithm SHA256).Hash -ne $marker.ffmpegSha256 -or
        (Get-FileHash -LiteralPath $ffprobe -Algorithm SHA256).Hash -ne $marker.ffprobeSha256) {
        throw 'Installed media tool executable hash verification failed.'
    }
    return [pscustomobject]@{
        Ffmpeg = $ffmpeg
        Ffprobe = $ffprobe
        FfmpegSha256 = ([string]$marker.ffmpegSha256).ToUpperInvariant()
        FfprobeSha256 = ([string]$marker.ffprobeSha256).ToUpperInvariant()
        PackageSha256 = ([string]$marker.packageSha256).ToUpperInvariant()
        PackageVersion = [string]$marker.packageVersion
        VersionDirectory = [string]$marker.versionDirectory
    }
}

function Assert-MediaToolSetUnchanged {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$ToolSet)

    foreach ($name in 'Ffmpeg','Ffprobe','FfmpegSha256','FfprobeSha256') {
        if (-not $ToolSet.PSObject.Properties[$name]) {
            throw "Pinned media tool set is missing $name."
        }
    }
    if ((Get-FileHash -LiteralPath $ToolSet.Ffmpeg -Algorithm SHA256).Hash -cne
        ([string]$ToolSet.FfmpegSha256).ToUpperInvariant() -or
        (Get-FileHash -LiteralPath $ToolSet.Ffprobe -Algorithm SHA256).Hash -cne
        ([string]$ToolSet.FfprobeSha256).ToUpperInvariant()) {
        throw 'Installed media tool executable hash verification failed immediately before launch.'
    }
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

    $start = New-ProductionProcessStartInfo `
        -FilePath $Executable `
        -ArgumentList $ArgumentList `
        -WorkingDirectory (Split-Path -Parent $Executable) `
        -CreateNoWindow `
        -RedirectStandardOutput `
        -RedirectStandardError

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    $stdoutTask = $null
    $stderrTask = $null
    try {
        if ($CancellationPath -and (Test-Path -LiteralPath $CancellationPath -PathType Leaf)) {
            throw [OperationCanceledException]::new('Media job was canceled.')
        }
        if ([DateTimeOffset]::UtcNow -ge $Deadline) {
            throw [TimeoutException]::new('Media job exceeded its deadline.')
        }
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
    Assert-MediaJobPrivatePaths -Job $Job
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

    Assert-MediaJobPrivatePaths -Job $Job
    Assert-MediaJobSourceUnchanged -Job $Job
    $partial = Get-Item -LiteralPath $Job.partialOutputPath -Force -ErrorAction Stop
    if ($partial.PSIsContainer -or $partial.Length -le 0) { throw 'Media output is empty.' }
    if ($partial.Length -gt [long]$Job.maxOutputBytes) { throw 'Media output exceeds maxOutputBytes.' }
    Assert-MediaJobPrivatePaths -Job $Job
    [IO.File]::Move([string]$Job.partialOutputPath, [string]$Job.readyOutputPath, $true)
    Write-MediaJobStatusAtomic -Job $Job -Status READY -OutputBytes $partial.Length
}

function Assert-MediaOutputCapacity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [Parameter(Mandatory)][long]$AvailableFreeSpaceBytes
    )

    $requiredBytes = Get-RequiredMediaCapacityBytes `
        -SourceSizeBytes ([long]$Job.sourceSize) `
        -MaxOutputBytes ([long]$Job.maxOutputBytes)
    if ($AvailableFreeSpaceBytes -lt $requiredBytes) {
        throw 'Insufficient space for source staging, maxOutputBytes, and the fixed free-space reserve.'
    }
}

function Get-RequiredMediaCapacityBytes {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][long]$SourceSizeBytes,
        [Parameter(Mandatory)][long]$MaxOutputBytes
    )

    if ($SourceSizeBytes -lt 0 -or $MaxOutputBytes -lt 0) {
        throw 'Media capacity components must not be negative.'
    }
    if ($SourceSizeBytes -gt [long]::MaxValue - $MaxOutputBytes) {
        throw 'Media capacity calculation would overflow.'
    }
    $workBytes = $SourceSizeBytes + $MaxOutputBytes
    if ($workBytes -gt [long]::MaxValue - $script:MinimumFreeSpaceReserveBytes) {
        throw 'Media capacity calculation would overflow.'
    }
    return $workBytes + $script:MinimumFreeSpaceReserveBytes
}

function Assert-MediaRemainingCapacity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][long]$RemainingSourceBytes,
        [Parameter(Mandatory)][long]$MaxOutputBytes,
        [Parameter(Mandatory)][long]$AvailableFreeSpaceBytes
    )

    $requiredBytes = Get-RequiredMediaCapacityBytes `
        -SourceSizeBytes $RemainingSourceBytes -MaxOutputBytes $MaxOutputBytes
    if ($AvailableFreeSpaceBytes -lt $requiredBytes) {
        throw 'Insufficient space for remaining source staging, output, and the fixed free-space reserve.'
    }
}

function Invoke-ValidatedMediaJob {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Job,
        [Parameter(Mandatory)]$ToolSet,
        [scriptblock]$GetAvailableFreeSpace = {
            param($Path)
            ([IO.DriveInfo]::new([IO.Path]::GetPathRoot($Path))).AvailableFreeSpace
        },
        [scriptblock]$ProbeAction = {
            param($Context)
            Invoke-PinnedMediaTool -Executable $Context.ToolSet.Ffprobe `
                -ArgumentList $Context.ToolArgList -Deadline $Context.Job.deadline `
                -CancellationPath $Context.Job.cancellationPath
        },
        [scriptblock]$TranscodeAction = {
            param($Context)
            Invoke-PinnedMediaTool -Executable $Context.ToolSet.Ffmpeg `
                -ArgumentList $Context.ToolArgList -Deadline $Context.Job.deadline `
                -CancellationPath $Context.Job.cancellationPath -OnPoll $Context.PollAction
        }
    )

    Assert-MediaJobMayContinue -Job $Job
    $privateStoreRoot = Assert-MediaJobUsesSinglePrivateStore -Job $Job
    $available = & $GetAvailableFreeSpace $privateStoreRoot
    Assert-MediaOutputCapacity -Job $Job -AvailableFreeSpaceBytes ([long]$available)
    $lease = Enter-MediaPrivateRootLease -Job $Job
    $ownedSource = $null
    $stagedSource = $null
    $completed = $false
    try {
        $ownedSource = Open-ValidatedMediaSource -Job $Job
        $staleStage = Join-Path $Job.stagingRoot "$($Job.jobId).source"
        Remove-Item -LiteralPath $staleStage -Force -ErrorAction SilentlyContinue
        $stagedSource = Copy-ValidatedMediaSourceToStage -Job $Job -OwnedSource $ownedSource `
            -GetAvailableFreeSpace $GetAvailableFreeSpace
        $executionJob = $Job.PSObject.Copy()
        $executionJob.sourcePath = $stagedSource.Path

        Assert-MediaJobMayContinue -Job $executionJob
        Assert-StagedMediaSourceUnchanged -Job $executionJob -StagedSource $stagedSource
        Write-MediaJobStatusAtomic -Job $executionJob -Status INSPECTING
        Assert-MediaToolSetUnchanged -ToolSet $ToolSet
        $probeContext = [pscustomobject]@{
            Job = $executionJob
            ToolSet = $ToolSet
            ToolArgList = New-FixedFfprobeArguments -Job $executionJob
        }
        Assert-MediaJobMayContinue -Job $executionJob
        $probe = & $ProbeAction $probeContext
        if ($probe.OutputTruncated -or
            [Text.Encoding]::UTF8.GetByteCount([string]$probe.StandardOutput) -gt 65536) {
            throw 'ffprobe JSON exceeded its bounded output limit.'
        }
        try { $probeJson = $probe.StandardOutput | ConvertFrom-Json -ErrorAction Stop }
        catch { throw 'ffprobe did not produce valid bounded JSON.' }
        if ($null -eq $probeJson -or $probeJson -is [array]) {
            throw 'ffprobe did not produce a JSON object.'
        }

        Write-MediaJobStatusAtomic -Job $executionJob -Status TRANSCODING
        $progress = @{ BufferingPublished = $false }
        $poll = {
            Assert-MediaJobPrivatePaths -Job $executionJob
            if (Test-Path -LiteralPath $executionJob.partialOutputPath -PathType Leaf) {
                $bytes = (Get-Item -LiteralPath $executionJob.partialOutputPath -Force).Length
                if ($bytes -gt [long]$executionJob.maxOutputBytes) {
                    throw 'Media output exceeds maxOutputBytes.'
                }
                if (-not $progress.BufferingPublished -and
                    $bytes -ge [long]$executionJob.initialBufferBytes) {
                    Write-MediaJobStatusAtomic -Job $executionJob -Status BUFFERING -OutputBytes $bytes
                    $progress.BufferingPublished = $true
                }
            }
        }
        Assert-MediaToolSetUnchanged -ToolSet $ToolSet
        Assert-MediaJobMayContinue -Job $executionJob
        Assert-StagedMediaSourceUnchanged -Job $executionJob -StagedSource $stagedSource
        $transcodeContext = [pscustomobject]@{
            Job = $executionJob
            ToolSet = $ToolSet
            ToolArgList = New-FixedFfmpegArguments -Job $executionJob
            PollAction = $poll
        }
        & $TranscodeAction $transcodeContext | Out-Null
        & $poll
        Assert-MediaJobMayContinue -Job $executionJob
        Assert-StagedMediaSourceUnchanged -Job $executionJob -StagedSource $stagedSource
        Complete-MediaJobAtomically -Job $executionJob
        $completed = $true
    } finally {
        if ($stagedSource) { $stagedSource.Dispose() }
        if ($ownedSource) { $ownedSource.Dispose() }
        if (-not $completed) {
            Remove-Item -LiteralPath $Job.partialOutputPath -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath (Join-Path $Job.stagingRoot "$($Job.jobId).source") `
            -Force -ErrorAction SilentlyContinue
        $lease.Dispose()
    }
}

Export-ModuleMember -Function Get-CanonicalPath,Test-PathBelowRoot,Assert-PathHasNoReparseComponent,Assert-MediaSourceSizeAllowed,Read-ValidatedMediaJob,Open-ValidatedMediaSource,Assert-MediaJobMayContinue,Assert-MediaJobUsesSinglePrivateStore,Copy-ValidatedMediaSourceToStage,Assert-StagedMediaSourceUnchanged,Assert-MediaJobPrivatePaths,Enter-MediaPrivateRootLease,Assert-MediaJobSourceUnchanged,New-FixedFfprobeArguments,New-FixedFfmpegArguments,Enter-SharedFolderWorkerLock,Assert-PinnedMediaToolSet,Assert-MediaToolSetUnchanged,Invoke-PinnedMediaTool,Write-MediaJobStatusAtomic,Complete-MediaJobAtomically,Get-RequiredMediaCapacityBytes,Assert-MediaRemainingCapacity,Assert-MediaOutputCapacity,Invoke-ValidatedMediaJob
