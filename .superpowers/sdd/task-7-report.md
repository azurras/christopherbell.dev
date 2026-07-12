# Task 7 Documentation and Verification Report

## Status

DONE

## Documentation

- `README.md` documents production Mission Control mode, fixed WinSW/log/shutdown
  paths, and the simulated local/default boundary.
- `website/src/main/java/dev/christopherbell/admin/README.md` documents command
  center ownership, cached/unavailable provider semantics, bounded configured log
  tail, exact action set, simulated default, fixed Windows mapping, step-up
  requirements, API routes, security boundary, and verification commands.
- `website/src/main/java/dev/christopherbell/view/README.md` documents the public
  `/command-center` route as a data-free shell whose data remains private.
- `website/src/main/java/dev/christopherbell/configuration/README.md` documents
  public/private routes and the sampling, log, threshold, mode, fixed-path, and
  action-abuse settings. It distinguishes the declared `power-delay` setting
  from the independently enforced fixed 60-second implementation contract.
- `website/src/main/resources/static/js/README.md` documents role gating,
  five-second bounded polling, hidden-tab pause, request serialization,
  abortable generations, cursor invalidation, literal search, text-only logs,
  challenge-dialog clearing, action ownership, and access-loss teardown.
- `website/src/main/resources/static/css/README.md` documents `.command-*`
  Mission Control ownership and responsive/state contracts.

No behavior, production configuration, application process, or production port
was changed or exercised in Task 7.

## Exact Verification Commands and Results

All Gradle commands used:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-worktrees\.gradle-mobile-command-center'
```

JavaScript syntax and focused browser behavior:

```powershell
node --check website/src/main/resources/static/js/command-center.js
node --check website/src/main/resources/static/js/lib/command-center.js
node --check website/src/main/resources/static/js/components/nav.js
node --check website/src/main/resources/static/js/lib/api.js
node --check website/src/main/resources/static/js/lib/util.js
node --test website/src/test/js/command-center.test.js
git diff --check
```

Result: all five parser checks exited 0; focused JavaScript reported 16 tests,
16 passed, 0 failed; `git diff --check` exited 0.

Focused Java command:

```powershell
.\gradlew.bat :website:test `
  --tests dev.christopherbell.admin.AdminActivityServiceTest `
  --tests dev.christopherbell.admin.commandcenter.CommandCenterControllerTest `
  --tests dev.christopherbell.admin.commandcenter.CommandCenterPropertiesTest `
  --tests dev.christopherbell.admin.commandcenter.action.CommandCenterActionServiceTest `
  --tests dev.christopherbell.admin.commandcenter.action.WindowsCommandExecutorTest `
  --tests dev.christopherbell.admin.commandcenter.logs.CommandCenterLogServiceTest `
  --tests dev.christopherbell.admin.commandcenter.metrics.CommandCenterMetricsServiceTest `
  --tests dev.christopherbell.admin.commandcenter.metrics.NvidiaMetricsProviderTest `
  --tests dev.christopherbell.view.ViewControllerTest
```

Result: every selected test in the nine focused classes passed; `BUILD
SUCCESSFUL in 7s` with 8 actionable tasks (3 executed, 5 up-to-date).

Full JavaScript suite:

```powershell
.\gradlew.bat :website:jsTest
```

Result: 112 tests, 112 passed, 0 failed; `BUILD SUCCESSFUL`.

Full website Java suite:

```powershell
.\gradlew.bat :website:test
```

Result: `BUILD SUCCESSFUL in 16s`. Fresh JUnit XML contained 64 suites/files,
517 tests, 0 failures, 0 errors, and 0 skipped.

Full website build and configured checks:

```powershell
.\gradlew.bat :website:build
```

Result: compilation, assembly, configured `:website:jsTest`, complete Java tests,
`:website:check`, and `:website:build` succeeded in 20s. The build reran 112/112
browser tests and the full Java suite.

## Diff and Security Scan

The feature scope was resolved from the merge base:

```powershell
$base = git merge-base origin/main HEAD
# 959621f15b5a13822d9e4bb1e9a0233ac846c9d8
$diff = git diff --unified=0 --no-ext-diff $base -- `
  cbell-lib/src/main/java/dev/christopherbell/libs/api/APIVersion.java `
  website/build.gradle.kts website/src/main website/src/test
```

The added lines were searched case-insensitively for:

```text
Runtime.exec | cmd.exe | powershell
ProcessBuilder
caller/request-provided path, file, executable, service, or argument fields
password adjacent to log.trace/debug/info/warn/error
innerHTML
hard-coded api-key/password/secret/token assignments and private-key markers
```

Results:

- No `Runtime.exec`, `cmd.exe`, or PowerShell invocation.
- Two legitimate `ProcessBuilder` matches: the fixed Windows action array and
  the fixed `nvidia-smi` executable plus constant query arguments. Neither uses
  a shell or caller-provided command/arguments.
- Path matches are reads from server-bound `CommandCenterProperties` and tests
  asserting the production profile. Controller inputs contain only cursor,
  supported log level, a 100-character literal query, the closed action enum,
  challenge ID, password, and exact phrase; no caller path, filename,
  executable, service name, or arbitrary argument exists.
- No password-logging match. Action audit metadata contains only action, client
  IP, configured mode, and outcome.
- The only feature-diff `innerHTML` match is the negative regression assertion
  that `logOutput.innerHTML` is absent. Command-center logs use `textContent`.
  Existing `innerHTML` uses elsewhere in static page/template modules predate
  and are outside this feature diff; they are not command-center log rendering.
- Secret-like assignments are test fixtures only: a password used by action
  service tests and a fake NVIDIA failure string used to prove path/token
  sanitization. No production credential, API key, JWT, private key, or secret
  was added.

Final Task 7 working diff before this report: six documentation files, 88
insertions, and 24 deletions. `git diff --check` remained clean; Git emitted only
the repository's normal Windows LF-to-CRLF checkout warnings.

## Self-Review

- Re-read every changed document against the final controller, properties,
  profiles, action enum/service/executor, log service, metrics providers,
  template, JavaScript modules, CSS, and tests.
- Corrected an initially over-broad statement about `power-delay`: runtime code
  enforces 60 seconds independently of the bound property, so the final docs do
  not claim that changing the property changes behavior.
- Confirmed the docs do not promise live runtime testing, caller-selected paths,
  arbitrary host actions, HTML log rendering, or environment variables that do
  not exist.
- Confirmed only Task 7 documentation and this report are changed.

## Commit

This report is included in the commit with subject:

```text
Document command center operations
```

The immutable SHA is recorded in the task handoff because a commit cannot embed
its own final SHA.

## Concerns

None. Per task scope, the app was not started and production was not touched;
verification is static, unit/integration, browser-module, and build evidence.

## Formal Review Fix: Fresh Persisted Admin Access

The Task 7 review found that the five controller method-security expressions
validated only ADMIN JWT authority. The action service separately rechecked the
persisted account, but snapshot and log reads did not, and the other action
routes did not fail at the shared controller boundary.

### TDD RED

The first focused test was written before `CommandCenterAccessService` existed:

```powershell
$env:GRADLE_USER_HOME='A:\Projects\christopherbell.dev-worktrees\.gradle-mobile-command-center'
.\gradlew.bat :website:test --tests dev.christopherbell.admin.commandcenter.CommandCenterAccessServiceTest
```

It failed for the expected missing production type:

```text
cannot find symbol: class CommandCenterAccessService
:website:compileTestJava FAILED
BUILD FAILED in 2s
```

After the narrow service contract passed, controller tests were added for all
five routes with suspended, unapproved, and missing persisted admins. Before the
controller annotations changed, the exact focused command:

```powershell
.\gradlew.bat :website:test --tests dev.christopherbell.admin.commandcenter.CommandCenterControllerTest
```

reported the intended RED:

```text
33 tests completed, 15 failed
BUILD FAILED in 6s
```

All 15 failures were the expected stale-account cases: snapshot, logs,
challenge, execute, and cancel each admitted suspended, unapproved, and missing
persisted admins instead of returning 403.

### Fix and GREEN

`CommandCenterAccessService` now resolves the current account ID and permits
only a freshly loaded account whose role is ADMIN, status is ACTIVE, and approval
flag is true. Blank/missing identities, missing accounts, identity parsing
failures, and repository failures return false. Every command-center controller
method requires both `permissionService.hasAuthority('ADMIN')` and this fresh
check. The action service retains its execution-time recheck as defense in depth.

Focused GREEN command:

```powershell
.\gradlew.bat :website:test `
  --tests dev.christopherbell.admin.commandcenter.CommandCenterAccessServiceTest `
  --tests dev.christopherbell.admin.commandcenter.CommandCenterControllerTest
```

Result: 41 tests passed, including all 15 stale-account denials with zero
metrics, log, or action interactions; anonymous remained 401, USER remained 403,
and active approved persisted admins retained successful access.

Fresh full verification:

```powershell
.\gradlew.bat :website:test
.\gradlew.bat :website:build
```

Results: full Java tests produced 65 JUnit XML files with 540 tests, 0 failures,
0 errors, and 0 skipped. The build reran 112 browser tests, all Java tests,
assembly, `:website:check`, and `:website:build`; `BUILD SUCCESSFUL in 20s`.

## Reproducible Added-Line Security Scan

The formal review requested literal commands, per-search exit codes, and
captured legitimate matches. The following PowerShell was rerun from the
repository root after the access fix. README files are excluded so documentation
phrasing cannot create false code matches; production and test code remain in
scope.

```powershell
$base = git merge-base origin/main HEAD
"BASE=$base EXIT=$LASTEXITCODE"
$featureDiff = git diff --unified=0 --no-ext-diff $base -- `
  cbell-lib/src/main/java website/build.gradle.kts website/src/main `
  website/src/test ':(exclude)**/README.md'
"FEATURE_DIFF_EXIT=$LASTEXITCODE"
$addedLines = $featureDiff | rg -N '^\+' | rg -N -v '^\+\+\+ '
"ADDED_LINES_EXIT=$LASTEXITCODE COUNT=$($addedLines.Count)"

$addedLines | rg -n -i 'Runtime\.exec'
"RUNTIME_EXEC_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i 'cmd\.exe'
"CMD_EXE_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i 'powershell'
"POWERSHELL_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i `
  '(RequestParam|RequestBody|PathVariable|record\s+\w+Request).*(path|file|filename|executable|service|args|arguments)|(path|file|filename|executable|service|args|arguments).*(RequestParam|RequestBody|PathVariable|record\s+\w+Request)'
"CALLER_PATHS_ARGS_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i `
  'log\.(trace|debug|info|warn|error).*password|password.*log\.(trace|debug|info|warn|error)'
"PASSWORD_LOGGING_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i 'innerHTML'
"INNER_HTML_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i `
  'PASSWORD\s*=|secret\s*=|api[_-]?key\s*[:=]|token\s*[:=]|BEGIN .*PRIVATE KEY'
"SECRET_MARKERS_EXIT=$LASTEXITCODE"
$addedLines | rg -n -i `
  'ProcessBuilder|getLogPath|getWinSwExecutable|getShutdownExecutable|nvidia-smi'
"LEGITIMATE_PROCESS_AND_FIXED_PATHS_EXIT=$LASTEXITCODE"
```

Top-level captured results:

```text
BASE=959621f15b5a13822d9e4bb1e9a0233ac846c9d8 EXIT=0
FEATURE_DIFF_EXIT=0
ADDED_LINES_EXIT=0 COUNT=5032
RUNTIME_EXEC_EXIT=1
CMD_EXE_EXIT=1
POWERSHELL_EXIT=1
CALLER_PATHS_ARGS_EXIT=1
PASSWORD_LOGGING_EXIT=1
INNER_HTML_EXIT=0
SECRET_MARKERS_EXIT=0
LEGITIMATE_PROCESS_AND_FIXED_PATHS_EXIT=0
```

For `rg`, exit 1 means no match. Therefore `Runtime.exec`, `cmd.exe`,
PowerShell, caller-provided path/filename/executable/service/arguments, and
password logging all had no added-line match.

The complete `innerHTML` output was the negative regression assertion:

```text
5001:+  assert.doesNotMatch(script, /logOutput\.innerHTML/);
```

Command-center logs use `textContent`; no added command-center log renderer uses
HTML. Existing static page/template-module `innerHTML` elsewhere is outside this
feature diff and does not render command-center logs.

The complete broad secret-marker output was:

```text
439:+      if (password == null || !PasswordUtil.verifyPassword(
632:+          + ", challengeId=<redacted>, password=<redacted>, confirmationPhrase=<redacted>]";
2448:+  const password = passwordInput.value;
3271:+  private static final String PASSWORD = "correct horse battery staple";
4114:+        "INFO value a.b\nINFO value axb\nINFO token=hidden a.b\n");
4118:+    var secret = service.read(null, null, "hidden");
4121:+        .containsExactly("INFO value a.b", "INFO token=[REDACTED] a.b");
4298:+    assertSplitSecretIsNeverReturned("INFO token=" + "t".repeat(80));
4333:+            + "INFO password=hunter2 api_key: abc123 secret = hush token=tok-value\n"
4343:+        .contains("password=[REDACTED]", "api_key: [REDACTED]", "secret = [REDACTED]", "token=[REDACTED]");
4516:+    var secret = "C:\\private\\service\\nvidia-smi.exe --secret-token=abc123";
```

The first three production matches are immediate password verification,
redacted string output, and the browser's transient password variable, which is
cleared before the request completes. Every remaining match is a test fixture
proving literal search-after-redaction, split-secret suppression, credential
redaction, or provider-detail sanitization. No real secret, key, or token exists
in the diff.

The fixed-process/path inspection captured these production boundaries:

```text
757:+      case RESTART_SITE -> List.of(actions.getWinSwExecutable().toString(), "restart");
759:+          actions.getShutdownExecutable().toString(), "/r", "/t", "60",
762:+          actions.getShutdownExecutable().toString(), "/s", "/t", "60",
765:+          List.of(actions.getShutdownExecutable().toString(), "/a");
776:+    var process = new ProcessBuilder(command).start();
849:+    Path logPath = properties.getLogPath();
1454:+/** Reads NVIDIA GPU data with a fixed, bounded {@code nvidia-smi} invocation. */
1466:+    this("nvidia-smi", properties.getProviderTimeout(), NvidiaMetricsProvider::runCommand);
1544:+    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
```

Additional matches were tests asserting fixed production paths, absence of host
paths in serialized metrics, exact fixed NVIDIA invocations, and sanitization of
a fake provider error containing a path/token. The two `ProcessBuilder` calls
receive only the closed action-to-array mapping or fixed `nvidia-smi` query; no
shell or caller-supplied path/argument reaches either process boundary.

## Formal Review Fix Commit

This review fix and the appended evidence are included in the commit with
subject:

```text
Enforce fresh command center access
```

The immutable SHA is recorded in the handoff because embedding a commit's own
SHA in that same commit is circular.

## Formal Review Fix Self-Review and Concerns

- Confirmed all five annotations contain both authorization predicates.
- Confirmed the access bean denies on every invalid persisted state and runtime
  lookup failure without logging account or token data.
- Confirmed controller denials occur before metrics, logs, or actions are called.
- Confirmed `CommandCenterActionService` still performs its execution-time
  persisted-account recheck.
- Confirmed the report commands rerun from the repository root and their stated
  outputs match the captured result.
- No app process or production operation was started. No remaining concern.
