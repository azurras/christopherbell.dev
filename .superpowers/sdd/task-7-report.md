# Task 7 Report: Isolated LocalService Media Worker

## Status

Complete in the isolated `codex/shared-folder-worker` worktree. The implementation is committed locally and has not been pushed. No production service, media tool, shared-folder root, or port was changed.

Implementation commit: `9b5b9df5` (`Add isolated shared folder media worker`)

## Delivered

- Added `ChristopherBellMediaWorker.xml` with the `ChristopherBellMediaWorker` service identity, `NT AUTHORITY\LocalService`, below-normal priority, delayed automatic startup, restart recovery, and bounded size-based log rotation.
- Added `Start-SharedFolderMediaWorker.ps1`, which holds one exclusive worker lock, consumes ready-marked jobs, validates each descriptor, publishes bounded atomic statuses, invokes only pinned tools, supports progressive `BUFFERING`, and records cancellation, timeout, low-space, output-limit, source-change, and general worker failures.
- Added `Production.SharedFolderWorker.psm1` with strict Task 6 schema validation, canonical root-derived paths, reparse rejection, source size/mtime checks, deadline and size limits, fixed FFprobe/FFmpeg profiles, bounded concurrent process-output drains, `ProcessStartInfo.ArgumentList`, process-tree termination, pinned executable hash validation, atomic status replacement, and atomic partial-to-ready publication.
- Added `Production.SharedFolder.psm1` for exact `A:\Shared` and `A:\Shared-System` roots, idempotent private-directory creation, inheritance-free ACLs, SYSTEM/Administrators full control, LocalService read-only originals/tools, LocalService modify access only on media-private job/output/status/cancel/log/lock roots, pinned media-tool installation, and idempotent WinSW worker installation/refresh.
- Added the pinned FFmpeg 8.0.1 essentials manifest. The installer verifies the HTTPS package SHA-256 before extraction, rejects archive traversal, records extracted executable hashes, and the worker revalidates those hashes before starting.
- Extracted unchanged MongoDB/website installation into `Install-WebsiteService`, delegated the worker install from `Install-ProductionRuntime`, and loaded `Production.SharedFolder` before `Production.Install` in `prod.ps1`.
- Protected website service-control files from the LocalService worker while granting that identity read/execute only on its own WinSW executable, XML, startup script, and worker module.
- Updated the Windows production runbook with worker topology, pinned-tool verification, roots, ACL boundaries, and startup behavior.

## TDD Evidence

Initial focused RED:

- Command: `Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1,.\ops\production\windows\tests\Production.Install.Tests.ps1,.\ops\production\windows\tests\Production.Command.Tests.ps1 -Output Detailed`
- Result: 20 passed, 21 failed.
- Expected causes: missing worker/shared-folder modules and service files, missing installer delegation, and missing command import ordering.

Focused GREEN:

- Command: `Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1 -Output Detailed`
- Result: 27 passed, 0 failed, 0 skipped.
- Coverage includes LocalService/priority XML, exact accepted schema, unknown schema/profile, extra fields, expired deadline, traversal, reparse points, altered source size/mtime, duplicate lock, validate-only nonzero exit, fixed MP4/M4A/FFprobe arguments, bounded process output, deadline tree termination, atomic status schema, exact roots, ACLs, idempotent directories/tools, manifest validation, and changed-tool hash rejection.

## Final Verification

- Full Windows Pester suite: 114 passed, 0 failed, 2 skipped. The two skipped tests are the pre-existing elevation-gated NTFS integration cases.
- `git diff --check`: passed before commit.
- PowerShell parser over every changed `.ps1`/`.psm1`: passed.
- Worker XML and media-tool JSON parsing: passed.
- `pwsh -File ops/production/windows/prod.ps1 help`: exited 0.
- PSScriptAnalyzer: not installed, so analyzer verification was skipped.

## Known Gaps and Concerns

- The assignment prohibited installing tools or services. Therefore live WinSW XML validation, installed LocalService identity/priority verification, real FFprobe/FFmpeg transcoding, real ACL token checks, worker startup, and progressive browser playback remain deployment-phase checks.
- `prod.ps1 install -WhatIf` could not be exercised in this non-elevated session because the pre-existing installer contract calls `Assert-Administrator` before the `WhatIf` branch. It exited 1 with `This operation requires elevated PowerShell.` No mutation occurred.
- The two skipped NTFS integration tests require elevation and remain unexecuted here.
- The pinned upstream archive URL is externally hosted; production installation will fail closed if it becomes unavailable or its bytes no longer match the committed SHA-256.

## Fix Wave: Security and Effect-Boundary Review

### Status

All four Important review findings are resolved in local commit `98ab8081` (`Harden shared folder media worker boundaries`). The fix wave remains local and has not been pushed. No production file, service, media tool, ACL, shared-folder root, or port was changed.

### Findings Resolved

- The worker now retains and validates the opened source object by final path and file identity, copies only from that handle into the private staging root, and gives FFprobe/FFmpeg only the staged path. Private mutation roots are reparse-checked at effect boundaries and held against coordinated directory substitution while a job is active.
- Descriptor validation now enforces the fixed two-hour deadline, 50 GiB output ceiling, 2 MiB initial-buffer ceiling, buffer/output relationship, and a 100 GiB free-space reserve independent of the descriptor. FFmpeg receives the matching `-fs` ceiling, with poll-time overshoot enforcement retained.
- Filesystem, ACL, archive, process, and service effects now have focused effect-boundary tests. Real ACL and service-token checks remain explicitly elevation-gated.
- Media tools are installed into immutable version directories and activated through an atomic pointer file. Existing workers retain their startup-selected version until restart, while exact executable hashes are revalidated immediately before both probe and transcode launches. Interrupted and corrupt refreshes cannot replace the active version with a partial installation.

### TDD Evidence

Fix-wave RED command:

`Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1,.\ops\production\windows\tests\Production.Install.Tests.ps1,.\ops\production\windows\tests\Production.Command.Tests.ps1,.\ops\production\windows\tests\Production.Security.Integration.Tests.ps1 -Output Detailed`

- Result: 50 passed, 14 failed, 3 skipped.
- Expected failures covered missing fixed bounds and `-fs`, missing retained-source/staging and private-root lease boundaries, unsafe last-moment publication, missing reserve/overshoot enforcement, missing ACL/service effect seams, and missing versioned atomic tool refresh.

Fix-wave focused GREEN command:

`Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1,.\ops\production\windows\tests\Production.Install.Tests.ps1,.\ops\production\windows\tests\Production.Command.Tests.ps1,.\ops\production\windows\tests\Production.Security.Integration.Tests.ps1 -Output Normal`

- Result: 67 passed, 0 failed, 3 skipped.
- The three skips are the Windows elevation-gated ACL/service-token integration cases.

Complete Windows GREEN command:

`Invoke-Pester .\ops\production\windows\tests -Output Normal`

- Result: 132 passed, 0 failed, 3 skipped.
- PowerShell parser validation passed for all 20 Windows `.ps1` and `.psm1` files.
- XML parsing passed for 2 files; JSON parsing passed for 2 files.
- `git diff --check` passed.

### Files Changed

- `ops/production/windows/modules/Production.SharedFolder.psm1`
- `ops/production/windows/modules/Production.SharedFolderWorker.psm1`
- `ops/production/windows/service/Start-SharedFolderMediaWorker.ps1`
- `ops/production/windows/tests/Production.SharedFolderWorker.Tests.ps1`
- `ops/production/windows/tests/Production.Security.Integration.Tests.ps1`
- `.superpowers/sdd/task-7-report.md`

### Commits

- `98ab8081` - worker boundary, resource-limit, effect-seam, immutable-tool, and regression-test fixes.
- The report-only commit follows this section and contains no production code.

### Remaining Concerns

- Three real ACL/service-token integration tests were skipped because this PowerShell session is not elevated. The deterministic ACL/service effect tests and LocalService XML assertions passed, but effective-token verification still requires an elevated deployment-phase run.
- The assignment prohibits installing services/tools or mutating production, so live WinSW refresh, real FFmpeg/FFprobe execution, and progressive browser playback remain deployment-phase checks.

## Second Fix Wave: Complete Job Boundary and Real Process Evidence

### Status

Both remaining Important findings are resolved in local commit `04d2dcd3` (`Bound media worker staging lifecycle`). The change remains local and has not been pushed. No production service, media tool, ACL, shared-folder root, or port was changed.

### Findings Resolved

- The worker now rejects transcode sources above the fixed 10 GiB shared-folder limit and safely rejects source-size/capacity values outside signed 64-bit arithmetic. Because staging and output are contractually beneath the same non-reparse private system root, one explicit invariant owns their capacity domain. Admission requires source staging bytes plus maximum output bytes plus the fixed 100 GiB reserve before copying begins.
- Retained source bytes are copied in fixed 64 KiB chunks. Deadline, cancellation, declared source size, and same-store remaining capacity are checked before and after every chunk, including the final chunk. Every failed copy removes its partial staged file, and the retained staged handle's final path, byte count, volume, and file identity are revalidated before probe, transcode, and publication.
- Cancellation and deadline are checked immediately before `Process.Start`, as well as before probe/transcode actions and after process completion. Existing in-loop cancellation/deadline checks still kill the complete owned process tree.
- Controlled PowerShell child processes now exercise the real probe/transcode process runner. A post-start cancellation marker kills a live parent/child process tree and leaves no staged, partial, or ready bytes. The success process uses bounded filesystem events to prove ready output is absent while the child remains active, emits oversized stdout/stderr through the bounded drain, exits, and only then permits READY publication.

### TDD Evidence

Initial second-wave focused RED command:

`Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1 -Output Normal`

- Result: 46 passed, 9 failed, 0 skipped.
- Expected failures covered the 10 GiB source ceiling, pre-start cancellation/deadline rejection, source-plus-output-plus-reserve capacity, overflow-safe aggregation, the single-private-store invariant, and chunked staging cancellation/deadline/free-space cleanup.
- Both real controlled-process tests passed at this baseline and were recorded as characterization evidence rather than manufactured failures.

Follow-up RED:

- Result: 55 passed, 1 failed, 0 skipped.
- The remaining failure proved a one-chunk copy did not recheck free-space reserve after its final write.

Focused GREEN command:

`Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1 -Output Normal`

- Result: 56 passed, 0 failed, 0 skipped.

Reviewer-covering GREEN command:

`Invoke-Pester .\ops\production\windows\tests\Production.SharedFolderWorker.Tests.ps1,.\ops\production\windows\tests\Production.Install.Tests.ps1,.\ops\production\windows\tests\Production.Command.Tests.ps1,.\ops\production\windows\tests\Production.Security.Integration.Tests.ps1 -Output Normal`

- Result: 78 passed, 0 failed, 3 skipped.
- The three skips are the existing Windows elevation-gated ACL/service-token integration cases.

Complete Windows GREEN command:

`Invoke-Pester .\ops\production\windows\tests -Output Normal`

- Result: 143 passed, 0 failed, 3 skipped.
- PowerShell parser validation passed for all 20 Windows `.ps1` and `.psm1` files.
- XML parsing passed for 2 files; JSON parsing passed for 2 files.
- `git diff --check` passed.

### Files Changed

- `ops/production/windows/modules/Production.SharedFolderWorker.psm1`
- `ops/production/windows/tests/Production.SharedFolderWorker.Tests.ps1`
- `.superpowers/sdd/task-7-report.md`

### Commits

- `04d2dcd3` - complete storage/deadline/cancellation boundary and real controlled-process evidence.
- The report-only commit follows this section and contains no production code.

### Remaining Concerns

- Three real ACL/service-token integration tests remain skipped because this PowerShell session is not elevated.
- The assignment prohibits production mutation, so live WinSW service execution, real pinned FFmpeg/FFprobe transcoding, and progressive browser playback remain deployment-phase checks. The new child-process tests use controlled PowerShell executables to exercise the real process runner deterministically.
