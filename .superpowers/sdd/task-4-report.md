# Task 4 Report - Conflict-Safe Mutations and Resumable Uploads

Date: 2026-07-17

Branch: `codex/shared-folder-portal`

Status: Implementation fixes complete; independent re-review required

## Review Outcome and Remediation

The initial Task 4 implementation commit `a40c95965b084ae1c77822bb7dc31e39cb9542af`
was independently rejected with two Critical and six Important findings. This pass addressed all
eight findings without marking the task complete:

- Native mutation roots and every retained root-to-leaf ancestor handle now deny delete sharing
  for the full validation-to-mutation window. Real Windows tests prove external source and
  ancestor renames fail after validation for rename and delete.
- Explicit replacement no longer relies on overwrite rename. Portable and native mutation and
  upload paths quarantine the exact observed target, verify its stable identity after the move,
  create the final name with no-replace semantics, preserve racers, and restore only when the
  visible name remains free.
- Owner-scoped Mongo mutation journals persist `PREPARED`, `TARGET_QUARANTINED`, `SOURCE_MOVED`,
  and `RESTORE_PENDING`. Bounded startup and pre-mutation reconciliation cover portable and native
  providers. Process-death tests cover every phase, including cleanup when quarantine is already
  gone and preservation when a racer blocks restoration.
- Upload finalization persists equivalent replacement phases in the owned upload session. Native
  and portable crash tests prove post-quarantine restoration and post-move completion.
- Unknown-length oversized request bodies map to `413` through the real filter/controller/service
  chain without advancing progress or retaining bytes.
- Appends use expiring instance/request leases plus optimistic Mongo versions. Recreated services
  truncate partial or full uncommitted bytes to the persisted offset on portable and native
  staging; a live lease cannot be stolen or truncated by another instance.
- Direct service validation rejects invalid/root mutations and invalid upload requests before
  filesystem or repository changes. Fresh access checks remain required. Missing, conflict, and
  unavailable native states are translated by explicit status classification, while unknown
  native failures fail closed as `503`.
- Status responses advertise the configured server chunk size and safe committed
  offset/length/SHA-256 proofs. The browser rehashes only committed slices before resume, uses the
  advertised chunk size, bounds transient retries, gates duplicate submit/drop, supports
  pause/resume, and waits for append settlement before a retried cancel. No whole-file prehash is
  performed.

## TDD Evidence

Observed RED/GREEN cycles in this remediation included:

- Browser tests first failed because the operation gate, configured chunk contract, prefix proof,
  retry, and workflow seam did not exist. The final mocked workflow tests exercise the same calls
  used by the page and prove configured chunking, transient retry, explicit replacement, duplicate
  gating, changed-file rejection, pause/resume, and cancellation settlement.
- Native/portable replacement tests first exposed unconditional overwrite behavior and missing
  durable phases. They passed after conditional quarantine, stable-identity checks, no-replace
  final creation, and journal reconciliation were introduced.
- Native cancel recovery initially remained pending after deletion because missing staging was not
  distinguishable from sharing failure. It passed after confirmed NTFS not-found became success
  while sharing/unavailable remained pending.
- Exact status tests exposed raw native failures and unavailable portable roots reported as `404`.
  They passed after explicit `400`/`404`/`409`/`503` translation and safe controller-envelope tests.
- The unknown-length request test initially allowed the streaming overflow to collapse into a
  conflict. It passed after a dedicated payload-too-large exception preserved `413` end to end.

Focused Windows/native command:

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
$env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST='true'
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  :website:test `
  --tests 'dev.christopherbell.sharedfolder.SharedFolderMutationServiceTest' `
  --tests 'dev.christopherbell.sharedfolder.SharedFolderUploadServiceTest' `
  --tests 'dev.christopherbell.sharedfolder.SharedFolderWriteControllerTest' `
  --tests 'dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundaryTest' `
  --tests 'dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeJnaIntegrationTest'
```

Result: 56 tests passed. This includes real JNA, junction, native ancestor pinning, native journal,
native upload finalization, native append recovery, and safe status-contract coverage.

## Final Verification

All Gradle state remained in external caches.

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
$env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST='true'
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  test
```

Result: 751 `website` tests and 93 `cbell-lib` tests passed (844 aggregate); 0 failed,
0 errored, 0 skipped.

```powershell
$tests = rg --files -g '*.test.js' website/src/test/js
node --test $tests
```

Result: 150 JavaScript tests passed; 0 failed, 0 skipped.

Additional verification:

- `node --check website/src/main/resources/static/js/shared-folder.js` passed.
- `git diff --check` passed (only expected line-ending notices).
- Worktree-local `.gradle` and `.gradle-task4` directories are absent.

## Deliberate Follow-Up

Task 4 retains physical delete by design. Task 5 replaces it with recycle behavior and connects
mutations to the persistent audit sink. Task 4 remains in progress until an independent reviewer
approves this remediation.
