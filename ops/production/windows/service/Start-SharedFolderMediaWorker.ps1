[CmdletBinding()]
param(
    [switch]$ValidateOnly,
    [string]$JobPath,
    [string]$SharedRoot = 'A:\Shared',
    [string]$SystemRoot = 'A:\Shared-System',
    [int]$PollMilliseconds = 500
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Production.SharedFolderWorker.psm1') -Force

try {
    if ($ValidateOnly) {
        if ([string]::IsNullOrWhiteSpace($JobPath)) { throw 'JobPath is required in validate-only mode.' }
        Read-ValidatedMediaJob -Path $JobPath -SharedRoot $SharedRoot -SystemRoot $SystemRoot | Out-Null
        exit 0
    }
    if ($PollMilliseconds -lt 100 -or $PollMilliseconds -gt 10000) {
        throw 'PollMilliseconds must be between 100 and 10000.'
    }

    $canonicalSystemRoot = Get-CanonicalPath -Path $SystemRoot
    $queueRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-jobs'
    $toolRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-tools'
    $tools = Assert-PinnedMediaToolSet -ToolRoot $toolRoot
    $lockPath = Join-Path (Join-Path $canonicalSystemRoot 'shared-folder-media-locks') 'worker.lock'
    $workerLock = Enter-SharedFolderWorkerLock -Path $lockPath
    try {
        while ($true) {
            $marker = Get-ChildItem -LiteralPath $queueRoot -Filter '*.ready' -File -Force |
                Sort-Object LastWriteTimeUtc, Name |
                Select-Object -First 1
            if ($null -eq $marker) {
                Start-Sleep -Milliseconds $PollMilliseconds
                continue
            }

            $jobPathForMarker = Join-Path $queueRoot ($marker.BaseName + '.json')
            $job = $null
            try {
                $job = Read-ValidatedMediaJob -Path $jobPathForMarker `
                    -SharedRoot $SharedRoot -SystemRoot $canonicalSystemRoot
                if (Test-Path -LiteralPath $job.cancellationPath -PathType Leaf) {
                    Write-MediaJobStatusAtomic -Job $job -Status CANCELED -FailureCategory canceled
                } else {
                    Invoke-ValidatedMediaJob -Job $job `
                        -FfprobeExecutable $tools.Ffprobe -FfmpegExecutable $tools.Ffmpeg
                }
            } catch [OperationCanceledException] {
                if ($job) {
                    Write-MediaJobStatusAtomic -Job $job -Status CANCELED -FailureCategory canceled
                }
            } catch [TimeoutException] {
                if ($job) {
                    Write-MediaJobStatusAtomic -Job $job -Status TIMED_OUT -FailureCategory timed_out
                }
            } catch {
                if ($job) {
                    if ($_.Exception.Message -like '*Insufficient space*') {
                        Write-MediaJobStatusAtomic -Job $job -Status INSUFFICIENT_SPACE `
                            -FailureCategory insufficient_space
                        continue
                    }
                    $category = if ($_.Exception.Message -like '*maxOutputBytes*') {
                        'output_limit'
                    } elseif ($_.Exception.Message -like '*Source metadata changed*') {
                        'source_changed'
                    } else {
                        'worker_failure'
                    }
                    Write-MediaJobStatusAtomic -Job $job -Status FAILED -FailureCategory $category
                } else {
                    Write-Warning "Rejected media job descriptor: $($_.Exception.Message)"
                }
            } finally {
                Remove-Item -LiteralPath $marker.FullName -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $jobPathForMarker -Force -ErrorAction SilentlyContinue
                if ($job -and (Test-Path -LiteralPath $job.partialOutputPath)) {
                    Remove-Item -LiteralPath $job.partialOutputPath -Force -ErrorAction SilentlyContinue
                }
            }
        }
    } finally {
        $workerLock.Dispose()
    }
} catch {
    Write-Error -ErrorRecord $_
    exit 1
}
