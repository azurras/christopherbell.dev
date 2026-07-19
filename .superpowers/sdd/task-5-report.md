# Task 5 Report - Recycle Retention and Audit Administration

Date: 2026-07-18

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

Result: BUILD SUCCESSFUL in 1m 50s. All 929 Java tests and 157 JavaScript tests passed, with
0 failures, 0 errors, and 0 skipped. The real JNA mutation tests and explicitly enabled junction
tests passed.

Additional verification:

- `git diff --check` passed with only expected line-ending notices.
- No worktree-local `.gradle*` directory exists.
- Production port 8080 was not touched.

Task 5 remains incomplete until a local candidate commit receives an independent whole-change
review with zero Critical and zero Important findings, all required remediation is reverified, and
the approved checkpoint is committed and pushed.
