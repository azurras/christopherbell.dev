# Task 6 Report - Direct Media and Progressive Derivative Delivery

Date: 2026-07-21

Branch: `codex/shared-folder-portal`

Status: Complete; independent whole-change review approved with zero blockers and zero warnings

## Delivered Behavior

- The browser first attempts the original authenticated media URL. A native playback error requests
  one fixed `VIDEO_MP4` or `AUDIO_M4A` fallback profile; the website does not guess codec support
  from a filename and never launches or accepts a media executable or argument list.
- Owner-scoped Mongo jobs enforce bounded global/per-account admission, source-revision cache keys,
  fixed output reservations, timeouts, cancellation, shared completed-cache reuse, and persisted LRU
  eviction. ADMIN and every current shared-folder reader may use a valid completed cache.
- Spring publishes exactly one active worker descriptor through a private fixed protocol. Admission,
  cancellation, reconciliation, and scheduler publication use one lifecycle lock. Interrupted
  descriptor publication is idempotently replayed; unattended terminal jobs advance the queue.
- Active output streams sequentially after the initial buffer. Atomic partial-to-ready publication is
  reconciled without truncating the response. Completed output supports one normal HTTP range while
  a reader lease prevents concurrent eviction from selection through asynchronous response closure.
- Missing worker status means no update. Malformed or oversized descriptor/status documents, source
  revision changes, output-limit violations, and private-storage outages remain distinct. Terminal
  protocol failures are audited; translated infrastructure failures retain their original cause.

The isolated FFmpeg/ffprobe worker, cross-process worker lock, fragmented media fixtures, and live
media-tool verification remain Task 7 scope.

## TDD and Concurrency Evidence

Observed RED/GREEN cycles included:

- A range-selection regression first failed compilation because `ReadySelection` did not own or
  close a reader lease. The completed selection now holds an idempotent lease through response-body
  completion.
- An oversized ready-output test first failed because absence and output-limit violation shared a
  sentinel. `OptionalLong` now represents absence only; typed output-limit failure terminalizes the
  job.
- Four new failure-boundary tests initially all failed: partial descriptor JSON escaped as an
  unchecked Jackson failure, malformed/oversized worker status remained active, and cancellation
  discarded its storage cause. Typed descriptor/status failures and cause-preserving translations
  made all four pass.
- Oversized descriptor and status-document tests initially failed as generic storage outages. A
  bounded-document size exception is now translated to `descriptor_invalid` or
  `invalid_worker_status`.
- A disposable pre-fix worktree at `b608f48a` replayed scheduler recovery, stale READY source,
  partial-to-ready handoff, and missing-leaf behavior. All four tests failed before the fixes and
  pass on the completed branch.
- A barrier placed in the pre-fix cancellation/admission publication check produced two descriptors,
  `job-2.json` and `job-3.json`, where one was required. The committed coordinated regression proves
  cancellation and admission cannot overlap lifecycle decisions under the shared lock.
- A second coordinated regression blocks range selection after lease acquisition and proves a
  concurrent eviction cannot delete the ready output.

All concurrency tests use latches and bounded futures; none depend on unbounded sleeps.

## Verification

The final focused gate covered all media classes, the private filesystem boundary, rate limits, and
the complete browser suite. It passed with the expected focused-only junction skip and all 165
JavaScript tests.

The final clean full gate used external Gradle caches and forced the real Windows junction test:

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
$env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST='true'
.\gradlew.bat --no-daemon --rerun-tasks `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  test :website:jsTest --console=plain
```

Result: `BUILD SUCCESSFUL` in 1m 43s. All 998 Java tests and 165 JavaScript tests passed with zero
failures, errors, or skips. `git diff --check` passed. Production port 8080 and the live Windows
service were not touched.

## Independent Review

The initial whole-change review rejected ten Important and one Minor issue. Later reviews found
restart reconciliation, lifecycle-lock, eviction-lease, atomic publication, stale-source, protocol
taxonomy, causal-context, and evidence gaps. Every blocker received regression coverage and a fresh
focused/full verification cycle.

Final review range:
`2d38909c5c7e9071e50a8d130eb9382ec54e2b5c..63849159`

Final verdict: **APPROVED - 0 blockers, 0 warnings**.

## Commits

- `32958f7b` - Add progressive shared folder media delivery
- `43469a38` - Reconcile media completion during streaming
- `b608f48a` - Harden shared folder media delivery
- `b8695a3b` - Harden shared folder media recovery
- `1e3ef033` - Make media worker failures explicit
- `63849159` - Close media protocol and lifecycle races
