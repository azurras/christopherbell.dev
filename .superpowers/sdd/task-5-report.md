# Task 5 Report - Recycle Retention and Audit Administration

Date: 2026-07-21

Branch: `codex/shared-folder-portal`

Status: Review candidate; independent whole-change approval required before completion and push

## Delivered Behavior

- Visible delete now moves the exact observed file or directory into isolated private recycle
  storage instead of physically deleting it. Root/unsafe paths and stale observations fail before
  mutation.
- Durable `PREPARING`, `RECYCLED`, `RESTORING`, and `PURGING` states reconcile interrupted moves,
  restores, explicit replacements, and permanent purges. Reconciliation defers an ambiguous item
  without blocking later recoverable records.
- ADMIN restore preserves the original path, refuses collisions by default, and replaces only
  after explicit intent. Permanent purge requires the exact phrase `PURGE <item-id>` and removes
  retained directory trees child-first through verified handles.
- The configured 30-day recycle retention is enforced by scheduled cleanup. Private payload keys
  and identity fingerprints are excluded from API responses.
- Bounded Mongo audit events use a configured absolute expiry with a zero-second TTL index. Audit
  calls cover permission decisions, listing, logical content access without per-range noise,
  previews, uploads, mutations, recycle, restore, and purge. Sink failures are best-effort and
  never expose exception text, bodies, tokens, absolute paths, or command lines.
- ADMIN audit browsing supports bounded account, action, outcome, exact relative-path, date, and
  limit filters, newest first. Back Office includes accessible filters, safe escaped audit/recycle
  rendering, restore, explicit replace confirmation, and exact typed purge confirmation.

Task 6 transcode admission/completion/failure auditing remains intentionally deferred until the
transcode implementation exists.

## TDD Evidence

Observed RED/GREEN cycles included:

- Recycle service tests first failed because no recycle service or durable state model existed.
  They passed after private moves, retention metadata, restore/replace/purge, and reconciliation
  were implemented.
- Native recursive purge initially failed to compile because the retained-handle tree operation did
  not exist. The regression now proves children are opened, identity checked, and deleted before
  their parent. The portable test-only state-machine provider received equivalent no-follow tree
  cleanup.
- A blank path initially reached root resolution; the new regression passes after non-empty safe
  relative segments are required before filesystem work.
- One ambiguous recovery record initially aborted the reconciliation pass. It now remains durable
  while later recoverable records continue.
- Audit persistence/query/recorder tests were RED before the bounded event, TTL sink, validated
  query, safe failure categories, trusted client-IP resolution, and logical range deduplication.
- Back Office tests were RED before escaped rendering, local-time date conversion, invalid-date
  omission, restore/replace controls, and exact purge confirmation.

## Verification Before Review

All Gradle state remained in the external user and project caches. The real Windows native and
junction flag was enabled:

```powershell
$env:GRADLE_USER_HOME='C:\Users\Christopher\AppData\Local\Temp\cbdev-gradle-shared-folder-portal'
$env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST='true'
.\gradlew.bat --no-daemon `
  --project-cache-dir 'C:\Users\Christopher\AppData\Local\Temp\cbdev-project-cache-shared-folder-portal' `
  test :website:jsTest --console=plain
```

Initial candidate result: BUILD SUCCESSFUL in 1m 50s. All 929 Java tests and 157 JavaScript tests passed, with
0 failures, 0 errors, and 0 skipped. The real JNA mutation tests and explicitly enabled junction
tests passed.

Additional verification:

- `git diff --check` passed with only expected line-ending notices.
- No worktree-local `.gradle*` directory exists.
- Production port 8080 was not touched.

Task 5 remains incomplete until a local candidate commit receives an independent whole-change
review with zero Critical and zero Important findings, all required remediation is reverified, and
the approved checkpoint is committed and pushed.

## First Independent Review and Remediation

The first whole-change review of candidate `8509899f` rejected Task 5 with zero Critical, four
Important, and zero Minor findings. No rejected candidate was pushed. Each finding received a
focused regression before its production fix:

- Native recursive purge no longer compares directory size or modified time after intentionally
  deleting its children. It still requires the same retained identity and item kind before deleting
  the parent. The fake native bridge now changes parent metadata after child deletion, matching real
  filesystem behavior while proving the child-first purge completes.
- Shared-folder permission changes no longer rely on a day-long JWT ADMIN claim. Every authenticated
  attempt enters the service, which requires a fresh persisted active, approved ADMIN before any
  mutation. A stale demoted/suspended/unapproved admin is denied.
- The permission operation now audits success plus validation, fresh authorization, missing target,
  and persistence failures through bounded safe categories. Controller validation delegates the
  complete request shape to this audited service boundary.
- Logical content-access deduplication now uses a synchronized insertion-ordered map with deterministic
  eldest eviction. A 10,050-path regression proves the heap-resident cache remains exactly bounded at
  10,000 entries.

The native/junction-enabled full gate after remediation passed 932 Java tests and 157 JavaScript
tests with zero failures, errors, or skips (`BUILD SUCCESSFUL` in 2m 8s). Task 5 remains a review
candidate until a new whole-change review approves the complete range.

## Third Independent Review and Remediation

A fresh whole-change review of `09d2f408..69e70508` rejected Task 5 with zero Critical, four
Important, and two Minor findings. No rejected candidate was pushed. The remediation addresses the
entire set:

- Recycle records now retain a stable filesystem identity separately from the mutable full
  fingerprint. Restore and partially completed recursive-purge recovery accept the same object after
  expected content or directory-metadata changes, while manual and scheduled purge still perform a
  strict full-fingerprint check before entering the destructive `PURGING` state.
- A shared HTTP boundary now audits validation, method-security, authentication, and other rejected
  shared-folder API responses that occur outside controller methods. Fixed action/resource mappings
  and bounded failure categories prevent request bodies, tokens, absolute paths, and exception text
  from entering audit records.
- Retention cleanup records accepted and rejected `RETENTION_PURGE` system events and continues with
  later expired items after one item fails. Successful audit and recycle browsing are also recorded.
- Back Office tests now exercise the actual restore, replace-confirmation, and exact typed-purge
  interaction wiring, including cancellation and mismatch paths that make no API request.
- Security configuration tolerates unrelated sliced controller tests in which the shared-folder
  audit component is intentionally absent; production still injects the real recorder.

Focused RED/GREEN verification covered portable and native partial purge, edited restored objects,
retention continuation/auditing, HTTP-boundary rejection auditing, unrelated security slice wiring,
and Back Office action decisions. The final clean native/junction-enabled gate passed all 938 Java
tests and 159 JavaScript tests with zero failures, errors, or skips (`BUILD SUCCESSFUL` in 1m 1s).
Task 5 remains a review candidate until the remediated whole range receives independent approval.

## Fourth Independent Review and Remediation

The next fresh whole-change review of `09d2f408..eeedb566` rejected Task 5 with zero Critical,
three Important, and three Minor findings. No rejected candidate was pushed. All six findings were
remediated with regression coverage:

- Native restore and displaced-target recovery now establish cleanup immediately after each
  successful retained-handle open. A target-open race proves the already-open recycle source and
  both destination ancestor chains are closed.
- The shared audit HTTP boundary now covers malformed account permission requests before controller
  entry and maps the real `/content` and `/uploads/{id}/chunks/{offset}` routes to their proper audit
  families. Malformed target account IDs use a fixed bounded resource, and malformed JSON is
  correctly classified as HTTP 400.
- Recycle administration returns at most 200 newest items, while each scheduled retention pass
  processes at most 100 oldest-expired items. This bounds response size, repository materialization,
  and time spent holding the service mutation monitor.
- Outcome and exact relative-path audit filters now have matching time-ordered Mongo compound
  indexes.
- The actual Back Office handler and delegation selection are extracted into tested wiring. The
  regression proves restore/replace/purge URL selection, HTTP methods, authorization headers,
  request bodies, refresh behavior, and button recovery.

The final clean native/junction-enabled gate passed all 944 Java tests and 160 JavaScript tests with
zero failures, errors, or skips (`BUILD SUCCESSFUL` in 1m 1s). Task 5 remains a review candidate
until this complete remediated range receives independent approval.

## Fifth Independent Review and Remediation

The fifth fresh whole-change review of `09d2f408..81cced78` rejected Task 5 with zero Critical,
three Important, and one Minor finding. No rejected candidate was pushed. The remediation completes
the remaining cross-cutting audit and bounding requirements:

- Startup and scheduled recycle reconciliation now materialize and process at most 100 pending
  records per pass, matching the bounded retention cleanup contract.
- Every successful upload chunk records an accepted `UPLOAD_APPEND` event with its safe session ID
  and committed offset.
- Every completed durable recycle, restore, or purge reconciliation records an accepted fixed system
  recovery action; failed scheduled reconciliations record only a bounded safe failure category.
- Request-scoped action/outcome markers let the HTTP rejection boundary skip a permission failure
  already recorded by the service, while pre-controller malformed requests still receive an event.

The clean native/junction-enabled full gate passed all 946 Java tests and 160 JavaScript tests with
zero failures, errors, or skips (`BUILD SUCCESSFUL` in 57s). Task 5 remains a review candidate until
the complete remediated range receives independent approval.

## Sixth Independent Review and Remediation

The sixth fresh whole-change review of `09d2f408..97b6e96f` rejected Task 5 with zero Critical,
four Important, and zero Minor findings. No rejected candidate was pushed. The remediation closes
each boundary explicitly:

- The outer shared-folder audit filter now binds and completes request context around the entire
  downstream security/application chain. Pre-controller rejections retain the trusted client IP,
  and post-dispatch service markers reliably suppress duplicate permission rejection events.
- Repeated HTTP 429 responses use a separate insertion-ordered, 10,000-entry, five-minute
  client/action/category deduplication window. Exhausted rate-limit buckets therefore cannot force
  one Mongo write per rejected request.
- PREPARING recovery deletes metadata only when the visible object has the persisted stable source
  identity. RESTORING recovery with a journaled but missing private replacement resets only when the
  visible target matches the persisted replacement fingerprint. Portable and native regressions
  prove ambiguous missing-artifact states remain durable and are not reported as accepted.
- Valid resources that exceed the 512-character audit field bound are converted to a deterministic
  SHA-256 resource identifier before cache insertion and persistence. Exact-path admin filters apply
  the same conversion, keeping long-path events both memory-bounded and searchable.

The clean native/junction-enabled full gate passed all 952 Java tests and 160 JavaScript tests with
zero failures, errors, or skips (`BUILD SUCCESSFUL` in 56s). Task 5 remains a review candidate until
the complete remediated range receives independent approval.
