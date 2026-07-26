# Shared-Folder Search and Radio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add recursive shared-folder search and one durable, approximately synchronized radio station that randomly plays audio from every folder below `Shared/Music` for accounts with shared-folder read access.

**Architecture:** A short-lived, public-safe catalog snapshot reuses the existing held-root browser boundary, so search and radio never walk unvalidated absolute paths. A Mongo-backed radio service owns the current track, start time, duration, and random transition; browsers report a bounded media duration, join at the server-computed position, and periodically correct track or drift through the persistent site player.

**Tech Stack:** Java 21, Spring Boot, Spring Data MongoDB, JUnit 5/AssertJ/MockMvc, browser-native JavaScript modules and custom elements, Node test runner, existing authenticated shared-folder streaming/transcoding.

## Global Constraints

- Every search, station-state, and station-duration request calls `SharedFolderAccessService.requireRead()`.
- Search accepts a trimmed query of 1-200 characters, scans recursively, and returns at most 200 public-safe matches with a `truncated` flag; it does not expose absolute paths.
- The catalog has one bounded freshness policy and uses only `SharedFolderBrowserService.list` so Windows held-root protections remain authoritative.
- Radio considers only entries whose preview kind is `AUDIO` below the root-level `Music` directory, case-insensitively.
- The persisted station has one mutable owner and uses an injected `Clock` and random index source for deterministic tests.
- Client duration reports must match the active path and station sequence and be finite between 1 and 86,400 seconds.
- Radio synchronization is approximate: clients join the server position and poll periodically, changing tracks or correcting drift greater than three seconds.
- Existing direct playback, transcoding fallback, album art, metadata, same-tab resume, and site-wide navigation behavior must remain intact.

---

### Task 1: Recursive public-safe catalog and search

**Files:**
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/service/SharedFolderCatalogService.java`
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/model/SharedFolderSearchResponse.java`
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/web/SharedFolderReadController.java:24-71`
- Test: `website/src/test/java/dev/christopherbell/sharedfolder/SharedFolderCatalogServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/sharedfolder/SharedFolderReadControllerTest.java:1-180`

**Interfaces:**
- Consumes: `SharedFolderBrowserService.list(String)` and `SharedFolderAccessService.requireRead()`.
- Produces: `SharedFolderCatalogService.search(String)` and `GET /api/shared-folder/2026-07-17/search?query=...` returning `SharedFolderSearchResponse(query, entries, truncated)`.

- [x] Write a failing service test with nested directories, mixed-case matches, a non-matching sibling, and more than the response limit; assert recursive matching, public-safe relative paths, ordering, and truncation.
- [x] Run `./gradlew.bat :website:test --tests '*SharedFolderCatalogServiceTest'` and confirm failure because the catalog/search API does not exist.
- [x] Implement a 15-second immutable catalog snapshot, breadth-first recursive traversal through `SharedFolderBrowserService`, query validation, case-insensitive name/path matching, and a 200-result response limit.
- [x] Re-run the focused service test and confirm it passes.
- [x] Add failing MockMvc tests proving the search route requires fresh read access, returns the response, and audits `SEARCH` without exposing filesystem paths.
- [x] Add the controller route, re-run `./gradlew.bat :website:test --tests '*SharedFolderReadControllerTest'`, and confirm it passes.

### Task 2: Durable server-owned radio state

**Files:**
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/radio/SharedFolderRadioDocument.java`
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/radio/SharedFolderRadioRepository.java`
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/radio/SharedFolderRadioService.java`
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/model/SharedFolderRadioResponse.java`
- Create: `website/src/main/java/dev/christopherbell/sharedfolder/model/SharedFolderRadioDurationRequest.java`
- Modify: `website/src/main/java/dev/christopherbell/sharedfolder/web/SharedFolderReadController.java:24-71`
- Test: `website/src/test/java/dev/christopherbell/sharedfolder/radio/SharedFolderRadioServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/sharedfolder/SharedFolderReadControllerTest.java:1-220`

**Interfaces:**
- Consumes: `SharedFolderCatalogService.audioTracksBelowMusic()`, `SharedFolderRadioRepository`, `Clock`, and a bounded random index function.
- Produces: `SharedFolderRadioService.current()` and `reportDuration(request)`, `GET /radio`, and `POST /radio/duration`.

- [x] Write failing service tests for no Music tracks, recursive audio-only selection, immediate-repeat avoidance, persisted restart continuity, bounded duration validation, stale-report rejection, and track advancement from injected wall time.
- [x] Run `./gradlew.bat :website:test --tests '*SharedFolderRadioServiceTest'` and confirm failure because radio state does not exist.
- [x] Implement the Mongo document/repository and synchronized station transition that performs the smallest complete read-decide-save operation, with no media process invocation or filesystem I/O inside the station lock.
- [x] Re-run the focused radio tests and confirm they pass.
- [x] Add failing controller tests for read authorization and safe radio payloads, then add the two endpoints and audit actions `RADIO_LISTEN` and `RADIO_DURATION_REPORTED`.
- [x] Re-run the controller test and confirm it passes.

### Task 3: Search browser interface

**Files:**
- Modify: `website/src/main/resources/static/js/lib/api.js:75-113`
- Modify: `website/src/main/resources/static/js/lib/shared-folder.js:150-250`
- Modify: `website/src/main/resources/static/js/shared-folder.js:1-700`
- Modify: `website/src/main/resources/templates/shared-folder.html:20-55`
- Modify: `website/src/main/resources/static/css/main.css:5941-6380`
- Test: `website/src/test/js/shared-folder.test.js:1-520`

**Interfaces:**
- Consumes: `API.sharedFolder.search(query)` and existing `renderEntries`/preview/navigation behavior.
- Produces: a semantic search form with clear action, recursive result rendering with parent paths, and restoration of the active directory after clearing.

- [x] Add failing Node tests for exact query encoding, validated response/result presentation helpers, and the search form contract.
- [x] Run `./gradlew.bat :website:jsTest` and confirm the new assertions fail for missing search behavior.
- [x] Implement the API path, pure search helpers, form wiring, cancellable request generation, result labels, clear behavior, responsive CSS, and accessible status text.
- [x] Re-run `./gradlew.bat :website:jsTest` and confirm the focused browser contract passes.

### Task 4: Persistent synchronized radio playback

**Files:**
- Modify: `website/src/main/resources/static/js/lib/api.js:75-113`
- Modify: `website/src/main/resources/static/js/lib/site-media-player.js:1-520`
- Modify: `website/src/main/resources/static/js/components/site-media-player.js:65-805`
- Modify: `website/src/main/resources/static/js/shared-folder.js:1-700`
- Modify: `website/src/main/resources/templates/shared-folder.html:20-55`
- Modify: `website/src/main/resources/static/css/main.css:6380-6755`
- Test: `website/src/test/js/site-media-player.test.js:1-470`
- Test: `website/src/test/js/shared-folder.test.js:1-520`

**Interfaces:**
- Consumes: `GET /radio`, `POST /radio/duration`, existing authenticated preview/fallback streams, and the persistent top-document player.
- Produces: `playSharedFolderRadio(browserWindow)`, radio-aware same-tab resume, bounded duration reporting, 15-second synchronization, three-second drift correction, and automatic next-track loading.

- [x] Add failing pure Node tests for radio response validation, target-position/drift decisions, old item-resume compatibility, radio-resume restoration, and timer teardown ownership.
- [x] Run `./gradlew.bat :website:jsTest` and confirm the new radio assertions fail for missing behavior.
- [x] Implement the API helpers and pure radio validation/synchronization functions before wiring DOM effects.
- [x] Add the `Radio` control to the shared-folder command bar and delegate it to the top-document player.
- [x] Extend the player descriptor with `ITEM`/`RADIO` mode, fetch current station state on radio restore/play, report loaded duration once per source sequence, synchronize periodically, advance on ended media, and disable item-only seeking/rate controls while in radio mode.
- [x] Re-run `./gradlew.bat :website:jsTest` and confirm all browser tests pass.

### Task 5: Regression, review, and delivery

**Files:**
- Review every changed production and test file above together.

**Interfaces:**
- Consumes: all backend and browser contracts from Tasks 1-4.
- Produces: one cohesive commit, one pushed branch, one PR, green CI, merge to `main`, automatic deployment, and live authenticated smoke evidence.

- [x] Run focused Java tests for catalog, radio, and the read controller.
- [x] Run `./gradlew.bat :website:jsTest`.
- [x] Run `./gradlew.bat :website:test` and `./gradlew.bat :website:check` with an isolated Gradle user home if the shared registry is unavailable.
- [x] Inspect `git diff --check`, `git status --short --branch`, and the production/test diff for access bypasses, absolute-path leaks, unowned timers, stale state transitions, and unrelated changes.
- [ ] Commit the cohesive feature, push `codex/shared-folder-search-radio`, open one PR, wait for required CI, merge, and verify the push-to-main deployment without interactive prompts.
- [ ] Smoke-test production search and radio authorization/state plus the live deployed release SHA; preserve any gap requiring a real browser session explicitly.
