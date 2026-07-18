# Task 4 Review Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct all Task 4 review findings with regression-tested native containment,
conditional replacement, restart-safe uploads, exact errors, and a trustworthy browser workflow.

**Architecture:** Retained native mutation chains deny delete sharing; replacement uses private
conditional quarantine. Mongo optimistic versions form durable per-session append/finalization
leases. Browser resume proves each committed prefix chunk and all routes share explicit domain
error mapping.

**Tech Stack:** Java 25, Spring Boot, Spring Data MongoDB, JNA/NT native APIs, JUnit 5, Mockito,
MockMvc, browser ES modules, Node test runner.

## Global Constraints

- Preserve fresh persisted authorization and owner checks.
- Never return absolute paths or native handles.
- Enabled Windows mode has no path-based NIO fallback.
- Keep Task 3 read semantics separate.
- Use external Gradle user and project caches.
- Keep Task 4 review-rejected in the progress ledger until a subsequent reviewer approves.

---

### Task 1: Retained mutation chains and share denial

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/fs/WindowsSharedFolderNativeBridge.java`
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/fs/JnaWindowsSharedFolderNativeBridge.java`
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/fs/WindowsSharedFolderMutationBoundary.java`
- Test: `website/src/test/java/dev/christopherbell/sharedfolder/fs/WindowsSharedFolderMutationBoundaryTest.java`
- Test: `website/src/test/java/dev/christopherbell/sharedfolder/fs/WindowsSharedFolderNativeJnaIntegrationTest.java`

**Interfaces:** Mutation traversal produces an `AutoCloseable` root-to-leaf chain whose retained
root and descendant handles all use `FILE_SHARE_READ | FILE_SHARE_WRITE`, and closes transient
handles in reverse order after the operation.

- [ ] Add fake-bridge and real-Windows tests that attempt outside source and ancestor renames while
  delete/rename is paused after validation; assert sharing conflict and unchanged outside content.
- [ ] Run the focused native tests and confirm RED because ancestors close and mutation opens share
  delete.
- [ ] Add a mutation-specific bridge open/share mode and retained-chain abstraction.
- [ ] Run focused native tests and confirm GREEN.

### Task 2: Conditional quarantine replacement

**Files:**
- Modify: native boundary and mutation/upload services above.
- Modify: upload session/state types under `sharedfolder/upload`.
- Test: mutation, upload, boundary, and real JNA test classes.

**Interfaces:** Replacement pins the observed target, moves it to a validated random private
quarantine key, moves source with `replace=false`, and restores or durably reconciles quarantine.

- [ ] Add target-swap, target-creation, non-empty-directory, restore, and recreated-service recovery
  tests for native and portable paths; add crash-point tests for PREPARED, TARGET_QUARANTINED,
  SOURCE_MOVED, and RESTORE_PENDING; assert source/staging/racer/quarantine preservation and RED.
- [ ] Implement an owner-scoped bounded mutation journal, no-overwrite moves, private quarantine
  metadata, startup/pre-operation reconciliation, rollback, and provider fail-closed mapping.
- [ ] Run focused replacement tests and confirm GREEN.

### Task 3: Streaming limit and restart-safe append

**Files:**
- Modify: `RequestSizeLimitFilter`, write controller, upload service/session/state.
- Test: request-size, controller, and upload tests.

**Interfaces:** A public recognizable `PayloadTooLargeException` reaches the filter unchanged;
pending append records persist offset/length/digest plus an instance token and bounded expiry after
the request has staged and verified its chunk. Optimistic versioning acquires the durable lease.

- [ ] Add unknown-length filter + real controller/service 413 test with unchanged offset/staging.
- [ ] Add recreated-service portable/native tests with partial and full uncommitted bytes and two
  competing service instances, including proof that an unexpired lease cannot be stolen or have
  its in-flight/committed bytes truncated; confirm RED.
- [ ] Preserve the payload exception, add pending append fields/state, reconcile physical length to
  committed offset, remove global synchronization, and use optimistic-save conflicts as 409 leases.
- [ ] Run focused streaming/restart tests and confirm GREEN.

### Task 4: Validation, expiry, cancel, and exact statuses

**Files:**
- Modify: mutation service, upload service, controllers, DTOs/domain exceptions.
- Test: mutation, upload, and parameterized controller/service contract tests.

**Interfaces:** One domain error mapper produces safe 400/404/409/503 responses consistently.

- [ ] Add direct-service tests for null/blank/unsafe/root requests, unauthorized observation,
  non-positive sizes, expired status/complete, and native delete failure/recovery; confirm RED.
- [ ] Add parameterized native/portable 400/404/409/503 contract tests and safe-body assertions;
  confirm RED.
- [ ] Implement explicit validation, authorized observation, centralized expiry reconciliation,
  confirmed cancellation, and domain exception mapping.
- [ ] Run focused lifecycle/status tests and confirm GREEN.

### Task 5: Behavioral browser workflow

**Files:**
- Modify: `website/src/main/resources/static/js/shared-folder.js`
- Modify: `website/src/main/resources/static/js/lib/shared-folder.js`
- Modify: public upload status DTO/API handling as required.
- Test: `website/src/test/js/shared-folder.test.js`

**Interfaces:** Status advertises `chunkSizeBytes` and ordered committed chunk proofs. The uploader
owns a one-operation gate and AbortController and retries only transient failures with bounded delay.

- [ ] Add behavioral tests with mocked fetch/DOM for retry, pause/resume, double submission,
  changed same-name/same-size file, configured chunk size, and explicit replacement; confirm RED.
- [ ] Implement prefix-proof resume, advertised chunk size, operation gate, pause/cancel abort, and
  bounded retry.
- [ ] Run focused and full JavaScript tests and confirm GREEN.

### Task 6: Report, full verification, and commit

**Files:**
- Modify: `.superpowers/sdd/task-4-report.md`
- Preserve: `.superpowers/sdd/progress.md`
- Modify relevant README files when public contracts changed.

- [ ] Record honest RED/GREEN commands and results for every review finding.
- [ ] Run all shared-folder/native/junction tests, full `:website:test`, `:website:jsTest`, JS syntax,
  and `git diff --check` using external caches.
- [ ] Verify the worktree-local Gradle caches are absent and the progress ledger remains
  review-rejected.
- [ ] Commit the complete green fix and send the SHA/report path for independent review.

### Task 7: Second independent re-review remediation

**Files:** Mutation recovery/session models and services, mutation/upload service tests, and these
SDD artifacts.

- [x] Add RED portable/native concurrency tests proving live mutation/finalization leases cannot be
  reconciled by status, complete, startup, or another service at PREPARED or physical quarantine.
- [x] Add optimistic lease claim/refresh behavior and explicitly expire simulated crash leases.
- [x] Add RED mutation/upload tests for non-empty replacement directories, post-quarantine child
  creation, same-size in-place content edits, and observed target disappearance.
- [x] Add content-sensitive portable replacement identity and repeated pre-move quarantine checks;
  restore before source/staging movement on failure.
- [x] Replace portable case-only UUID two-step rename with one atomic attempt and prove injected
  failure never strands the source; retain real Windows case-only coverage.
- [x] Add portable and real-native commit-then-throw append tests; reload durable ACTIVE proof and
  reconcile only exact matching APPENDING leases without truncating committed bytes.
- [x] Validate append id/offset/body/digest as 400 and classify native upload missing/conflict/
  unavailable statuses through real service tests.
- [x] Prove portable/native explicit replacement target disappearance is 409 with source/staging
  intact.
- [x] Record final full verification, commit, and request another fresh independent review while
  retaining Task 4 as review-rejected/in progress.

### Task 8: Native leaf write denial and renewable fencing

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/fs/WindowsSharedFolderNativeBridge.java`
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/fs/JnaWindowsSharedFolderNativeBridge.java`
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/fs/WindowsSharedFolderMutationBoundary.java`
- Modify: mutation/upload repositories and services.
- Test: native boundary/JNA integration and mutation/upload service tests.

**Interfaces:** `openRelativeForExclusiveMutation(...)` opens only a final visible mutation leaf
with mutation access and `FILE_SHARE_READ`. Repository renewal methods match id + exact lease token
+ state/phase and return the numeric modified-document count without changing `@Version`.

- [x] Add fake-bridge and real Windows RED races for same-size source/target writes and directory
  child creation after the final native recheck in rename/delete, durable mutation replacement, and
  upload replacement.
- [x] Implement exclusive visible-leaf opens while retaining compatible root/ancestor sharing; run
  the focused fake/JNA tests GREEN.
- [x] Add short-lease RED tests that pause multi-block digest/recovery, attempt a competing claim,
  and prove token loss stops the old writer before its next physical transition.
- [x] Add atomic lease renewal, periodic digest/recovery heartbeat, and pre-transition fencing; run
  mutation/upload lease tests GREEN.

### Task 9: APPENDING claim and strict case-only rename

**Files:** upload repository/service/tests and mutation service/tests.

**Interfaces:** APPENDING recovery optimistically saves a new recovery token and expiry for the
exact expired lease before physical cleanup. Case-only rename calls a provider-safe one-step helper
only after same-object/collision determination.

- [x] Add a RED two-instance stale APPENDING reconciler test where the winner restores ACTIVE and a
  new append starts before the loser reaches cleanup; assert the loser cannot truncate new bytes.
- [x] Claim/fence APPENDING before truncate/delete, heartbeat the physical append window, and run
  portable/native append concurrency tests GREEN.
- [x] Add RED case-sensitive differently-cased-target and target-creation races, plus real Windows
  case-only success/failure, preserving both files on conflict.
- [x] Implement same-object proof and strict no-replace provider behavior; run mutation tests GREEN.

### Task 10: Exact upload errors and portable private-root capability

**Files:**
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/fs/PortableSharedFolderPrivateBoundary.java`
- Modify: upload/mutation services and shared-folder documentation.
- Test: portable upload service and filesystem-boundary tests.

**Interfaces:** The portable private boundary accepts only a pre-created ordinary system root,
captures/rechecks its full ancestor/root identity chain, and exposes safe direct-child staging and
quarantine operations. Typed boundary failures map to 404/409/503.

- [x] Add native real-service RED tests for create/append/complete/cancel missing, collision/share,
  status-zero, and unknown status classification.
- [x] Add portable real-service RED tests for missing visible parent/item, absent/non-directory/
  linked/provider-unavailable visible root, and unavailable private root across lifecycle methods.
- [x] Add RED symlink/junction and ancestor/root substitution tests proving no outside private write
  or delete; document the pre-created system-root contract.
- [x] Implement exact native/portable classification and private-root capability rechecks; run all
  focused service/filesystem tests GREEN.

### Task 11: Canonical replacement spelling and non-retried create

**Files:** shared-folder browser library/page code, JS tests, mutation/upload service parity tests,
and frontend documentation.

**Interfaces:** Listing matches return the canonical server entry name/path. Create upload and move
payloads use that spelling for explicit replacement. Resume name matching is case-insensitive only
with the existing committed-prefix proof. `createUpload` executes once without transient retry.

- [x] Add browser RED workflow tests for replacing `Foo.mkv` with local `foo.mkv`, canonical move
  payloads, case-insensitive resume with prefix proof, and a failed create POST attempted once.
- [x] Add portable/native service RED parity tests for case-insensitive explicit replacement.
- [x] Implement canonical spelling propagation and non-retried create; run JS/service tests GREEN.

### Task 12: Third remediation report, verification, and commit

- [x] Preserve all prior three review regression groups and record honest RED/GREEN evidence.
- [x] Run focused fake/native/JNA/junction tests with the native environment flag, full Java tests,
  all 150+ JavaScript tests, touched JavaScript syntax, diff check, and external-cache hygiene.
- [x] Update design, plan, report, feature/frontend docs, and progress; commit while Task 4 remains
  in progress/review-rejected and request a fourth whole-change review.

### Task 13: Fourth independent review remediation

**Review result:** Commit `0675a6db` was rejected with three Critical, seven Important, and two
Minor findings. Independent technical verification confirmed the three Critical findings and
Important findings 1, 2, 4, 6, and 7. Scheduled unattended expiry cleanup remains assigned to
Task 8 in the primary Builder plan. Universal validation-before-authorization contradicts the
approved Task 4 design, which deliberately requires that precedence only for append inputs.

- [x] Add RED renew-versus-claim races for mutation and upload finalization; replace stale
  optimistic `save` claims with exact token/state/phase/expiry atomic claims and reload proof.
- [x] Add RED blocked-read/expired-lease append races; renew before truncate/write/flush and retain
  exclusive native append-target handles plus a portable physical-write fence.
- [x] Add RED private-leaf symlink, hardlink, and mid-operation substitution tests; replace raw
  leaf `Path` callbacks with provider-backed no-follow capabilities and fail unsupported providers
  closed as 503.
- [x] Add RED portable/native initial target-disappearance tests and preserve 409 with source and
  staging intact.
- [x] Add typed native missing/conflict/unavailable failures; propagate exact recovery errors and
  remove status-zero call-site booleans.
- [x] Revalidate authoritative server status before browser resume and add a forged local-record
  regression.
- [x] Make the replacement-racer test deterministic, fix null-file-key identity ambiguity, and run
  the focused concurrency/recovery tests repeatedly.
- [x] Remove the duplicate import, update documentation/report/progress, run full Java/JS/native/
  junction verification, commit a fifth candidate, and request another whole-change review.

### Task 14: Fifth independent review remediation

**Review result:** Commit `09a0e766` was rejected with three Critical and four Important findings.
Scheduled unattended expiry cleanup remains assigned to Task 8 in the primary Builder plan, and
the approved append validation-before-authorization precedence remains unchanged.

- [x] Add a repository RED test proving every expired mutation, append, and finalization lease
  claim invalidates stale optimistic entity versions; increment `@Version` atomically and reload
  exact claim proof before physical recovery.
- [x] Add RED create-new and mid-operation substitution tests; bind private file operations to a
  retained provider identity and, on Windows, a native handle-backed file channel.
- [x] Add a RED move-out substitution test; remove an unsafe substituted leaf from the visible
  destination while preserving outside content.
- [x] Remove metadata-only stable-identity fallback and require provider-backed file identity.
- [x] Add portable/native RED tests for an observed replacement target disappearing during initial
  creation; return 409 without consuming source or staging data.
- [x] Add a RED native private-directory initialization test; create only after an exact missing
  status and propagate unknown/unavailable opens.
- [x] Add a RED native append reserve test; include temporary chunk duplication plus final staging
  growth in the peak requirement.
- [ ] Commit the sixth candidate, request a fresh whole-change review, and retain Task 4 as
  review-rejected until approval and remote push.
