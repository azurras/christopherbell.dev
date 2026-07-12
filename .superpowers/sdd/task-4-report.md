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
