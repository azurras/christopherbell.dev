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
- [ ] Record final full verification, commit, and request another fresh independent review while
  retaining Task 4 as review-rejected/in progress.
