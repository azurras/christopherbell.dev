# Task 4 Report - Conflict-Safe Mutations and Resumable Uploads

Date: 2026-07-17

Branch: `codex/shared-folder-portal`

Status: Sixth review rejected; seventh remediation implementation and verification complete; fresh independent review required

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

## Second Independent Re-review and Remediation

The independent re-review of commit `7975b9e8e0818ffaca7100c03042538f35e046b7`
rejected Task 4 again with four Critical and four Important findings. This remediation remains
review-rejected pending another fresh review and closes the reported gaps as follows:

- Mutation recovery and upload finalization now use bounded writer leases. Live PREPARED and
  TARGET_QUARANTINED work is invisible to competing recovery, while expired work must be claimed by
  optimistic version before any physical action. Portable and real Windows-native latch tests cover
  mutation, replacement upload, and non-replacement upload windows.
- Portable mutation and upload replacement reject non-empty directories before displacement and
  recheck quarantine emptiness before source/staging movement. Regular-file replacement identities
  include SHA-256, detecting an in-place same-size edit that stable metadata alone missed.
- An explicitly observed replacement target that disappears returns 409 before source/staging
  movement in portable and native modes. The original source or private upload staging remains
  intact.
- Portable case-only rename is one atomic provider operation. Injected failure and the Windows
  regression prove the source never becomes a visible random UUID.
- Portable and real Windows-native append tests simulate Mongo committing ACTIVE progress and then
  throwing. The service reloads matching durable chunk proof, returns consistent progress, and an
  identical retry neither truncates nor duplicates bytes. A proven APPENDING result is reconciled
  only for the exact writer lease after that lease is durably expired.
- Append id, offset, body, and required SHA-256 digest are validated as 400 before authorization,
  repository, or native-boundary interaction. Real service tests classify native missing as 404,
  collision/share as 409, and unknown/inactive failures as 503.

Observed RED runs failed each new regression before production changes: live work was recovered by
competitors, non-empty/modified/disappeared targets were displaced, case-only failure stranded a
UUID name, final progress-save exceptions truncated committed bytes, malformed append requests
returned conflict or threw, and native upload statuses collapsed to 409. The corresponding focused
GREEN runs now pass.

The final full verification for this second remediation used the external Gradle caches and
`SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true`:

- `gradlew.bat --no-daemon --project-cache-dir ... test`: 767 `website` tests and 93 `cbell-lib`
  tests passed (860 aggregate); 0 failed, 0 errored, 0 skipped.
- `gradlew.bat --no-daemon --project-cache-dir ... :website:jsTest`: 150 passed; 0 failed,
  0 skipped.
- The focused mutation/upload service run passed all 48 tests, including real native lease,
  recovery, append acknowledgement, replacement integrity, and target-disappearance coverage.

Task 4 remains in progress and requires a fresh independent review of the new remediation commit.

## Third Independent Re-review

The whole-change review of `fc7847a06276897ea42f12b79d85942b55177436`
rejected Task 4 with four Critical, four Important, and one Minor finding. The active remediation
scope is native leaf write-share denial, renewable token/version fencing, pre-cleanup APPENDING
claim, provider-safe case-only rename, exact native and portable upload status classification,
contained pre-created private-root operations, canonical case-insensitive replacement spelling,
and removal of retry from non-idempotent upload-session creation. Task 4 remains review-rejected;
RED/GREEN and final verification evidence will be appended after implementation.

## Third Remediation RED/GREEN Evidence

The fourth review candidate closes every finding reported against `fc7847a0` while preserving all
earlier regression groups:

- Native leaf write-denial and renewable-fencing regressions failed before the dedicated
  read-share-only leaf open, repository renewal queries, time-or-byte digest heartbeats, and
  pre-transition fences. The fake bridge, real JNA, short-lease mutation, upload-finalization, and
  append-recovery tests pass after those changes.
- The stale APPENDING reconciler test failed before recovery first claimed the exact expired lease.
  It now proves a losing service cannot truncate bytes after another instance restores ACTIVE and
  begins a new append.
- Case-sensitive spelling tests failed before strict no-replace provider behavior. Both a
  differently cased collision and a target-creation race now preserve both files; real Windows
  case-only coverage remains green.
- Native create/append/complete/cancel tests initially collapsed status zero, unknown statuses, or
  missing replacement targets into incorrect conflict/not-found results. Exact missing, conflict,
  and unavailable classification now passes through the real services.
- The checked private capability test first failed to compile because staging/quarantine services
  still accepted raw paths. Substitution tests then exposed the Windows short-path versus canonical
  root spelling. The final boundary uses the captured canonical root, rechecks root and every
  ancestor immediately before and after each operation, and rejects direct-child substitution,
  root substitution, ancestor substitution, mounted children, unsupported providers, and a real
  Windows junction before the callback runs. No outside write or delete occurs.
- Portable lifecycle recovery tests first returned a normal pending status after private storage
  disappeared. Cancel-pending, ordinary finalization, and replacement-finalization status recovery
  now propagate the typed unavailable result as 503.
- Portable/native canonical replacement tests were RED because `Target.bin` requested as
  `target.bin` either conflicted or returned the caller spelling. Both services now retain the
  observed canonical spelling.
- Four browser regressions were RED before the final frontend changes: case-insensitive resume was
  rejected, canonical replacement used the local spelling, committed-prefix resume was refused,
  and move used the lowercase name. They pass with canonical listing propagation and prefix proof.
  A failing upload-session POST is attempted exactly once.

An initial whole-Java run reported one suite-level failure in
`durableReplacementRacerLeavesRestorePendingWithEveryPayloadPreserved` (884 of 885 passed). The
same test passed immediately in isolation and the complete 25-test mutation class passed unchanged.
The final clean whole-suite run below includes that regression and all newly added tests.

## Third Remediation Final Verification

All Gradle state remained in the external user and project caches. The real Windows junction flag
was enabled for the whole Java run.

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
$env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST='true'
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  test --console=plain
```

Result: BUILD SUCCESSFUL; 794 `website` tests and 93 `cbell-lib` tests passed (887 aggregate),
with 0 failures, 0 errors, and 0 skipped. The portable private-boundary class passed all 8 tests,
including the explicitly enabled real Windows junction regression.

```powershell
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  :website:jsTest --console=plain
```

Result: BUILD SUCCESSFUL; 152 JavaScript tests passed with 0 failures and 0 skipped. The focused
shared-folder browser file passed all 17 tests during remediation.

Additional verification:

- `node --check` passed for the shared-folder page module, library module, and test file.
- `git diff --check` passed with only expected line-ending notices.
- No worktree-local `.gradle*` directory exists.
- The progress ledger remains Task 4 in progress/review-rejected until a fresh reviewer approves
  the whole change from `8602985d` through the new review commit.

## Fourth Independent Re-review

The whole-change review of local commit `0675a6db` rejected Task 4 with three Critical, seven
Important, and two Minor findings. The commit was not pushed. Confirmed remediation scope is:

- atomic exact-expiry claims for mutation and finalization leases;
- renew-before-mutate append fencing and exclusive native write handles;
- provider-backed private leaf capabilities that reject symlink, hardlink, and substitution races;
- 409 for initially disappeared explicit replacement targets;
- typed native missing/conflict/unavailable mapping in normal and recovery paths;
- authoritative server-session matching before browser resume; and
- deterministic replacement-racer identity/test behavior.

Two review suggestions do not change Task 4 scope. Scheduled unattended expired-staging cleanup is
already an explicit Task 8 requirement in the primary Builder plan. Universal validation before
authorization contradicts the approved Task 4 design, which deliberately grants that precedence
to append id/offset/body/digest validation while other protected operations authorize first.

Task 4 remains review-rejected while the fifth review candidate is developed and verified.

## Fourth-Review Remediation RED/GREEN Evidence

The fifth candidate addresses every confirmed fourth-review finding while retaining the two scoped
technical rebuttals above:

- The mutation and finalization renewal-race tests first failed to compile without exact repository
  claim methods. Exact token/state/phase/expiry compare-and-set updates now prevent a stale snapshot
  from stealing a renewed lease, and both services reload the claimed token as proof.
- The blocked-read append regression first failed to compile without a pre-write seam. Portable and
  native append now renew after a blocking read and before truncate, write, force/flush, and cleanup;
  losing the lease leaves staging unchanged. Native staging files deny external write/delete sharing.
- Removing raw private-leaf callbacks initially left both services unable to compile. The replacement
  API retains no-follow `FileChannel` capabilities and exclusive write locks, rejects symlinks,
  hardlinks, reparse points, unsupported providers, and identity substitution, and owns private
  moves/deletes without returning a `Path`. All 11 portable boundary tests pass, including the real
  Windows junction test and a retained-channel substitution race that leaves the outside file intact.
- The real native staging regression initially failed because restricting the generic create primitive
  also restricted retained directory handles and caused `STATUS_SHARING_VIOLATION` on unrelated
  quarantine renames. File creation now uses read-only sharing while directory creation preserves
  directory-compatible sharing. A real-JNA regression proves the held staging handle rejects an
  external write without blocking unrelated quarantine.
- Portable and native initial replacement-disappearance tests were RED before explicit missing-target
  handling. Both now return 409 while preserving the source or upload staging bytes.
- Native status-zero and unavailable recovery tests were RED when metadata failures were treated as
  missing or were classified by call-site booleans. Typed missing/conflict/unavailable failures now
  propagate 404/409/503 through ordinary and recovery paths.
- The forged local browser resume test failed with `Missing expected rejection` before status
  revalidation. Resume now compares the authoritative server parent, name, size, and destination
  before sending any bytes.
- Replacement racer tests no longer depend on provider-specific `Files.move` collision behavior.
  The two mutation racers and the upload replacement racer passed five forced runs each (15 test
  executions) without a rerun-only acceptance. Null-file-key identity fallback now includes creation
  time, size, last-modified time, and item kind.

The duplicate import was removed. Scheduled unattended expiry cleanup remains Task 8, and the
approved append-only validation precedence remains unchanged.

## Fifth Candidate Verification Before Review

All Gradle state remained in the external user and project caches, and
`SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true` was set for native/junction runs.

- Focused mutation, upload, portable-boundary, and real-JNA classes: 110 tests passed.
- Full Java run: 804 `website` tests and 93 `cbell-lib` tests passed (897 aggregate), with 0
  failures, 0 errors, and 0 skipped. A second full `--rerun-tasks` execution passed the same 897
  tests from fresh task execution.
- Full JavaScript run: 153 tests passed, with 0 failures and 0 skipped.
- `node --check` passed for the touched shared-folder library and test files.
- `git diff --check` passed with only expected line-ending notices.
- No worktree-local `.gradle*` directory exists.

Task 4 remains in progress and review-rejected until the fifth candidate is committed, independently
reviewed across `8602985d..HEAD`, approved with no Critical or Important findings, and pushed.

## Fifth Independent Re-review

The whole-change review of local commit `09a0e7669c80e3c025137352caefbd4c62527495`
rejected Task 4 with three Critical and four Important findings. The commit was not pushed. The
confirmed remediation scope was atomic claim version fencing, binding portable private operations
to the named retained leaf, cleaning an unsafe move-out substitution from the visible destination,
provider-backed stable identity, consistent 409 semantics for initially disappeared replacement
targets, exact missing-only native private-directory creation, and reserve accounting for temporary
chunk duplication.

The reviewer also confirmed the existing scope decisions: unattended expiry cleanup remains a
later Task 8 requirement, and append input validation retains its approved precedence before
authorization. Task 4 remains review-rejected pending a new whole-change review.

## Fifth-Review Remediation RED/GREEN Evidence

Each confirmed finding received a focused regression before its production change:

- Atomic mutation, append, and finalization lease-claim tests failed because the database updates
  changed token/expiry without changing `@Version`. Claims now increment the version, and recovery
  reloads exact durable claim proof before physical work.
- Create-new and retained-leaf substitution tests failed because the callback channel was not
  proven to belong to the named private leaf. Stable provider identity is mandatory; Windows uses
  a retained native leaf handle as the file-channel capability and denies concurrent rename.
- The move-out substitution test exposed an unsafe leaf at the visible destination. Post-move
  identity or link-count failure now deletes that moved leaf before propagating the failure, while
  outside content remains unchanged.
- Initial replacement-disappearance tests returned inconsistent missing/unavailable results.
  Portable and native create paths now return semantic 409 when the caller supplied an observed
  target token and the target has disappeared.
- Native private-directory initialization treated all open failures as absence. It now creates
  only after an exact missing NTSTATUS or Win32 status and rethrows unknown/unavailable failures.
- Native append reserve admitted staging growth without accounting for the temporary chunk copy.
  The peak calculation now includes both concurrent byte sets and fails with 507 before creation.

All new regressions were observed RED before their corresponding fixes and GREEN afterward.

## Sixth Candidate Verification Before Review

All Gradle state remained in the external user and project caches, with
`SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true` for the native and junction coverage.

- The complete affected mutation, upload, repository-claim, portable-boundary, native-boundary,
  and real-JNA test set passed.
- `:website:test :website:jsTest` passed: 811 `website` tests and 93 `cbell-lib` tests passed
  (904 Java tests total), plus 153 JavaScript tests; 0 failures, 0 errors, and 0 skipped.
- A forced full Java `test --rerun-tasks` passed the same 904 tests from fresh task execution.
- The two mutation replacement-racer tests and upload replacement-racer test passed five forced
  runs each (15 executions total), with no rerun-only acceptance.
- `git diff --check` passed with only expected line-ending notices.
- No worktree-local `.gradle*` directory exists.

Task 4 remains in progress and review-rejected until the sixth candidate is committed, reviewed
across `8602985d..HEAD`, approved with no Critical or Important findings, and pushed.

## Sixth Independent Re-review

The whole-change review of local commit `14f00cf26b68d0cec1af2248ca6f157324bdac3b`
rejected Task 4 with three Critical, one Important, and one Minor finding. The commit was not
pushed. Portable visible rename/move/delete still performed a pathname operation after its final
observation check, portable replacement could persist a target identity newer than the caller's
observation, post-move cleanup could delete an unrelated visible racer, and plain portable moves
did not guarantee the atomic transitions assumed by recovery. The custom native file-channel
transfer loops also lacked a zero-progress exit.

## Sixth-Review Remediation RED/GREEN Evidence

The remediation resolves the shared root cause by failing closed rather than claiming a provider
guarantee Java does not expose:

- A new mutation regression was RED while unsupported portable rename, move, and delete still
  changed the observed source. Deployable operations now return 503 before reconciliation or any
  visible-path transition unless the retained native boundary is active.
- A new upload regression was RED while portable completion published private staging. Unsupported
  completion now returns 503 with the target absent, private staging intact, and the durable session
  still ACTIVE. Pending portable finalization recovery is also unavailable rather than path-mutating.
- Portable private move-out regressions now prove the boundary refuses the transition before an
  unsafe substitute can be exposed and cannot delete a pre-existing visible racer. Move-in is
  equally unavailable without a retained provider mutation capability.
- Legacy portable mutation/finalization state-machine tests are isolated behind an explicit
  test-only marker that the Spring production boundary never supplies. They preserve coordination
  coverage without advertising those pathname operations as deployable security guarantees.
- Native file-channel transfer loops now return on legal zero progress instead of spinning.

## Seventh Candidate Verification Before Review

All Gradle state remained in external user and project caches, with
`SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true` for native and junction coverage.

- The complete affected repository-claim, mutation, upload, portable-boundary, native-boundary,
  and real-JNA set passed, including the explicitly enabled junction regressions.
- `:website:test :website:jsTest` passed: 815 `website` tests and 93 `cbell-lib` tests passed
  (908 Java tests total), plus 153 JavaScript tests; 0 failures, 0 errors, and 0 skipped.
- A forced full Java `test --rerun-tasks` executed all 12 tasks and passed 907 tests before the
  final zero-progress regression was added; the subsequent complete Java gate passed all 908.
- `git diff --check` passed with only expected line-ending notices.
- No worktree-local `.gradle*` directory exists.

Task 4 remains in progress and review-rejected until the seventh candidate is committed, reviewed
across `8602985d..HEAD`, approved with no Critical or Important findings, and pushed.
