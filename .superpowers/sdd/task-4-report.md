# Task 4 Recovery Report

## Status

DONE

## Recovery Context

Task 4 was recovered from an uncommitted, untrusted handoff on branch
`codex/mobile-command-center` at committed base `fda6a9c9`. The handoff already
contained production and test files but no report or usable RED evidence. The
existing diff was retained only after inspection, focused testing, independent
review, and corrective RED-GREEN cycles. No controller or frontend work was
added, and no real host action was executed.

## Initial Focused Test Evidence

Command:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-worktrees\.gradle-mobile-command-center'
.\gradlew.bat :website:test --tests dev.christopherbell.admin.commandcenter.action.CommandCenterActionServiceTest --tests dev.christopherbell.admin.commandcenter.action.WindowsCommandExecutorTest
```

The first recovered run failed honestly during `:website:compileTestJava`:

```text
CommandCenterActionServiceTest.java:112: cannot find symbol
AtomicInteger.valueOf(0)
CommandCenterActionServiceTest.java:167: wrong number of type arguments
ArgumentCaptor.<Map<String, String>>forClass(Map.class)
BUILD FAILED
```

After those two handoff-test defects were repaired, a second compilation run
found an unchecked `PasswordUtil.hashPassword` fixture exception. Once the test
harness compiled, the inherited focused tests passed. No claim is made that the
pre-existing production code was observed missing before recovery.

Subsequent recovery RED evidence reproduced these real defects before their
minimum fixes:

- Full suite: `CommandCenterPropertiesTest.createsOneSharedSystemInfoProvider`
  failed because the new executor bean required properties absent from the
  standalone configuration context.
- Two independently valid concurrent challenges bypassed cooldown and both ran.
- A failed `shutdown /a` launch discarded pending state and prevented retry.
- Invalid challenges bypassed the three-failures-per-15-minute window.
- `ActionConfirmation.toString()` exposed the submitted challenge, password,
  and confirmation phrase.
- A failed power-command launch left a phantom pending action.
- Competing restart/shutdown requests overwrote the single pending action.
- A completion-audit persistence failure misreported an already launched action
  as a launch failure.
- A non-Windows execution guard test initially failed to compile because the
  host-detection seam did not exist.

## Implementation

- Added the closed action enum and executor interface.
- Added simulated-by-default and fixed-array Windows executors. Windows execution
  fails closed on non-Windows hosts; no shell, request command, path, service,
  or argument is accepted.
- Added SecureRandom, account-and-action-bound, atomically single-use challenges
  with a two-minute TTL and secret-redacted string representation.
- Revalidates current persisted ACTIVE, approved ADMIN state and verifies the
  current password with `PasswordUtil` at execution time.
- Enforces exact phrases, three failures per 15 minutes, atomic two-minute
  accepted-action cooldowns, replay/idempotency protection, and one globally
  pending machine power action.
- Executes fixed 60-second restart/shutdown countdown arrays and fixed `/a`
  cancellation, restoring pending state if cancellation cannot launch.
- Audits acceptance before deferred website restart, uses captured explicit
  actor identity for background completion, and keeps secrets out of metadata.
- Decouples completion-audit persistence failure from the truth of whether a
  host process already launched.
- Wires the configured SIMULATED/WINDOWS executor and a deferred action scheduler.

## Files

- `website/src/main/java/dev/christopherbell/admin/README.md`
- `website/src/main/java/dev/christopherbell/admin/activity/AdminActivityService.java`
- `website/src/main/java/dev/christopherbell/admin/commandcenter/CommandCenterConfiguration.java`
- `website/src/main/java/dev/christopherbell/admin/commandcenter/action/CommandCenterActionType.java`
- `website/src/main/java/dev/christopherbell/admin/commandcenter/action/CommandExecutor.java`
- `website/src/main/java/dev/christopherbell/admin/commandcenter/action/SimulatedCommandExecutor.java`
- `website/src/main/java/dev/christopherbell/admin/commandcenter/action/WindowsCommandExecutor.java`
- `website/src/main/java/dev/christopherbell/admin/commandcenter/action/CommandCenterActionService.java`
- `website/src/test/java/dev/christopherbell/admin/AdminActivityServiceTest.java`
- `website/src/test/java/dev/christopherbell/admin/commandcenter/CommandCenterPropertiesTest.java`
- `website/src/test/java/dev/christopherbell/admin/commandcenter/action/CommandCenterActionServiceTest.java`
- `website/src/test/java/dev/christopherbell/admin/commandcenter/action/WindowsCommandExecutorTest.java`

## Final Verification

Focused command covering action, executor, configuration, and explicit-actor
audit tests:

```text
29 tests completed, 0 failed
BUILD SUCCESSFUL in 4s
```

Full command:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-worktrees\.gradle-mobile-command-center'
.\gradlew.bat :website:test
```

Final result:

```text
BUILD SUCCESSFUL in 16s
8 actionable tasks: 3 executed, 5 up-to-date
```

## Commit

This report is included in the commit with subject:

```text
Add protected command center actions
```

The immutable SHA is recorded in the task handoff because including a commit's
own final SHA inside that same commit is circular.

## Concerns

None. Production host commands were never invoked; Windows executor verification
was limited to exact-array inspection and the fail-closed non-Windows seam.

## Formal Security Review Fix Wave

The formal Task 4 review returned `Needs fixes`. All findings were addressed in
one test-first security wave after commit `82f27e30e059421a06c616c7c4a89bf94aa48c7f`.

### RED Evidence

The first focused run stopped at `:website:compileTestJava` because the new tests
required a bounded Windows command-runner seam and `CommandResult` that did not
yet exist:

```text
WindowsCommandExecutorTest.java:73: no suitable constructor found
WindowsCommandExecutorTest.java:79: cannot find symbol CommandResult
6 errors
BUILD FAILED in 2s
```

After adding only that seam so all behavioral tests could execute, the focused
suite produced seven expected failures:

```text
29 tests completed, 7 failed
challengeStorePrunesExpiredEntriesAndCapsPerActorAndTotalSize FAILED
cancellationCannotOvertakeAReservedPowerActionBeforeItsLaunch FAILED
failedCancellationAuditsFailureAfterAcceptanceAndRetainsPendingState FAILED
challengeStringRepresentationsNeverExposeTheOneTimeId FAILED
concurrentWrongPasswordsAtomicallyReserveOnlyThreeFailureSlots FAILED
cancellationAuditIsPersistedBeforeTheFixedCancelCommand FAILED
powerDelayOverrideCannotChangeTheLiteralSixtySecondAllowlist FAILED
BUILD FAILED in 4s
```

The concurrent wrong-password assertion observed all eight attempts pass the
old check-before-record gap (`expected: 3L but was: 8L`). A separate focused
test also proved a configured five-second override changed service-visible
execution time instead of preserving the approved 60 seconds. After the main
fixes, a retry regression test proved failed power launch still retained its
cooldown before rollback was added.

### Security Fixes

- One `actionStateLock` boundary now covers machine-action reservation,
  acceptance audit, fixed `/r` or `/s` launch, and cancellation. The deterministic
  latch test proves `/a` cannot overtake a reserved action before its launch.
- Confirmation validation is serialized per actor through window check,
  challenge consumption, password verification, phrase verification, and
  failure recording. Latch-aligned invalid-ID and wrong-password tests prove
  exactly three failures proceed and later concurrent attempts are throttled.
- `ActionChallenge`, `StoredChallenge`, and `ActionConfirmation` string
  representations redact one-time IDs and submitted secrets without changing
  record accessors used by JSON serialization.
- Windows cancellation runs through an injected command-runner seam, waits at
  most five seconds, requires exit code zero, and treats timeout/non-zero exit as
  failure. Tests use only fake runners; no real shutdown command is executed.
- Cancellation persists `ACCEPTED` before `/a`; process failure records
  `LAUNCH_FAILED` and leaves the pending action cancellable.
- Both fixed Windows arrays and service `executeAt` use an immutable literal
  60-second machine-action delay, regardless of configuration overrides.
- Challenge creation and consumption prune expired entries. The in-memory store
  is capped at eight challenges per actor and 64 total.
- Failed initial machine launch rolls back pending state and cooldown so a fresh
  verified retry can proceed.

### Final Security-Wave Verification

Focused action and executor tests:

```text
30 tests completed, 0 failed
BUILD SUCCESSFUL in 4s
```

Fresh full website suite:

```text
BUILD SUCCESSFUL in 15s
8 actionable tasks: 3 executed, 5 up-to-date
```

No real Windows host action was executed during verification.

An independent final review of the post-`82f27e30` diff found no remaining
actionable issues against the seven formal security findings.
