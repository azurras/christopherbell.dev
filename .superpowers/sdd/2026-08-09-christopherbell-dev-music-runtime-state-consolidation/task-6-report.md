# Task 6 Report: Close the Installed Windows Writer-Start Safety Boundary

## Status

Implemented on `codex/music-runtime-state-consolidation` from Task 6 base
`388d049fc1445111216274c99b31796cfe212db5`.

Commit message: `fix: close installed writer start boundary`.

Fix Round 1 closes the reviewer-identified root/lock bootstrap, legacy configuration,
first-registration crash, launcher ordering-test, and exact containment-cause gaps in a separate
follow-up commit.

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
- Before any configuration, cloudflared, WinSW, XML, or publisher effect, `prod install`
  canonicalizes the fixed production root and deploy-lock path, inspects each existing ancestor,
  creates missing root/locks components individually without `-Force`, rejects reparse points,
  protects and verifies root/locks, acquires `deploy.lock`, and revalidates that boundary under
  the held lock. Disposable root and locks junction targets remain untouched on rejection.
- An existing service is verified Disabled under lock, reads only a legacy-compatible validated
  `productionPort`, and stops before the defaults upgrader or full modern configuration validation.
  A real prior-Running legacy fixture missing `publicUrls`, `sensorLibrariesEnabled`, retention,
  and polling fields upgrades successfully and restores private/public health. A malformed port
  never reaches a port-targeted stop and failure containment verifies Disabled/Stopped through
  the bounded SCM-only fallback.
- A first installation registers from a pinned WinSW XML with `Manual` startup, observes the exact
  registration effect, and immediately establishes and verifies `Disabled`. Automatic startup is
  restored only after the complete guarded service and shared-runtime boundary verifies. Crashes
  before registration, during registration, and before Disabled readback never request Automatic
  startup or start the service; a newly observed registration is contained by the outer lifecycle.
- A prior Running service alone is restarted and receives private and public health checks; a
  prior Stopped service remains stopped. Failure re-verifies Disabled/stopped containment and
  aggregates SCM, disable, stop, disappearance, and original installation causes when containment
  is uncertain. Pre-registration ACL, WinSW digest, and XML digest failures correctly report that
  no website service is registered rather than inventing a disappearance cause.
- Sensor configuration compares canonical Windows roots with
  `StringComparison.OrdinalIgnoreCase`.

## TDD and Review

- RED tests first exposed the absent exact startup-type helper, unsafe pre-guard ordering and
  failure state, missing compatible parent ACL/reparse validation, lost reinstall state, and
  case-sensitive sensor identity.
- Later RED cycles reproduced an unverified WinSW/XML/SCM boundary, pre-lock installer failures,
  SCM discovery ambiguity, installer writes through a junction, and pre-existing reparse host
  destinations. Each was made GREEN with the minimum boundary or lifecycle change.
- Fix Round 1 RED tests reproduced unchecked root/locks traversal, creation/protection/under-lock
  replacement races, premature full validation of a legacy config, Automatic first registration,
  and false pre-registration disappearance reporting. PS5 then exposed the new .NET Core-only
  path API; the compatible fixed-drive predicate was verified on both PowerShell hosts.
- The launcher ordering test now requires the actual
  `Assert-InstalledWriterStartServiceDirectoryAcl` call to exist before comparing its position.

## Verification

- Focused PowerShell 7 Common/WriterStart/Install/Sensors/Deploy/Operations: 274 discovered,
  273 passed, 0 failed, 1 skipped (the opt-in real ACL test).
- Full PowerShell 7 production suite: 463 discovered, 437 passed, 0 failed, 26 skipped.
- Approved Windows PowerShell 5.1 Common/Command/WriterStart/Install/Operations: 199 discovered,
  198 passed, 0 failed, 1 skipped.
- Approved Windows PowerShell 5.1 exact Task 6 Deploy/Sensors selection: 89 discovered, 10 passed,
  0 failed, 0 skipped, 79 not run.
- PowerShell 7 and Windows PowerShell 5.1 parsers each parsed all 11 changed PowerShell files with
  0 errors; both hosts also parsed the WinSW XML successfully. `git diff --check` passed.
- Module-resolution probes using production import order found the new Deploy-to-Install and
  Install-to-Deploy commands at runtime.

## ACL Evidence and Scope

- The opt-in real Windows ACL test uses only an owned
  `%TEMP%\cbell-writer-start-acl-<guid>` directory and covers the compatible parent plus pinned
  WinSW, exact XML, launcher, module, and manifest.
- The final PowerShell 7 and Windows PowerShell 5.1 attempts each discovered 29 WriterStart tests,
  executed only the opt-in ACL test, and failed before mutation with
  `Real writer-start ACL integration requires elevated PowerShell`; each reported 0 new
  disposable-directory residue. This session is not elevated, so a successful real ACL
  application remains the Task 7 evidence gate. No ACL policy was weakened.
- One earlier non-elevated development attempt partially changed an owned disposable ACL before
  cleanup was denied. Access was restored only on that exact temporary path and the directory was
  deleted; the final elevation precondition prevents recurrence.
- No production service, listener, ProgramData ACL, MongoDB data/schema, Java source, or production
  configuration was read or changed.
- The unrelated modified `gradlew.bat` and untracked `testResults.xml` remain preserved and are
  excluded from this task's commit.
