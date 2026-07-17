# Task 4 Report - Conflict-Safe Mutations and Resumable Uploads

Date: 2026-07-17

Branch: `codex/shared-folder-portal`

Status: Complete

## Outcome

The shared-folder portal now supports write-capability-gated folder creation, rename, move,
explicit replacement, physical delete, and owner-scoped resumable uploads. Portable providers
retain their documented best-effort filesystem boundary. Enabled Windows deployments use retained
native directory handles for mutation and upload operations with no path-based NIO fallback.

The browser exposes write controls only to effective writers, uploads in 8 MiB SHA-256-verified
chunks, shows progress, supports cancel and drag/drop, and resumes matching local files from
non-secret session metadata. The exact chunk route has its dedicated streaming limit and the
upload, mutation, and transcode rate-limit rules precede the generic API mutation rule.

## Implemented Contract

- Fresh `requireWrite()` authorization on every mutation and upload operation.
- Opaque observed tokens, held-source rechecks, explicit replacement plus current target token,
  case-only rename support, race-safe no-replace behavior, and `409 Conflict` mapping.
- Native `NtCreateFile`, `NtSetInformationFile`, write/flush/truncate, handle-relative rename and
  delete, stable volume plus 128-bit file identity, same-volume enforcement, and fail-closed
  capacity arithmetic.
- Owner-scoped optimistic-versioned upload sessions with private staging, ordered/idempotent
  chunks, rollback after physical or persistence failure, and durable `FINALIZING` and
  `CANCEL_PENDING` reconciliation.
- `413 Payload Too Large` for configured file/chunk limits and `507 Insufficient Storage` when the
  configured free-space reserve cannot be preserved.
- Create/status/chunk/complete/cancel HTTP routes, no-store responses, safe relative DTOs, and raw
  servlet streaming for octet-stream chunks.

## Test-Driven Development Evidence

The initial mutation, upload, controller, filter, and native-boundary RED tests were supplied by
the preceding Task 4 implementation pass. The takeover re-ran the inherited focused suite before
continuing. The original failure logs were not preserved in this report, so the entries below
distinguish inherited evidence from RED/GREEN cycles directly observed during completion.

### Inherited RED/GREEN coverage reverified here

Command family:

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
.\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.sharedfolder.*' `
  --tests 'dev.christopherbell.configuration.RequestSizeLimitFilterTest' `
  --tests 'dev.christopherbell.configuration.RateLimitFilterTest'
```

Inherited REDs covered missing mutation/upload services, incomplete native create/write/finalize
operations, stale/race replacement behavior, append rollback, two-phase terminal reconciliation,
route binding, route-specific request limits, and dedicated rate-limit ordering. The final focused
classes contain 41 tests: mutation service 5, upload service 10, write controller 3, native
mutation boundary 6, real native JNA 6, request-size filter 4, and rate-limit filter 7. All 41 pass.

### Direct RED/GREEN: configured file limit and disk reserve

RED command:

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
.\gradlew.bat --no-daemon :website:test `
  --tests 'dev.christopherbell.sharedfolder.SharedFolderUploadServiceTest.createMapsConfiguredFileLimitTo413AndPortableReserveRefusalTo507' `
  --tests 'dev.christopherbell.sharedfolder.SharedFolderUploadServiceTest.enabledWindowsReserveRefusalUses507'
```

RED result: 2 tests failed because the service mapped both conditions to the generic 409 upload
conflict response.

GREEN command: the same two-test command after separating file-limit and storage-reserve errors.

GREEN result: 2 tests passed, 0 failed. Configured file overflow maps to 413; portable and native
reserve refusal map to 507.

### Other directly observed RED/GREEN cycles

- A real native create/write/delete test first failed while native mutation methods were absent;
  it passed after implementing relative open/create, write, flush, truncate, rename, and delete.
- A real staging reopen/finalize test first failed when staging was reopened without mutation
  access; it passed after using the mutation handle and same-volume handle-relative finalization.
- File identity tests first failed because enumeration used a synthetic volume identifier; they
  passed after reading the real volume serial for listed and opened entries.
- Portable target-creation race coverage first exposed Windows `ATOMIC_MOVE` replacement without
  `REPLACE_EXISTING`; it passed after no-replace moves used explicit create-new semantics.
- Upload rollback tests first failed after partial append and Mongo progress-save failures; they
  passed after truncate/flush rollback and serialized session transitions.
- Finalization/cancel recovery tests first failed after simulating the final Mongo save failure;
  they passed after durable pending states and stable-identity reconciliation.
- A MockMvc octet-stream chunk PUT first returned 415; it passed after the controller consumed the
  raw servlet input stream.
- A request-size test first applied the upload limit to non-chunk descendants; it passed after
  matching only the exact PUT chunk route, including unknown-length streams.
- JavaScript tests first failed on missing write/resume helpers; they passed after capability,
  progress, terminal-state, resume-match, and explicit-replacement helpers were added.

## Final Verification

All Gradle commands used the external user home and final commands also used the external project
cache `C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal`.

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
$env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST='true'
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  :website:test
```

Result: 726 tests passed, 0 failed, 0 errored, 0 skipped. The six real Windows native JNA tests
all ran, including both explicitly enabled junction mutation/race cases.

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  :website:jsTest
```

Result: 144 tests passed, 0 failed, 0 skipped.

```powershell
node --check website/src/main/resources/static/js/shared-folder.js
node --check website/src/main/resources/static/js/lib/shared-folder.js
node --check website/src/main/resources/static/js/lib/api.js
node --check website/src/test/js/shared-folder.test.js
```

Result: all four syntax checks passed.

Final repository checks: `git diff --check` passed, and the worktree-local Gradle cache was removed
after all Gradle processes completed and verified absent.

## Deliberate Follow-Up

Task 4 retains physical delete by design. Task 5 replaces that operation with recycle behavior and
wires mutations into the persistent audit sink. No absolute host path is returned by the Task 4
API or browser DTOs.
