# Task 3 Report — Coalesce activity writes and rotate with compare-and-set

## Status

Implemented and verified. The final clean `:website:check` completed successfully.

## Before-Edit Brief

- **Behavior:** Interactive browser-session use writes no activity inside five minutes; a due
  use conditionally touches activity, and a due current credential rotates atomically.
- **Invariants:** Absolute expiry never changes; idle expiry is capped to absolute expiry;
  previous-token overlap remains exactly two minutes; conditional misses, revocation races,
  expiry races, and persistence failures cannot authenticate a session.
- **Boundary/API:** `BrowserSessionActivityStore` owns the narrow Mongo transition boundary.
  `BrowserSessionService` retains its public authentication, create, and revocation operations;
  Spring wires the store into the service.
- **Effects and failures:** Mongo `findAndModify` is the only authentication-time persistence
  mutation. A conditional miss returns empty and is not reloaded. Mongo runtime failures
  propagate to the existing filter-level safe credential rejection path.
- **Tests and evidence:** Fixed-clock service tests cover write coalescing, no repository save,
  CAS loss, revocation ordering, absolute/idle expiry, and exact overlap. Mongo-template tests
  capture the conditional query and partial update. MVC-slice wiring and the full regression
  suite provide integration evidence.

## RED Evidence

Command:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.browser.BrowserSessionServiceTest --tests dev.christopherbell.configuration.security.browser.MongoBrowserSessionActivityStoreTest
```

Result: `:website:compileTestJava FAILED` as expected before the new persistence seam existed:

```text
BrowserSessionServiceTest.java:196: error: cannot find symbol
private final BrowserSessionActivityStore activity = mock(BrowserSessionActivityStore.class);
symbol: class BrowserSessionActivityStore
```

The tests added before production edits express the missing activity boundary and behavioral
contract: no write inside the window, one due touch without a `save`, CAS-loss rejection,
revocation-before-rotation rejection, and exact previous-token overlap.

## Implementation

- Added `BrowserSessionActivityStore` and `MongoBrowserSessionActivityStore`.
- `touch` atomically sets only `lastSeenOn` and `idleExpiresOn`.
- `rotate` atomically sets only the current/previous credential and activity fields.
- Both predicates require the session id, live idle expiry, an absolute expiry at least as late
  as the requested idle expiry, and the observed activity or credential version. This both
  preserves the idle/absolute cap and makes stale writers lose.
- `BrowserSessionService.authenticate` neither mutates nor saves the loaded document. A
  successful store transition returns the new authoritative snapshot; a conditional miss fails
  closed without an unsafe reload.
- Rotation only mints a replacement when the presented credential matches the current hash.
  The returned snapshot is revalidated before authentication succeeds.
- Added the new collaborator to the MVC security slice as a Mockito bean and documented the
  coalescing/CAS behavior in the security package README.

## GREEN Evidence

Focused Task 3 tests:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.browser.BrowserSessionServiceTest --tests dev.christopherbell.configuration.security.browser.MongoBrowserSessionActivityStoreTest
```

Result: `BUILD SUCCESSFUL`; 14 tests passed.

MVC-slice regression reproducer after the wiring correction:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.AsyncDispatcherSecurityIntegrationTest
```

Result: `BUILD SUCCESSFUL`; all 3 tests passed.

Full verification:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3'
.\gradlew.bat :website:check
```

Result: `BUILD SUCCESSFUL in 1m 42s`; Java tests, JavaScript tests, boot JAR runtime
verification, and the website check all completed.

## Full-Gate Failure and Resolution

The first full check failed with 1,435 tests completed, 3 failed, and 3 skipped. The failures
were all methods in `AsyncDispatcherSecurityIntegrationTest`; the MVC slice imports
`SecurityConfig` but excludes component-scanned repositories, so its context had no
`BrowserSessionActivityStore` after the service gained that collaborator. The causal error was
`NoSuchBeanDefinitionException` for `BrowserSessionActivityStore`. Adding only a
`@MockitoBean BrowserSessionActivityStore` to that deliberately restricted slice restored its
declared collaborators. The focused reproducer, the 14 Task 3 tests, and the clean full check
then passed.

## Files Changed

- `website/src/main/java/dev/christopherbell/configuration/security/browser/BrowserSessionActivityStore.java`
- `website/src/main/java/dev/christopherbell/configuration/security/browser/MongoBrowserSessionActivityStore.java`
- `website/src/main/java/dev/christopherbell/configuration/security/browser/BrowserSessionService.java`
- `website/src/main/java/dev/christopherbell/configuration/security/SecurityConfig.java`
- `website/src/main/java/dev/christopherbell/configuration/security/README.md`
- `website/src/test/java/dev/christopherbell/configuration/security/browser/BrowserSessionServiceTest.java`
- `website/src/test/java/dev/christopherbell/configuration/security/browser/MongoBrowserSessionActivityStoreTest.java`
- `website/src/test/java/dev/christopherbell/configuration/security/AsyncDispatcherSecurityIntegrationTest.java`

## Self-Review

- Authentication-time `BrowserSessionRepository.save` is absent; the only remaining save is
  session creation.
- No lost-CAS reload exists. Conditional misses reject, so a revoked, idle-expired, or
  concurrently rotated session cannot be resurrected.
- `idleExpiresOn > now` and `absoluteExpiresOn >= requestedIdleExpiresOn` are part of every
  update predicate; an expired record or a record whose absolute expiry no longer supports the
  proposed idle expiry cannot be extended.
- The partial-update tests assert the complete `$set` payload for touch and rotation.
- Review found no unrelated source changes or whitespace errors (`git diff --check` clean).

## Concerns

No open blocker. Mongo queries are verified through captured `MongoTemplate` requests rather
than a live Mongo process; this matches the task's deterministic no-sleep test requirement.

## Review Fix — Preserve the Full Rotation Overlap

Review found that capping `previousTokenExpiresOn` to absolute expiry shortened the promised
two-minute overlap if a due rotation occurred in the final two minutes. The service now rotates
only when `absoluteExpiresOn >= now + ROTATION_OVERLAP`; equality deliberately remains eligible
for exactly two minutes, while fewer than two minutes falls through to the existing due touch.

The new fixed-clock test sets a live session to one nanosecond inside that final window, with
both activity and rotation due. It proves that authentication succeeds without a replacement,
uses `touch` with the absolute-capped idle expiry, and never calls `rotate`.

RED command:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.browser.BrowserSessionServiceTest --tests dev.christopherbell.configuration.security.browser.MongoBrowserSessionActivityStoreTest
```

RED result: `rotationWithLessThanFullOverlapRemainingTouchesWithoutIssuingReplacement` failed
at `BrowserSessionServiceTest.java:128` because the prior implementation issued a replacement.
The same run confirmed both Mongo-store tests passed before production correction.

GREEN command: the same focused command above.

GREEN result: `BUILD SUCCESSFUL`; all 15 focused tests passed, including the new boundary
scenario. The directly related MVC wiring check also passed:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.AsyncDispatcherSecurityIntegrationTest
```

Result: `BUILD SUCCESSFUL`; all 3 MVC-slice tests passed. No full check was run for this scoped
review fix per controller instruction.

The Mongo assertion improvement now reads BSON structurally without key-order dependence: it
checks the separate `$and` clauses for `_id`, `idleExpiresOn: {$gt: now}`, and
`absoluteExpiresOn: {$gte: requestedIdleExpiry}`, while each operation checks its observed CAS
fields directly. This would reject swapped expiry operators or values.

## Runtime Fix Round 2

Task 5's alternate-port candidate startup failed before binding its listener. Spring 7 applied
repository persistence exception translation using the application's class-based proxy mode,
but `MongoBrowserSessionActivityStore` was `final`. The resulting failure chain was
`BeanCreationException` to `AopConfigException` to `IllegalArgumentException: Cannot subclass
final class ...MongoBrowserSessionActivityStore`.

The regression uses a real `AnnotationConfigApplicationContext`, a mocked `MongoTemplate`, and
`PersistenceExceptionTranslationPostProcessor` configured for class proxies. It registers the
actual repository bean and resolves it through `BrowserSessionActivityStore`, exercising the
same framework boundary that prevented application startup rather than checking Java modifiers.

RED command:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3-runtimefix'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.browser.MongoBrowserSessionActivityStoreTest
```

RED result: `BUILD FAILED`; 3 tests completed and
`repositoryCanBeProxiedUsingTheApplicationClassProxyMode` failed at context refresh with the
same `BeanCreationException` / `AopConfigException` / final-class cause observed during startup.

The minimal fix removes `final` only from the repository adapter and documents why this class is
an intentional exception to the usual final-class default. No global AOP or proxy configuration
changed.

GREEN command: the same focused command above.

GREEN result: `BUILD SUCCESSFUL`; all 3 store tests passed, including real class-proxy bean
creation.

Focused Task 3 and MVC regression command:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-gradle\performance-authentication-20260729-task3-runtimefix'
.\gradlew.bat :website:test --tests dev.christopherbell.configuration.security.browser.BrowserSessionServiceTest --tests dev.christopherbell.configuration.security.browser.MongoBrowserSessionActivityStoreTest --tests dev.christopherbell.configuration.security.AsyncDispatcherSecurityIntegrationTest
```

Result: `BUILD SUCCESSFUL`; all 19 selected tests passed. A separate application startup was not
needed because the new regression executes the exact Spring proxy-creation failure boundary;
this also left port 8080 and all Task 5 measurement databases untouched. Task 5's uncommitted
diagnostic configuration remained unmodified and is excluded from this fix commit.
