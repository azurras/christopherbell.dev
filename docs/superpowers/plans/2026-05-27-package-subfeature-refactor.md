# Package Subfeature Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the repository toward smaller feature/subfeature packages so each package is easier to understand, test, and change without creating god classes.

**Architecture:** This is a behavior-preserving package-boundary refactor. Move classes and extract narrowly focused services by feature responsibility, keep public API paths stable, keep Spring component scanning under `dev.christopherbell`, and update tests/docs with every package task.

**Tech Stack:** Java 21, Spring Boot 3.4, MongoDB repositories, Thymeleaf, vanilla JavaScript ES modules, Gradle Wrapper, Node syntax/test runner for browser modules.

---

## Global Rules For Every Task

- Do not change endpoint paths, request/response JSON, template names, or database field names unless the task explicitly says so.
- Prefer package moves and small service extractions over large rewrites.
- Keep controllers thin. Business rules belong in services inside the owning subfeature package.
- Keep repositories next to the subfeature that owns their data access.
- Keep DTO/entity classes in the existing `model` package unless a subfeature already has enough DTOs to justify `subfeature/model`.
- Update the package README touched by the move.
- Run targeted tests before and after each package task.
- Run `./gradlew :website:test` after every high-risk package: `account`, `post`, `vehicle`, `whatsforlunch`, `configuration`.
- Use `node --check` and `node --test website/src/test/js/*.test.js` for frontend package tasks.
- If Gradle cache locks appear, use:

```bash
./gradlew --stop
```

Then rerun the command. Do not delete repository files to clear locks.

---

### Task 1: Baseline Inventory And Safety Net

**Files:**
- Read: `AGENTS.md`
- Read: `README.md`
- Read: `website/src/main/java/dev/christopherbell/README.md`
- Create or modify only if missing: package README files for packages touched later

- [x] **Step 1: Capture current status**

Run:

```bash
git status --short
```

Expected: The worktree may already be dirty. Do not revert unrelated files.

- [x] **Step 2: Capture package and class-size hotspots**

Run:

```bash
find website/src/main/java/dev/christopherbell -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
find website/src/main/java/dev/christopherbell -name '*.java' -print | xargs wc -l | sort -nr | head -60
find website/src/main/resources/static/js -maxdepth 2 -type f -name '*.js' -print | xargs wc -l | sort -nr | head -40
```

Expected: `RestaurantService`, `PostService`, `AccountService`, `NhtsaVinEnrichmentService`, `VehicleService`, `ViewController`, `whats-for-lunch.js`, `back-office.js`, `feed-render.js`, `nav.js`, and `main.css` remain the main hotspots.

- [x] **Step 3: Run baseline tests**

Run:

```bash
./gradlew :website:test
node --test website/src/test/js/*.test.js
```

Expected: Both pass before package moves begin. If they fail, stop and document the pre-existing failure in the task notes before changing code.

---

### Task 2: Account Package

**Current package:** `website/src/main/java/dev/christopherbell/account`

**Target subpackages:**
- `dev.christopherbell.account.auth` for login/token-adjacent account flows.
- `dev.christopherbell.account.passwordreset` for password reset token and mail handoff.
- `dev.christopherbell.account.profile` for public profile and self-account reads.
- `dev.christopherbell.account.follow` for follow/unfollow behavior.
- `dev.christopherbell.account.moderation` for approval, status, and role updates.
- Keep `dev.christopherbell.account.model` and `dev.christopherbell.account.model.dto`.

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/account/AccountService.java`
- Modify: `website/src/main/java/dev/christopherbell/account/AccountController.java`
- Move or extract from: `website/src/main/java/dev/christopherbell/account/PasswordResetNotificationService.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/account/`
- Modify docs: `website/src/main/java/dev/christopherbell/account/README.md`

- [x] **Step 1: Add characterization coverage for service slices before extraction**

Ensure these tests exist or add focused equivalents in `AccountServiceTest`:

```java
@Test
void loginAccount_rejectsSuspendedAccounts() throws Exception {
  // Use existing login test style.
  // Arrange an account with AccountStatus.SUSPENDED.
  // Assert AccountNotActiveException or existing inactive-account exception.
}

@Test
void requestPasswordReset_existingEmailStoresTokenAndSendsLink() {
  // Keep current password reset behavior locked before moving reset code.
}

@Test
void followAccount_addsTargetToCurrentAccountFollowingSet() throws Exception {
  // Keep follow behavior locked before moving follow code.
}
```

- [x] **Step 2: Extract password reset service**

Create package:

```text
website/src/main/java/dev/christopherbell/account/passwordreset/
```

Move or create:

```text
PasswordResetService.java
PasswordResetNotificationService.java
```

`AccountService` should delegate reset operations to `PasswordResetService` instead of owning token generation, hashing, URL construction, expiry checks, and mail handoff.

- [x] **Step 3: Extract follow/profile/moderation collaborators**

Create:

```text
website/src/main/java/dev/christopherbell/account/follow/AccountFollowService.java
website/src/main/java/dev/christopherbell/account/profile/AccountProfileService.java
website/src/main/java/dev/christopherbell/account/moderation/AccountModerationService.java
```

Move only the methods that match each responsibility. Keep `AccountService` as a facade only if controllers/tests still depend on it.

- [x] **Step 4: Update imports and docs**

Run:

```bash
rg "dev\\.christopherbell\\.account" website/src/main/java website/src/test/java
```

Expected: imports point at the new subpackages where classes moved. Update `account/README.md` to explain the new subpackages and ownership.

- [x] **Step 5: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.account.AccountServiceTest --tests dev.christopherbell.account.AccountControllerTest --tests dev.christopherbell.account.PasswordResetNotificationServiceTest
./gradlew :website:test
```

Expected: all pass.

---

### Task 3: Admin Package

**Current package:** `website/src/main/java/dev/christopherbell/admin`

**Target subpackages:**
- `dev.christopherbell.admin.activity` for admin activity recording and reads.
- Keep `dev.christopherbell.admin.model` for admin DTOs.

**Files:**
- Move: `AdminActivityService.java`
- Move: `AdminActivityController.java`
- Move or keep: `AdminActivityRepository.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/admin/`
- Modify docs: `website/src/main/java/dev/christopherbell/admin/README.md`

- [x] **Step 1: Move activity classes**

Create:

```text
website/src/main/java/dev/christopherbell/admin/activity/
```

Move activity classes into that package and update package declarations.

- [x] **Step 2: Update imports**

Run:

```bash
rg "AdminActivity" website/src/main/java website/src/test/java
```

Expected: references import `dev.christopherbell.admin.activity.*` where needed.

- [x] **Step 3: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.admin.AdminActivityServiceTest --tests dev.christopherbell.admin.AdminActivityControllerTest
```

Expected: all pass.

---

### Task 4: Blog Package

**Current package:** `website/src/main/java/dev/christopherbell/blog`

**Target subpackages:**
- No immediate split required unless behavior grows.
- Optional future split: `dev.christopherbell.blog.content` if file-backed/config-backed content grows.

**Files:**
- Review: `BlogController.java`
- Review: `BlogService.java`
- Modify docs only if deciding to keep package flat: `website/src/main/java/dev/christopherbell/blog/README.md`

- [x] **Step 1: Confirm package should stay flat**

Run:

```bash
find website/src/main/java/dev/christopherbell/blog -name '*.java' -print | xargs wc -l
```

Expected: package remains small enough to keep flat.

- [x] **Step 2: Document no-split decision**

Add a short note to `blog/README.md`:

```markdown
This package intentionally stays flat while it only owns blog list/detail reads.
Create a `content` subpackage if authoring, indexing, or multiple content
providers are added.
```

- [x] **Step 3: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.blog.BlogServiceTest --tests dev.christopherbell.blog.BlogControllerTest
```

Expected: all pass.

---

### Task 5: Configuration Package

**Current package:** `website/src/main/java/dev/christopherbell/configuration`

**Target subpackages:**
- `dev.christopherbell.configuration.security` for security config and JWT filter.
- `dev.christopherbell.configuration.filter` for rate limit/request size filters.
- `dev.christopherbell.configuration.mongo` for Mongo auditing/config.

**Files:**
- Move: `SecurityConfig.java`
- Move: `JwtAuthenticationFilter.java`
- Move: `RateLimitFilter.java`
- Move: `RequestSizeLimitFilter.java`
- Move: `MongoAuditingConfig.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/configuration/`
- Modify docs: `website/src/main/java/dev/christopherbell/configuration/README.md`

- [x] **Step 1: Move security files**

Create:

```text
website/src/main/java/dev/christopherbell/configuration/security/
```

Move `SecurityConfig.java` and `JwtAuthenticationFilter.java`. Keep public route constants accessible if tests reference them.

- [x] **Step 2: Move filter files**

Create:

```text
website/src/main/java/dev/christopherbell/configuration/filter/
```

Move `RateLimitFilter.java` and `RequestSizeLimitFilter.java`.

- [x] **Step 3: Move Mongo config**

Create:

```text
website/src/main/java/dev/christopherbell/configuration/mongo/
```

Move `MongoAuditingConfig.java`.

- [x] **Step 4: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.configuration.SecurityConfigTest --tests dev.christopherbell.configuration.JwtAuthenticationFilterTest --tests dev.christopherbell.configuration.RateLimitFilterTest --tests dev.christopherbell.configuration.RequestSizeLimitFilterTest
./gradlew :website:test
```

Expected: all pass.

---

### Task 6: Location Package

**Current package:** `website/src/main/java/dev/christopherbell/location`

**Target subpackages:**
- `dev.christopherbell.location.zip` for ZIP coordinate controller/service/repository/import reader.
- Keep `dev.christopherbell.location.model` unless ZIP DTOs grow enough to justify `zip/model`.

**Files:**
- Move: `LocationController.java`
- Move: `ZipCoordinateService.java`
- Move: `ZipCoordinateRepository.java`
- Move: `ZipCoordinateGazetteerReader.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/location/`
- Modify docs: `website/src/main/java/dev/christopherbell/location/README.md`

- [x] **Step 1: Move ZIP coordinate implementation**

Create:

```text
website/src/main/java/dev/christopherbell/location/zip/
```

Move ZIP coordinate implementation classes into that package and update imports.

- [x] **Step 2: Verify public/security behavior**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.location.LocationControllerTest --tests dev.christopherbell.location.LocationControllerSecurityTest --tests dev.christopherbell.location.ZipCoordinateServiceTest --tests dev.christopherbell.location.ZipCoordinateGazetteerReaderTest --tests dev.christopherbell.configuration.SecurityConfigTest
```

Expected: all pass, especially anonymous ZIP lookup and admin-only import.

---

### Task 7: Message Package

**Current package:** `website/src/main/java/dev/christopherbell/message`

**Target subpackages:**
- `dev.christopherbell.message.conversation` for conversation listing/retrieval/read-state behavior.
- `dev.christopherbell.message.delivery` for send-message behavior and notification handoff.
- Keep `dev.christopherbell.message.model`.

**Files:**
- Modify/extract from: `MessageService.java`
- Move or keep: `MessageRepository.java`
- Modify: `MessageController.java`
- Modify tests: `MessageServiceTest.java`, `MessageControllerTest.java`
- Modify docs: `website/src/main/java/dev/christopherbell/message/README.md`

- [x] **Step 1: Add or preserve coverage**

Ensure these tests exist:

```java
@Test
void sendMessage_rejectsSuspendedSender() throws Exception {
  // Existing coverage should remain after extraction.
}

@Test
void getConversation_marksIncomingMessagesRead() throws Exception {
  // Existing coverage should remain after extraction.
}
```

- [x] **Step 2: Extract delivery service**

Create:

```text
website/src/main/java/dev/christopherbell/message/delivery/MessageDeliveryService.java
```

Move validation, sender status check, recipient lookup, message creation, save, and notification handoff for sending messages.

- [x] **Step 3: Extract conversation service**

Create:

```text
website/src/main/java/dev/christopherbell/message/conversation/ConversationService.java
```

Move conversation reads, summary creation, and read-state updates.

- [x] **Step 4: Keep or remove facade**

If `MessageController` can inject both services cleanly, remove the old `MessageService`. If that creates noisy controller changes, keep `MessageService` as a thin facade that delegates to the two subfeature services.

- [x] **Step 5: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.message.MessageServiceTest --tests dev.christopherbell.message.MessageControllerTest
```

Expected: all pass.

---

### Task 8: Notification Package

**Current package:** `website/src/main/java/dev/christopherbell/notification`

**Target subpackages:**
- `dev.christopherbell.notification.delivery` for notification creation methods.
- `dev.christopherbell.notification.inbox` for unread counts, list reads, mark-read behavior.
- Keep `dev.christopherbell.notification.model`.

**Files:**
- Modify/extract from: `NotificationService.java`
- Modify: `NotificationController.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/notification/`
- Modify docs: `website/src/main/java/dev/christopherbell/notification/README.md`

- [x] **Step 1: Extract creation behavior**

Create:

```text
website/src/main/java/dev/christopherbell/notification/delivery/NotificationDeliveryService.java
```

Move methods that create mention, message, like, comment, and WFL session notifications.

- [x] **Step 2: Extract inbox behavior**

Create:

```text
website/src/main/java/dev/christopherbell/notification/inbox/NotificationInboxService.java
```

Move list, unread count, and mark-read behavior.

- [x] **Step 3: Update callers**

Update post/message/WFL services to depend on `NotificationDeliveryService` for creation. Update `NotificationController` to depend on `NotificationInboxService`.

- [x] **Step 4: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.notification.NotificationServiceTest --tests dev.christopherbell.notification.NotificationControllerTest --tests dev.christopherbell.post.PostServiceTest --tests dev.christopherbell.message.MessageServiceTest --tests dev.christopherbell.whatsforlunch.restaurant.WhatsForLunchSessionServiceTest
```

Expected: all pass.

---

### Task 9: Permission Package

**Current package:** `website/src/main/java/dev/christopherbell/permission`

**Target subpackages:**
- Keep flat for now unless security logic grows.
- Optional future split: `permission.jwt` and `permission.authority`.

**Files:**
- Review: `PermissionService.java`
- Modify docs: `website/src/main/java/dev/christopherbell/permission/README.md`

- [x] **Step 1: Keep package flat and document threshold**

Add a note to the README:

```markdown
This package stays flat while `PermissionService` owns the complete auth helper
surface. Split into `jwt` and `authority` only if token creation/validation or
role checks grow into separate collaborators.
```

- [x] **Step 2: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.permission.PermissionServiceTest
```

Expected: all pass.

---

### Task 10: Photo Package

**Current package:** `website/src/main/java/dev/christopherbell/photo`

**Target subpackages:**
- Keep flat for now.
- Optional future split: `photo.gallery` if upload, albums, or permissions are added.

**Files:**
- Review: `PhotoController.java`
- Review: `PhotoService.java`
- Modify docs: `website/src/main/java/dev/christopherbell/photo/README.md`

- [x] **Step 1: Document no-split decision**

Add a short README note:

```markdown
This package remains flat while it only owns read-only gallery data. Create a
`gallery` subpackage when uploads, albums, moderation, or permissions are added.
```

- [x] **Step 2: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.photo.PhotoServiceTest --tests dev.christopherbell.photo.PhotoControllerTest
```

Expected: all pass.

---

### Task 11: Post Package

**Current package:** `website/src/main/java/dev/christopherbell/post`

**Target subpackages:**
- `dev.christopherbell.post.creation` for create/reply validation and persistence.
- `dev.christopherbell.post.feed` for global/following/user/current-user feeds.
- `dev.christopherbell.post.thread` for thread reads and reply-tree traversal.
- `dev.christopherbell.post.expiration` for lifespan, purge, reply synchronization.
- `dev.christopherbell.post.interaction` for like/delete/comment notification behavior.
- `dev.christopherbell.post.preview` for link preview client/service.
- Keep `dev.christopherbell.post.model`.

**Files:**
- Modify/extract from: `PostService.java`
- Move: `PostLinkPreviewService.java`
- Move: `PostLinkPreviewClient.java`
- Move: `JsoupPostLinkPreviewClient.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/post/`
- Modify docs: `website/src/main/java/dev/christopherbell/post/README.md`

- [x] **Step 1: Preserve behavior coverage before extraction**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.post.PostServiceTest --tests dev.christopherbell.post.PostControllerTest --tests dev.christopherbell.post.PostLinkPreviewServiceTest --tests dev.christopherbell.post.JsoupPostLinkPreviewClientTest
```

Expected: all pass.

- [x] **Step 2: Move preview classes first**

Create:

```text
website/src/main/java/dev/christopherbell/post/preview/
```

Move:

```text
PostLinkPreviewService.java
PostLinkPreviewClient.java
JsoupPostLinkPreviewClient.java
```

Update imports in `PostService` and tests.

- [x] **Step 3: Extract expiration service**

Create:

```text
website/src/main/java/dev/christopherbell/post/expiration/PostExpirationService.java
```

Move expiration constants and methods:

```text
calculateExpiration
expirationForNewPost
refreshExpiration
ensureExpirationSet
isExpired
refreshThreadRootExpiration
refreshThreadRootExpirationForNewReply
rootExpirationFor
setReplyExpirationFromRoot
synchronizeReplyExpirations
purgeExpiredPosts
```

Keep `PostService` delegating until tests are green.

- [x] **Step 4: Extract feed/thread/interaction/creation services**

Create:

```text
website/src/main/java/dev/christopherbell/post/creation/PostCreationService.java
website/src/main/java/dev/christopherbell/post/feed/PostFeedService.java
website/src/main/java/dev/christopherbell/post/thread/PostThreadService.java
website/src/main/java/dev/christopherbell/post/interaction/PostInteractionService.java
```

Move methods by public behavior:

```text
createPost -> PostCreationService
getGlobalFeed/getFollowingFeed/getUserFeed/getMyFeed/getMyPosts/getPostsByAccountId -> PostFeedService
getPostById/getThread -> PostThreadService
toggleLike/deletePost -> PostInteractionService
```

Keep `PostService` as a facade only while controllers/tests migrate.

- [x] **Step 5: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.post.PostServiceTest --tests dev.christopherbell.post.PostControllerTest --tests dev.christopherbell.post.PostExpirationConfigurationTest --tests dev.christopherbell.post.PostLinkPreviewServiceTest --tests dev.christopherbell.post.JsoupPostLinkPreviewClientTest
./gradlew :website:test
```

Expected: all pass.

---

### Task 12: Report Package

**Current package:** `website/src/main/java/dev/christopherbell/report`

**Target subpackages:**
- `dev.christopherbell.report.submission` for user report submission.
- `dev.christopherbell.report.moderation` for report resolution actions.
- Keep `dev.christopherbell.report.model`.

**Files:**
- Modify/extract from: `ReportService.java`
- Modify: `ReportController.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/report/`
- Modify docs: `website/src/main/java/dev/christopherbell/report/README.md`

- [x] **Step 1: Extract submission service**

Create:

```text
website/src/main/java/dev/christopherbell/report/submission/ReportSubmissionService.java
```

Move report creation, post lookup for report metadata, and email handoff.

- [x] **Step 2: Extract moderation service**

Create:

```text
website/src/main/java/dev/christopherbell/report/moderation/ReportModerationService.java
```

Move report resolution, post deletion, and user suspension behavior.

- [x] **Step 3: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.report.ReportServiceTest --tests dev.christopherbell.report.ReportControllerTest
```

Expected: all pass.

---

### Task 13: Vehicle Package

**Current packages:**
- `website/src/main/java/dev/christopherbell/vehicle`
- `website/src/main/java/dev/christopherbell/vehicle/nhtsa`
- `website/src/main/java/dev/christopherbell/vehicle/randomvin`

**Target subpackages:**
- `dev.christopherbell.vehicle.core` for CRUD, mapper, repository, state reads.
- `dev.christopherbell.vehicle.vin` for VIN validation, create-from-VIN, batch VIN creation.
- `dev.christopherbell.vehicle.nhtsa.decode` for NHTSA client/decode service.
- `dev.christopherbell.vehicle.nhtsa.enrichment` for scheduled stored-VIN enrichment and import state.
- `dev.christopherbell.vehicle.randomvin.importing` for RandomVIN import service/client/state.
- `dev.christopherbell.vehicle.randomvin.policy` for robots policy parsing.
- Keep `dev.christopherbell.vehicle.model`.

**Files:**
- Modify/extract from: `VehicleService.java`
- Modify: `VehicleController.java`
- Move/extract from: `NhtsaVinEnrichmentService.java`
- Move/extract from: `RandomVinImportService.java`
- Move: `RandomVinRobotsPolicy.java`
- Modify tests under: `website/src/test/java/dev/christopherbell/vehicle/`
- Modify docs: `website/src/main/java/dev/christopherbell/vehicle/README.md`, `vehicle/nhtsa/README.md`, `vehicle/randomvin/README.md`

- [x] **Step 1: Move core CRUD**

Create:

```text
website/src/main/java/dev/christopherbell/vehicle/core/
```

Move:

```text
VehicleRepository.java
VehicleMapper.java
VehicleDataCollectionStateService.java
```

Extract CRUD methods from `VehicleService` into `VehicleCrudService`.

- [x] **Step 2: Extract VIN service**

Create:

```text
website/src/main/java/dev/christopherbell/vehicle/vin/VehicleVinService.java
```

Move VIN validation, single VIN creation, and batch VIN creation from `VehicleService`.

- [x] **Step 3: Split NHTSA package**

Create:

```text
website/src/main/java/dev/christopherbell/vehicle/nhtsa/decode/
website/src/main/java/dev/christopherbell/vehicle/nhtsa/enrichment/
```

Move NHTSA decode client/service into `decode`. Move enrichment scheduler/state handling into `enrichment`.

- [x] **Step 4: Split RandomVIN package**

Create:

```text
website/src/main/java/dev/christopherbell/vehicle/randomvin/importing/
website/src/main/java/dev/christopherbell/vehicle/randomvin/policy/
```

Move RandomVIN import/client/state to `importing`. Move `RandomVinRobotsPolicy` to `policy`.

- [x] **Step 5: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.vehicle.VehicleServiceTest --tests dev.christopherbell.vehicle.VehicleControllerTest --tests dev.christopherbell.vehicle.VehicleDataCollectionStateServiceTest --tests dev.christopherbell.vehicle.nhtsa.* --tests dev.christopherbell.vehicle.randomvin.*
./gradlew :website:test
```

Expected: all pass.

---

### Task 14: View Package

**Current package:** `website/src/main/java/dev/christopherbell/view`

**Target subpackages:**
- `dev.christopherbell.view.voidroutes` for Void/feed/profile/messages/notifications/post routes.
- `dev.christopherbell.view.tools` for VIN Decoder and ZIP Coordinates page routes.
- `dev.christopherbell.view.wfl` for WFL page routes.
- `dev.christopherbell.view.account` for login/signup/password reset page routes.
- `dev.christopherbell.view.content` for home/blog/photo/The Bell routes.

**Files:**
- Split: `ViewController.java`
- Modify tests: `website/src/test/java/dev/christopherbell/view/ViewControllerTest.java`
- Modify docs: `website/src/main/java/dev/christopherbell/view/README.md`

- [x] **Step 1: Split controller by page family**

Create:

```text
website/src/main/java/dev/christopherbell/view/voidroutes/VoidViewController.java
website/src/main/java/dev/christopherbell/view/tools/ToolsViewController.java
website/src/main/java/dev/christopherbell/view/wfl/WhatsForLunchViewController.java
website/src/main/java/dev/christopherbell/view/account/AccountViewController.java
website/src/main/java/dev/christopherbell/view/content/ContentViewController.java
```

Move route methods without changing mappings or template names.

- [x] **Step 2: Verify all page route tests**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: all pass.

---

### Task 15: What's For Lunch Package

**Current packages:**
- `website/src/main/java/dev/christopherbell/whatsforlunch`
- `website/src/main/java/dev/christopherbell/whatsforlunch/restaurant`
- `website/src/main/java/dev/christopherbell/whatsforlunch/workflow`

**Target subpackages under `whatsforlunch/restaurant`:**
- `suggestion` for nearby picks, ZIP origin, radius, distance math, cuisine filtering.
- `preference` for saved WFL filters.
- `rating` for ratings and top-rated restaurants.
- `favorite` for favorites.
- `importing` for OpenStreetMap import, merge/update, scheduler, import state.
- `dedupe` for duplicate restaurant names.
- `daily` for daily picks and replacement after admin delete.
- `session` for shared sessions, links, votes, participants.
- Keep `model` for now, or move only if a subfeature accumulates many private DTOs.

**Files:**
- Modify/extract from: `RestaurantService.java`
- Modify/split: `RestaurantController.java`
- Move or split: `WhatsForLunchSessionService.java`
- Move: `OpenStreetMapRestaurantClient.java`
- Move relevant repositories next to subfeatures if ownership is clear.
- Modify tests under: `website/src/test/java/dev/christopherbell/whatsforlunch/restaurant/`
- Modify docs: `website/src/main/java/dev/christopherbell/whatsforlunch/README.md`, `restaurant/README.md`

- [x] **Step 1: Preserve WFL baseline**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.whatsforlunch.restaurant.RestaurantServiceTest --tests dev.christopherbell.whatsforlunch.restaurant.RestaurantControllerTest --tests dev.christopherbell.whatsforlunch.restaurant.RestaurantControllerMemberSecurityTest --tests dev.christopherbell.whatsforlunch.restaurant.WhatsForLunchSessionServiceTest --tests dev.christopherbell.whatsforlunch.restaurant.OpenStreetMapRestaurantClientTest
```

Expected: all pass.

- [x] **Step 2: Extract session package**

Create:

```text
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/session/
```

Move `WhatsForLunchSessionService` and `WhatsForLunchSessionRepository` there. Update controller imports.

- [ ] **Step 3: Extract preference/favorite/rating packages**

Create:

```text
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/preference/
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/favorite/
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/rating/
```

Move preference, favorite, and rating repositories and service methods into focused services:

```text
WhatsForLunchPreferenceService
RestaurantFavoriteService
RestaurantRatingService
```

Checkpoint: `preference`, `favorite`, and `rating` repository packages have been created and wired. Focused service extraction remains.

- [ ] **Step 4: Extract suggestion/daily packages**

Create:

```text
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/suggestion/
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/daily/
```

Move nearby-pick logic, cuisine filtering, ZIP origin, radius, coordinate bounds, distance math, daily picks, and admin replacement behavior into focused services.

- [ ] **Step 5: Extract import/dedupe packages**

Create:

```text
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/importing/
website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/dedupe/
```

Move OpenStreetMap client/import/merge/monthly scheduler/import state into `importing`. Move duplicate-name survivor selection and cleanup into `dedupe`.

- [ ] **Step 6: Split controller if needed**

If `RestaurantController` remains over 300 lines, split it into:

```text
RestaurantAdminController
RestaurantSuggestionController
RestaurantPreferenceController
RestaurantRatingController
RestaurantFavoriteController
WhatsForLunchSessionController
```

Keep request mappings unchanged.

- [ ] **Step 7: Verify**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.whatsforlunch.restaurant.*
./gradlew :website:test
```

Expected: all pass.

---

### Task 16: cbell-lib Packages

**Current module:** `cbell-lib/src/main/java/dev/christopherbell/libs`

**Target subpackages:**
- Keep `api`, `api.controller`, `api.exception`, `api.model`, `security`, `test`, `workflow`, `workflow.model`, `workflow.operation`, `workflow.retry`, `workflow.exception`.
- Optional future split: `security.email`, `security.password`, `security.username` only if sanitizers grow beyond utility classes.

**Files:**
- Review: `cbell-lib/src/main/java/dev/christopherbell/libs/security/*`
- Review: `cbell-lib/src/main/java/dev/christopherbell/libs/workflow/*`
- Modify docs if present or root README if no package README exists.

- [ ] **Step 1: Keep cbell-lib stable**

Run:

```bash
find cbell-lib/src/main/java/dev/christopherbell/libs -name '*.java' -print | xargs wc -l | sort -nr | head -40
```

Expected: no class is large enough to justify an immediate package split except possibly `WorkflowExecutor`, which should stay in `workflow` unless retry/execution responsibilities grow.

- [ ] **Step 2: Verify library tests**

Run:

```bash
./gradlew :cbell-lib:test
```

Expected: all pass.

---

### Task 17: Frontend JavaScript Packages

**Current package root:** `website/src/main/resources/static/js`

**Target directories:**
- `static/js/wfl/` for WFL page modules and helpers.
- `static/js/back-office/` for admin dashboard modules.
- `static/js/feed/` for feed render/context/composer/infinite helpers.
- `static/js/nav/` for nav component state and browser notification dropdown behavior.
- Keep `static/js/lib/api.js` and `static/js/lib/util.js` as shared browser utilities.

**Files:**
- Split: `static/js/whats-for-lunch.js`
- Split: `static/js/back-office.js`
- Split: `static/js/lib/feed-render.js`
- Split: `static/js/components/nav.js`
- Modify tests under: `website/src/test/js/`
- Modify docs: `website/src/main/resources/static/js/README.md`

- [ ] **Step 1: Split WFL browser module**

Create:

```text
website/src/main/resources/static/js/wfl/
```

Extract:

```text
location.js
filters.js
session.js
cards.js
ratings.js
favorites.js
```

Keep `whats-for-lunch.js` as the page entry module that wires these helpers.

- [ ] **Step 2: Split Back Office browser module**

Create:

```text
website/src/main/resources/static/js/back-office/
```

Extract:

```text
users.js
reports.js
operations.js
drawer.js
```

Keep `back-office.js` as the page entry module.

- [ ] **Step 3: Split feed/nav shared modules**

Create:

```text
website/src/main/resources/static/js/feed/
website/src/main/resources/static/js/nav/
```

Move feed-specific helpers out of `lib` only if import paths remain clear. Keep generic helpers in `lib`.

- [ ] **Step 4: Verify**

Run:

```bash
node --check website/src/main/resources/static/js/whats-for-lunch.js
node --check website/src/main/resources/static/js/back-office.js
node --check website/src/main/resources/static/js/components/nav.js
node --test website/src/test/js/*.test.js
```

Expected: all pass.

---

### Task 18: CSS Ownership Split

**Current package root:** `website/src/main/resources/static/css`

**Target files:**
- `main.css` remains the entry stylesheet imported by templates.
- Create feature CSS files only if the template pipeline supports loading them directly or if `main.css` can import them safely without a build step.
- Candidate files:
  - `void.css`
  - `wfl.css`
  - `back-office.css`
  - `feed.css`
  - `messages.css`
  - `forms.css`

**Files:**
- Modify: `website/src/main/resources/static/css/main.css`
- Create optional files under: `website/src/main/resources/static/css/`
- Modify docs: `website/src/main/resources/static/css/README.md`

- [ ] **Step 1: Confirm stylesheet loading strategy**

Search templates:

```bash
rg "main\\.css|static/css|/css/" website/src/main/resources/templates
```

Expected: templates load `main.css`. If using separate CSS files, add explicit links in relevant templates or use CSS `@import` at the top of `main.css`.

- [ ] **Step 2: Split high-volume CSS sections**

Move blocks by ownership without changing class names:

```text
Void/feed classes -> void.css or feed.css
WFL classes -> wfl.css
Back Office classes -> back-office.css
Messages classes -> messages.css
Auth/form classes -> forms.css
```

- [ ] **Step 3: Verify visually relevant templates still reference styles**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Then manually spot check in browser if this task is executed locally:

```text
http://localhost:8081/
http://localhost:8081/void
http://localhost:8081/messages
http://localhost:8081/wfl
http://localhost:8081/back-office
```

Expected: no missing styles.

---

### Task 19: Final Repository Verification

**Files:**
- Verify all changed packages, docs, and tests.

- [ ] **Step 1: Run full Java test suite**

Run:

```bash
./gradlew :website:test
./gradlew :cbell-lib:test
```

Expected: all pass.

- [ ] **Step 2: Run JS tests and syntax checks**

Run:

```bash
node --test website/src/test/js/*.test.js
find website/src/main/resources/static/js -name '*.js' -print0 | xargs -0 -n1 node --check
```

Expected: all pass.

- [ ] **Step 3: Check diff quality**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Dirty files should be only the files intentionally changed by the executed tasks plus any unrelated pre-existing worktree changes noted before implementation.

- [ ] **Step 4: Update root docs**

If package layout changed, update:

```text
README.md
AGENTS.md
website/src/main/java/dev/christopherbell/README.md
```

Expected: a future agent can find the new subpackages from the docs without reading this plan first.
