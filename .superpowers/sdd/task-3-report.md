# Task 3 Report — Shared Folder Read Portal

## Status

Cache/revocation remediation and the Windows native read boundary are implemented on branch
`codex/shared-folder-portal` from Task 2 base `ff2380987788fd462d0bce272ce2119f5bf484b5`.
Enabled Windows production reads now use held-root, handle-relative native opens rather than a
last-moment pathname recheck as their final race boundary.

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
  attachment filenames, and revalidating disk-backed `ResourceRegion` streaming. No
  implementation calls `readAllBytes`.
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
  disk streaming/range handling, content policy, bounded previews, and revalidating resources.
- `website/src/main/java/dev/christopherbell/sharedfolder/fs/` — last-responsible-moment read
  handles, captured leaf-identity checks, fail-closed no-follow channel opening, and the native
  Windows held-root JNA bridge/resource boundary.
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

## Review Remediation — 2026-07-17

### Decisions

- Replaced mutable-path `FileSystemResource` use with a resolver-owned read handle. It captures
  the no-follow leaf identity, rechecks the complete Task 2 chain and leaf identity immediately
  before metadata, directory opens, bounded text input, and `Resource.getInputStream()`, and
  exposes no absolute path to callers. NIO opens ordinary leaves with `NOFOLLOW_LINKS`; a provider
  that does not support that option now fails closed rather than reopening with link-following
  semantics. Portable Java NIO has no `openat`-style root-relative descriptor API, so this is the
  strongest portable boundary: full revalidation directly before an OS no-follow open.
- Replaced browser `fetch(...).blob()` download/media handling with a same-origin root-scoped
  module service worker. It stores the current JWT only in worker memory per controlled client,
  receives it by acknowledged `postMessage`, attaches it only to
  `/api/shared-folder/2026-07-17/` same-origin requests, preserves existing headers including
  `Range`, and never writes bearer data to a URL. The server still invokes the persisted
  `requireRead()` check on every resulting request.
- Native anchors and media receive their normal protected URLs only after worker control and token
  acknowledgement. Worker 401 messages clear client auth and redirect to login; 403/revocation
  messages are rendered as clear inline status. Service-worker setup failures are also caught at
  the download/preview action rather than becoming unhandled rejections.

### New RED Evidence

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderPathResolverTest --tests dev.christopherbell.sharedfolder.SharedFolderReadServiceTest
```

Result: exit `1`; `:website:compileTestJava` reported that
`SharedFolderPathResolver.readHandle(Path)` did not exist in the new deterministic directory-list
and text-preview substitution regressions.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.NioSharedFolderFileSystemBoundaryTest.refusesToReopenAFileWhenTheProviderDoesNotSupportNoFollow
```

Result: exit `1`; `:website:compileTestJava` reported that the test seam could not override
`openNoFollowChannel`, proving the no-follow fail-closed boundary did not yet exist.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderPathResolverTest.readHandleMapsAnUnsupportedNoFollowOpenToTheFailClosedPathResult
```

Result: exit `1`; the assertion received `UnsupportedOperationException` rather than the generic
`UnsafeSharedPathException`, proving an unsupported provider still needed resolver-level
normalization.

### New GREEN Evidence

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderPathResolverTest --tests dev.christopherbell.sharedfolder.SharedFolderReadServiceTest
```

Result: exit `0`; resolver/read-service tests passed, including deterministic directory-list and
text-preview substitutions plus leaf substitutions immediately before download and binary-preview
resource stream opens. The generic late-open error contains no configured absolute path.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.NioSharedFolderFileSystemBoundaryTest.refusesToReopenAFileWhenTheProviderDoesNotSupportNoFollow
```

Result: exit `0`; an unsupported no-follow provider propagates denial and never falls back to a
link-following file open.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderPathResolverTest.readHandleMapsAnUnsupportedNoFollowOpenToTheFailClosedPathResult --tests dev.christopherbell.sharedfolder.fs.NioSharedFolderFileSystemBoundaryTest.refusesToReopenAFileWhenTheProviderDoesNotSupportNoFollow
```

Result: exit `0`; unsupported no-follow handling is both denied at the provider boundary and
normalized to the generic path-safe result seen by service callers.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadControllerTest.mediaPreview_honorsNativeSingleRangeRequests
```

Result: exit `0`; native audio preview returned `206`, `Accept-Ranges: bytes`,
`Content-Range: bytes 2-5/10`, and `Content-Length: 4` through Spring resource streaming.

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website/src/test/js/shared-folder-streaming.test.js
```

Result: exit `0`; 4/4 browser-side tests passed: prefix-only token scope, preserved `Range`, no
token query parameter, no Blob/ObjectURL buffering for anchor/media actions, and actionable
401/403 states.

## Cache and Revocation Remediation — 2026-07-17

### Scope and Decisions

- `SharedFolderNoStoreFilter` applies `Cache-Control: private, no-store` only to the exact
  `/api/shared-folder/2026-07-17/` prefix before security or controller processing. Controller
  responses also set the same header for entries, full/partial content, `HEAD`, `416`, and
  previews; the filter covers protected `401`, `403`, and error responses.
- The service worker now forwards scoped authenticated requests with `cache: 'no-store'` without
  changing the URL or existing headers such as `Range`. It removes the transient per-client token
  on a `401`; logout continues to send the explicit clear message.
- Text preview and native worker streaming now send both `401` and `403` through one
  `handleSharedFolderAccessLoss` UI action. `401` clears local and worker state before login
  redirect; `403` gives the existing capability-revoked status without an unhandled rejection.

### RED

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadControllerTest
```

Result: exit `1`; the new cache/error assertions could not compile because
`SharedFolderNoStoreFilter` did not exist, proving the required pre-security no-store boundary was
absent.

### GREEN

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.SharedFolderReadControllerTest --tests dev.christopherbell.sharedfolder.SharedFolderNoStoreFilterTest
```

Result: exit `0`; entries, full/partial content, `HEAD`, `416`, text/PDF/media preview, `401`,
`403`, and protected `404` all assert `Cache-Control: private, no-store`; the filter test rejects
the near-miss version prefix.

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website/src/test/js/shared-folder-streaming.test.js
```

Result: exit `0`; 5/5 tests passed, covering exact worker scope, authorization/`Range` retention,
no token URL, `cache: 'no-store'`, 401-only worker token eviction, logout clearing, and one
text/native 401/403 action handler.

## Native Windows Handle Boundary — 2026-07-17

### Contract and Decisions

- When `app.shared-folder.enabled` is true on Windows, the singleton
  `WindowsSharedFolderReadBoundary` initializes or fails closed. It opens the configured root once
  with a custom JNA `NtCreateFile` binding, an absolute NT name, `FILE_DIRECTORY_FILE`, and
  `OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE`; its retained handle closes at application shutdown.
- Each request is parsed with the existing Windows-safe grammar. Every selected directory and file
  is then opened one segment at a time relative to the retained/opened parent handle, with no
  pathname-based NIO fallback. Native failure, including
  `STATUS_REPARSE_POINT_ENCOUNTERED (0xC000050B)`, maps to a generic unavailable/not-found result.
- Listings use `GetFileInformationByHandleEx(FileIdBothDirectoryRestartInfo/Info)` on the opened
  directory handle, retain its `EndOfFile` and `LastWriteTime` metadata, and skip reparse or
  grammar-invalid names. File metadata uses `FileIdInfo`; the stream opens a fresh relative native
  file handle and compares volume serial plus the full 128-bit ID before exposing an `InputStream`.
  The stream uses `ReadFile`, `SetFilePointerEx` for range skipping, and idempotent close.
- The native boundary has a fair JVM lifecycle read/write lock so shutdown cannot close the held
  root during an in-process traversal. It is defense in depth only; the native held-root traversal
  is the security boundary. Non-Windows local/test providers keep the prior NIO behavior and do
  not claim this Windows-specific race guarantee.

### RED

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundaryTest
```

Result: exit `1`; `:website:compileTestJava` reported the expected missing
`WindowsSharedFolderNativeBridge`, `WindowsSharedFolderReadBoundary`, handle, metadata, and open
kind symbols. The initial deterministic test suite therefore had no native boundary implementation.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundaryTest.nativeTextPreviewUsesBoundedBlockReadsInsteadOfOneReadFileCallPerByte
```

Result: exit `1`; the counting native-resource preview test observed the unbuffered byte-at-a-time
path instead of bounded block reads.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundaryTest.delayedResourceAfterBoundaryShutdownMapsRootLossToGenericNotFound
```

Result: exit `1`; a resource created before boundary shutdown reached the missing parent handle
instead of returning the generic `FileNotFoundException` required from a delayed stream open.

### GREEN

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundaryTest --tests dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeJnaIntegrationTest
```

Result: exit `0`; deterministic tests cover root-relative `OBJ_DONT_REPARSE` opens, safe reparse
mapping, delayed FileId rejection, same-handle enumeration/unsafe-child filtering, range seek and
idempotent close with mapped close failure, close-on-failed-open, and bounded text-preview bridge reads. The Windows JNA
smoke test created a temporary root and verified native list name/size/mtime, file open/identity,
seek/read, file close, and root close against real Windows APIs.

The delayed resource also now rechecks that the held root remains active under the lifecycle lock
when Spring opens it. A resource created before shutdown returns the generic `FileNotFoundException`
after shutdown rather than invoking a native bridge with no parent handle.

The junction swap regression is gated by Windows capability and remains available for an explicit
local run. It passed on this Windows host with a real `mklink /J` swap target:

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; $env:SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST = 'true'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeJnaIntegrationTest.junctionRaceIsRejectedWhenExplicitNativeIntegrationIsEnabled
```

## Worker Bootstrap Matcher Remediation — 2026-07-17

- `prepareSharedFolderStreamingAuth()` installs the root-scoped
  `/shared-folder-auth-sw.js` before a service worker can attach the JWT. `SecurityConfig` therefore
  permits only an exact anonymous `GET` for that static script; it does not permit POST, trailing
  paths, source-map names, or any `/api/shared-folder/**` request.

### RED

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --tests dev.christopherbell.configuration.SecurityConfigTest --tests dev.christopherbell.configuration.security.SharedFolderWorkerStaticResourceTest
```

Result: exit `1`; the exact worker GET matcher was absent and the anonymous static-resource
retrieval test could not reach the bootstrap asset.

### GREEN

The same focused command now exits `0`. The matcher test proves only the exact GET (including one
with a query string) is public; POST, trailing path, `.map`, and shared-folder API paths
are not. Anonymous MockMvc retrieves the real worker content, while the protected API still returns
`401`. The browser-facing Node lifecycle regression additionally exercises registration of the
root-scoped worker, verifies its expected controller, and resolves only after the token-message
acknowledgement; `node --test website/src/test/js/shared-folder-streaming.test.js` reports `6/6`
passing. Live-browser smoke remains Task 9.

## Page DTO and Worker-Restart Remediation — 2026-07-17

### Scope and Decisions

- `fetchJson()` returns an already-unwrapped account DTO. The shared-folder initializer now passes
  that DTO directly to `accountHasSharedFolderRead()` and does not look for a second `payload`
  wrapper, so effective READ accounts proceed to the protected entries request while denied
  accounts stop before it.
- A service-worker execution-context restart can discard the worker's in-memory per-client JWT
  map while the controlled page and native media request remain live. For an exact shared-folder
  API request with no token, the worker now performs a bounded one-shot message-port request to
  only the initiating controlled client. A recovered token exists only in that worker map, then is
  attached to a no-store clone retaining `Range`; no recovery reply returns a no-store `401`,
  notifies the page, and never forwards an unauthenticated request.

### RED

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website/src/test/js/shared-folder-page-initialization.test.js
```

Result: exit `1`; the desired page-flow test could not import
`initializeSharedFolderPage`, proving the existing initializer had no testable path that exercised
the unwrapped account DTO through its entries render flow.

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website/src/test/js/shared-folder-worker-runtime.test.js
```

Result: exit `1`; Node reported `ERR_MODULE_NOT_FOUND` for
`js/lib/shared-folder-worker-runtime.js`, proving there was no restart-recovery boundary.

### GREEN

```powershell
& 'C:\Progra~1\nodejs\node.exe' --test website/src/test/js/shared-folder-page-initialization.test.js website/src/test/js/shared-folder-worker-runtime.test.js website/src/test/js/shared-folder-streaming.test.js
```

Result: exit `0`; all `12` focused tests passed. Page initialization proves a READ account DTO
reaches the entries request and rendering while a denied DTO does not. Worker lifecycle coverage
proves map loss rehydrates through the initiating client before an authorized no-store request,
preserves `Range`, rejects a timeout or client-lookup failure without a network fetch, notifies an
available page with a controlled `401`, and keeps the recovery runtime free of URL or
persistent-storage token channels.

## Full Verification

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; .\gradlew.bat --no-daemon :website:test --console=plain
```

Result: exit `0`; the complete Java suite passed after cache/revocation and native boundary changes.
The standard run includes the real Windows JNA smoke test; the deliberately capability-gated
junction test is separately recorded above as passed on this host.

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'shared-folder-portal-gradle-task3'; $env:NODE_EXE = 'C:\Progra~1\nodejs\node.exe'; .\gradlew.bat --no-daemon :website:jsTest --console=plain
```

Result: exit `0`; Node reported `141` passed, `0` failed.

```powershell
git diff --check
```

Result: exit `0` (only pre-existing Git line-ending warnings were emitted).

## Concerns and Follow-Up

- No live Spring Boot/browser smoke session was run because this task's endpoints require a real
  JWT-backed account and a controlled shared-folder root. The controller, service, security, and
  browser contracts are covered by the focused and complete automated suites.
- The native browser path requires service-worker support. Unsupported browsers receive a clear
  action-level status instead of an insecure Blob or bearer-URL fallback.
