# Task 3 Report — Shared Folder Read Portal

## Status

Completed on branch `codex/shared-folder-portal` from Task 2 base
`ff2380987788fd462d0bce272ce2119f5bf484b5`.

## Scope Delivered

- Added the public, data-free `/shared` Thymeleaf shell and allowed only that page through the
  public security matcher. All `/api/shared-folder/2026-07-17/**` routes remain authenticated.
- Added protected directory listing, disk-backed full/single-range download, `HEAD`, and preview
  endpoints. Every route calls `SharedFolderAccessService.requireRead()` before invoking a
  filesystem service.
- Added public-safe listing models and services that pass the already-decoded HTTP query value to
  the Task 1/2 resolver unchanged, return relative metadata only, and map unsafe/missing paths to
  generic 404 responses without local paths.
- Added single-range `206`/`416` behavior with `Accept-Ranges`, correct `Content-Range`, safe
  attachment filenames, and `FileSystemResource`/`ResourceRegion` streaming. No implementation
  calls `readAllBytes`.
- Added bounded UTF-8 text previews, allowlisted image/audio/video/PDF previews, attachment-only
  active/unknown formats, `nosniff`, a sandbox CSP and iframe sandbox for PDFs, and safe text-node
  rendering in the browser.
- Added the responsive portal UI, relative breadcrumbs, copyable internal links, login/denial
  handling, download controls, and a nav item shown only after `/api/accounts/2025-09-03/me`
  reports effective shared-folder read capability.

## TDD Evidence

The worktree contained an inherited, uncommitted partial Task 3 shell and test patch. It was
treated as Task 3 scope after the coordinator confirmed ownership; no unrelated files were
reverted. The inherited service test supplied the first red state before any read-service
production code existed.

### RED

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadServiceTest --console=plain
```

Result: exit `1`; `:website:compileTestJava` reported five expected missing symbols: the
`SharedFolderPreviewKind` model and `SharedFolderBrowserService`, `SharedFolderDownloadService`,
and `SharedFolderPreviewService` packages/classes.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadControllerTest --console=plain
```

Result: exit `1`; `:website:compileTestJava` reported the expected missing model, service, and
`SharedFolderReadController` types (19 compile errors), proving the HTTP contract had no
implementation.

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website\src\test\js\nav-messages-link.test.js
```

Result: exit `1`; the new nav test failed with `TypeError: profileMenuItems is not a function`.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadControllerTest --console=plain
```

Result: exit `1`; the multi-range assertion failed because the partial controller returned
`Content-Range: bytes */0` before it knew the selected file length.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadServiceTest --console=plain
```

Result: exit `1`; the newly added empty `Range` header case failed because the partial service
treated an explicitly blank header as a full download.

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website\src\test\js\shared-folder.test.js
```

Result: exit `1`; the new PDF UI safety assertion found no iframe `sandbox` attribute.

### GREEN

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadServiceTest --tests dev.christopherbell.sharedfolder.SharedFolderReadControllerTest --console=plain
```

Result: exit `0`; all 12 focused read service/controller tests passed. They cover anonymous and
stale denial for every route, list metadata/no absolute-path leak, full/range/HEAD downloads,
malformed/multiple/empty/unsatisfied ranges, missing files, safe dispositions, text bounds, and
text/PDF preview headers.

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website\src\test\js\nav-messages-link.test.js
& 'C:\Progra~1\nodejs\node.exe' --test website\src\test\js\shared-folder.test.js
```

Result: both exit `0`; the nav test passed 11/11 and the shared-folder test passed 6/6. Syntax
checks also passed for `components/nav.js`, `shared-folder.js`, and `lib/shared-folder.js`.

## Files Changed

- `website/src/main/java/dev/christopherbell/sharedfolder/model/` — safe list and preview DTOs.
- `website/src/main/java/dev/christopherbell/sharedfolder/service/` — resolver-backed browsing,
  disk streaming/range handling, content policy, and bounded previews.
- `website/src/main/java/dev/christopherbell/sharedfolder/web/SharedFolderReadController.java` —
  protected HTTP routes and response headers.
- `website/src/main/java/dev/christopherbell/configuration/security/SecurityConfig.java` and its
  tests — public `/shared` shell only.
- `website/src/main/java/dev/christopherbell/view/content/ContentViewController.java`, template,
  styles, page module, API/helper modules, nav, and browser tests — portal UI and gated discovery.
- `website/src/test/java/dev/christopherbell/sharedfolder/` — service and controller regression
  coverage.
- Feature, view, security, JavaScript, and CSS READMEs — updated ownership and safety contracts.

## Decisions

- Kept effective authorization in the existing Task 2 `SharedFolderAccessService`; the controller
  calls it synchronously per operation, so an unchanged JWT cannot retain a revoked persisted
  capability.
- Let Spring perform the single HTTP query decode. Neither the controller nor the services call a
  URL decoder, and the resolver rejects a residual encoded separator.
- Used a strict preview allowlist instead of MIME sniffing. HTML/SVG and unrecognized formats are
  attachment-only; text is returned as JSON and rendered with `textContent`.
- Joined multiple `Range` header values only to route them through the safe download boundary. The
  boundary rejects anything other than one syntactically valid range and gives the controller the
  correct total length for a standards-correct `416` response.

## Full Verification

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --console=plain
```

Result: completed successfully; 79 JUnit XML reports were produced and a post-run scan found
`JUNIT_FAILURES=0`.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; $env:NODE_EXE = 'C:\Progra~1\nodejs\node.exe'; .\gradlew.bat --no-daemon :website:jsTest --console=plain
```

Result: exit `0`; Node reported `129` passed, `0` failed.

```powershell
git diff --check
```

Result: exit `0` (only pre-existing Git line-ending warnings were emitted).

## Concerns and Follow-Up

- No live Spring Boot/browser smoke session was run because this task's endpoints require a real
  JWT-backed account and a controlled shared-folder root. The controller, service, security, and
  browser contracts are covered by the focused and complete automated suites.
- Download initiation currently uses an authenticated browser fetch and Blob URL because the
  application stores bearer tokens client-side. Server-side file transfer remains disk-streamed;
  a future native browser streaming download design would need a separately scoped credential
  handoff rather than placing a bearer token in a URL.
