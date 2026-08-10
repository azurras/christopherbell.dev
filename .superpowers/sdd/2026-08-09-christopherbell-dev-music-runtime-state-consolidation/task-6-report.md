# Task 6 Report: Close the Installed Windows Writer-Start Safety Boundary

## Status

Implemented on `codex/music-runtime-state-consolidation` from Task 6 base
`388d049fc1445111216274c99b31796cfe212db5`.

Commit message: `fix: close installed writer start boundary`.

Fix Round 1 closes the reviewer-identified root/lock bootstrap, legacy configuration,
first-registration crash, launcher ordering-test, and exact containment-cause gaps in a separate
follow-up commit.

Fix Round 2 closes the remaining protected-root bootstrap, configured-root identity, and
legacy-port parser gaps in a second separate follow-up commit.

Fix Round 3 closes the exact install-root ACL and native replacement-race evidence gaps in a
third separate follow-up commit.

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
  canonicalizes the fixed production root and deploy-lock path, rejects reparse traversal, and
  verifies every existing ancestor's protected owner/DACL denies untrusted delete, ACL-control,
  ownership, null-DACL, and generic-all replacement power. An existing production root must
  already have the exact protected ACL; the installer never repairs or descends through an
  untrusted normal root.
- A missing root is created as an unpredictable sibling with its final protected security
  descriptor in the create call, identity/reparse/ACL checked while unreferenced, and atomically
  published with a same-parent no-replace rename. A benign competing protected creator is
  accepted only after full verification; unsafe creation races fail closed, and identity-checked
  partial stages are deleted non-recursively. Only after the root verifies does a handle-relative
  native create establish protected `locks`, preventing a replaced visible root from redirecting
  that write. Root, locks, parent, and `deploy.lock` are revalidated under the acquired lock.
- The staged root, published root, existing root, and `locks` directory use an install-root-specific
  raw ACL verifier. It requires protected inheritance, Builtin Administrators ownership, and
  exactly two explicit allow ACEs: one SYSTEM and one Builtin Administrators ACE, each with exact
  FullControl, ContainerInherit and ObjectInherit, and no propagation flags. Missing, duplicate,
  extra, deny, alternate-rights, alternate-inheritance, wrong-owner, unprotected, and reparse
  variants fail closed.
- Immediately after full configuration validation, the installer rejects relative/reparse
  traversal and requires `programDataRoot` to equal the already locked canonical root with
  `OrdinalIgnoreCase`, then rechecks the same root file identity and protected ACL before any
  config-derived ACL, cloudflared, WinSW, XML, publisher, or registration effect. An alternate
  normal configured root remains content-, metadata-, and ACL-unchanged.
- An existing service is verified Disabled under lock, reads only a legacy-compatible validated
  `productionPort`, and stops before the defaults upgrader or full modern configuration validation.
  A real prior-Running legacy fixture missing `publicUrls`, `sensorLibrariesEnabled`, retention,
  and polling fields upgrades successfully and restores private/public health. A malformed port
  never reaches a port-targeted stop and failure containment verifies Disabled/Stopped through
  the bounded SCM-only fallback.
- The legacy stop-port reader accepts only an integral JSON number in `1..65535`; missing, null,
  strings, Booleans, fractional numbers, arrays, objects, and out-of-range values fail closed. Its
  malformed-JSON exception deliberately omits the parser inner exception because Windows
  PowerShell 5.1 otherwise embeds the raw configuration in `Exception.ToString()`.
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
- Fix Round 2 RED tests reproduced repair of an unprotected existing root, locks creation through
  a replaced root, incomplete protected-stage cleanup, and use of an alternate configured root.
  Deterministic publish/child replacement tests preserve redirect-target content, ACL, and
  metadata with zero downstream installation effects. Windows PowerShell 5.1 then supplied the
  final RED by exposing the malformed JSON and secret text through the preserved parser inner
  exception; removing only that unsafe inner cause made all direct parser cases GREEN.
- Fix Round 3 RED tests first failed because the install-root exact ACL verifier did not exist,
  then because the native identity-race exception lacked a stable failure code. The exact raw-ACE
  matrix exercises the real verifier without mocking it. Windows PowerShell 5.1 exposed a composite
  enum conversion incompatibility; combining the numeric flag values preserved the exact ACL
  contract on both hosts.
- The launcher ordering test now requires the actual
  `Assert-InstalledWriterStartServiceDirectoryAcl` call to exist before comparing its position.

## Verification

- Focused PowerShell 7 Common/WriterStart/Install/Sensors/Deploy/Operations: 318 discovered,
  316 passed, 0 failed, 2 skipped (the opt-in real ACL tests).
- Full PowerShell 7 production suite: 507 discovered, 480 passed, 0 failed, 27 skipped.
- Approved Windows PowerShell 5.1 Common/Command/WriterStart/Install/Operations: 243 discovered,
  241 passed, 0 failed, 2 skipped.
- Approved Windows PowerShell 5.1 exact Task 6 Deploy/Sensors selection: 89 discovered, 10 passed,
  0 failed, 0 skipped, 79 not run.
- The exact install-root ACL selection passes on both hosts: 90 discovered, 16 passed, 0 failed,
  0 skipped, 74 not run. The complete Install module passes on each host: 90 discovered, 89 passed,
  0 failed, 1 skipped. Its real in-memory negative cases cover duplicate or missing SYSTEM and
  Administrators ACEs, wrong inheritance or propagation, extra or missing rights, extra or deny
  ACEs, wrong owner, unprotected inheritance, and reparse traversal.
- The real `Read-ProductionWebsiteStopPort` matrix passes all 14 cases on PowerShell 7 and Windows
  PowerShell 5.1, including boundary values 1/8080/65535 and raw-config redaction.
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
- The new install-root opt-in ACL test likewise reserves only an owned
  `%TEMP%\cbell-install-root-acl-<guid>` parent. Final PowerShell 7 and Windows PowerShell 5.1
  attempts each discovered 90 Install tests, selected one, and failed before creating the parent
  with `Real install-root ACL integration requires elevated PowerShell`; each reported 0 new
  residue. Elevated NTFS ACL application/readback and native volume/file-ID protected-create
  identity evidence remain a Task 7 gate.
- One earlier non-elevated development attempt partially changed an owned disposable ACL before
  cleanup was denied. Access was restored only on that exact temporary path and the directory was
  deleted; the final elevation precondition prevents recurrence.
- No production service, listener, ProgramData ACL, MongoDB data/schema, Java source, or production
  configuration was changed. Fix Round 2 performed read-only `Get-Acl` inspection of `C:\` and
  `C:\ProgramData` to verify the fixed-parent replacement-control model; all mutation/integration
  tests used owned disposable paths. Fix Round 3 did not inspect or mutate any production resource.
- The unrelated modified `gradlew.bat` and untracked `testResults.xml` remain preserved and are
  excluded from this task's commit.
