# Task 6 Report: Close the Installed Windows Writer-Start Safety Boundary

## Status

Implemented on `codex/music-runtime-state-consolidation` from Task 6 base
`388d049fc1445111216274c99b31796cfe212db5`.

Commit message: `fix: close installed writer start boundary`.

## Outcomes

- The deploy-lock-held pre-guard upgrade sets `ChristopherBellDev` to `Disabled` and verifies the
  exact SCM readback before stopping or publishing. Staging, first-file, process-death, and
  Automatic-readback failures reassert and verify `Disabled`; `Automatic` is restored only after
  the complete installed boundary verifies.
- The canonical production/service path rejects reparse traversal and receives the exact
  protected ACL compatible with the media worker: SYSTEM and Administrators have full control,
  while LocalService has non-inheriting read/execute traversal. The installer establishes this
  boundary before WinSW, XML, or writer-guard writes. Existing reparse destinations are rejected.
- The version-2 writer-start manifest binds the pinned WinSW executable, exact service XML,
  launcher, and WriterStart module by SHA-256. All five files have protected ACLs. The installed
  launcher rechecks the parent ACL, reparse traversal, exact manifest, and all four content hashes
  before reading runtime configuration or starting Java. Install and deploy also verify that SCM
  is bound to the exact WinSW path under LocalSystem before restoring Automatic startup.
- `prod install` creates only the lock bootstrap directory before acquiring `deploy.lock`, then
  captures service state with query errors distinct from absence. An existing service is disabled
  and stopped before other fallible install effects. A prior Running service alone is restarted
  and receives private and public health checks; a prior Stopped service remains stopped. Failure
  re-verifies Disabled/stopped containment and aggregates SCM, disable, stop, disappearance, and
  original installation causes when containment is uncertain.
- Sensor configuration compares canonical Windows roots with
  `StringComparison.OrdinalIgnoreCase`.

## TDD and Review

- RED tests first exposed the absent exact startup-type helper, unsafe pre-guard ordering and
  failure state, missing compatible parent ACL/reparse validation, lost reinstall state, and
  case-sensitive sensor identity.
- Later RED cycles reproduced an unverified WinSW/XML/SCM boundary, pre-lock installer failures,
  SCM discovery ambiguity, installer writes through a junction, and pre-existing reparse host
  destinations. Each was made GREEN with the minimum boundary or lifecycle change.
- The final independent review reports no blockers or actionable findings. Its initial WinSW/XML,
  early-install, SCM-query, and installer-junction findings are covered by regression tests.

## Verification

- Focused PowerShell 7 WriterStart/Install/Sensors/Deploy/Operations: 230 discovered, 229 passed,
  0 failed, 1 skipped (the opt-in real ACL test).
- Full PowerShell 7 production suite: 444 discovered, 418 passed, 0 failed, 26 skipped.
- Approved Windows PowerShell 5.1 WriterStart/Install/Operations: 141 discovered, 140 passed,
  0 failed, 1 skipped.
- Approved Windows PowerShell 5.1 exact Task 6 Deploy/Sensors selection: 89 discovered, 10 passed,
  0 failed, 0 skipped, 79 not run.
- Diagnostic Windows PowerShell 5.1 run of all five changed-module suites: 230 discovered,
  221 passed, 8 failed, 1 skipped. The eight failures are unchanged base incompatibilities in
  untouched behavior: `Path.GetRelativePath`, `IO.Compression.ZipFile`, and `Double.IsFinite` in
  Sensors, plus two existing Deploy native-process assumptions. No Task 6 test failed.
- PowerShell 7 and Windows PowerShell 5.1 parsers each parsed all 9 changed executable/test files
  with 0 errors. `git diff --check` passed.
- Module-resolution probes using production import order found the new Deploy-to-Install and
  Install-to-Deploy commands at runtime.

## ACL Evidence and Scope

- The opt-in real Windows ACL test uses only an owned
  `%TEMP%\cbell-writer-start-acl-<guid>` directory and covers the compatible parent plus pinned
  WinSW, exact XML, launcher, module, and manifest.
- The final PowerShell 7 and Windows PowerShell 5.1 attempts each selected 1 test and failed before
  mutation with `Real writer-start ACL integration requires elevated PowerShell`; each reported
  0 disposable-directory residue. This session is not elevated, so a successful real ACL
  application remains the sole evidence gap. No ACL policy was weakened.
- One earlier non-elevated development attempt partially changed an owned disposable ACL before
  cleanup was denied. Access was restored only on that exact temporary path and the directory was
  deleted; the final elevation precondition prevents recurrence.
- No production service, listener, ProgramData ACL, MongoDB data/schema, Java source, or production
  configuration was read or changed.
- The unrelated modified `gradlew.bat` and untracked `testResults.xml` remain preserved and are
  excluded from this task's commit.
