# christopherbell.dev

Personal website and application for Christopher Bell. The app is a Spring Boot
monolith with server-rendered Thymeleaf pages and browser-native JavaScript
served from Spring static resources.

This README is the root-level technical guide. A beginner should be able to use
it to run the app, understand the package layout, and know where to make common
changes.

## Tech Stack

- Java 25
- Spring Boot 4.1
- Gradle Wrapper
- MongoDB 8.3.2, or Docker with Compose
- Thymeleaf templates
- Vanilla JavaScript ES modules
- Bootstrap styles and local CSS

There is no npm build. There is no `package.json`. Do not run `npm install` for
this app.

## Project Layout

```text
.
|-- build.gradle.kts              # Root Gradle build configuration
|-- settings.gradle.kts           # Includes website and cbell-lib modules
|-- cbell-lib/                    # Shared Java library code
`-- website/                      # Spring Boot application
    |-- src/main/java/dev/christopherbell/
    |   |-- account/              # Signup, login, profiles, password reset
    |   |-- admin/                # Back Office activity and admin views
    |   |-- blog/                 # Config-backed blog content
    |   |-- canesboxtracker/      # Raising Canes Box Index weekly price tracker
    |   |-- configuration/        # Security, filters, app configuration
    |   |-- location/             # Imported ZIP coordinate reference data
    |   |-- message/              # Direct messages
    |   |-- notification/         # Notifications and mention alerts
    |   |-- photo/                # Photo gallery data
    |   |-- post/                 # Void posts, replies, likes, feeds
    |   |-- report/               # Post reporting and moderation
    |   |-- vehicle/              # VIN decoder and vehicle storage
    |   |-- view/                 # Page route controller
    |   `-- whatsforlunch/        # WFL restaurant import and suggestions
    `-- src/main/resources/
        |-- application.yml       # Default configuration
        |-- application-local.yml # Local profile overrides
        |-- static/               # CSS, JS, images
        `-- templates/            # Thymeleaf HTML templates
```

Most feature packages have their own `README.md`. Read the package README before
changing that feature, and update it when behavior or API contracts change.

AI coding agents should also read `AGENTS.md`. GitHub Copilot-style agents can
use `.github/copilot-instructions.md`, which points back to the same workflow.

## Modules

- `website` - the runnable Spring Boot web application.
- `cbell-lib` - reusable Java library code shared by the app.

The root Gradle build sets Java 25 for subprojects and enables JUnit Platform for
tests.

## Requirements

- Java 25 JDK
- MongoDB
- A shell that can run the Gradle wrapper

Optional for email/password reset work:

- Resend API key
- Verified sender domain

## Configuration

Spring loads configuration from:

- `website/src/main/resources/application.yml`
- `website/src/main/resources/application-local.yml`
- optional `.env` files from the repo root or `website/`
- environment variables

The default Spring profile is `local`.

Important local defaults:

- App port: `8081`
- Mongo database: `christopherbell`
- Mongo URI: `mongodb://localhost:27017`

Useful environment variables:

```bash
export SPRING_PROFILES_ACTIVE=local
export SPRING_MONGODB_URI=mongodb://localhost:27017
export APP_MAIL_ENABLED=false
export APP_JWT_SECRET=replace-with-at-least-32-random-characters
```

For local secrets, copy `.env.example` to `.env` and fill in values. Do not
commit `.env`.

## Run Locally

### Local MongoDB with Docker Compose

From the repository root, start the pinned MongoDB 8.3.2 service and wait for
Compose to report it healthy:

```bash
docker compose up -d mongodb
docker compose ps mongodb
```

The service listens only on `mongodb://localhost:27017` and stores data in the
`christopherbell_mongo_data` named volume. `docker compose stop mongodb` keeps
that data. To reset only this Compose project's local data, first confirm the
project and volume names, then run `docker compose down --volumes`.

Database shape changes must be appended through the versioned migration runner;
never edit an applied migration ID or checksum. See the
[MongoDB migration runbook](docs/operations/mongodb-migrations.md).

With MongoDB running, start the app:

```bash
./gradlew :website:bootRun
```

Open:

```text
http://localhost:8081
```

For Windows PowerShell:

```powershell
.\gradlew.bat :website:bootRun
```

## Build

Build the application and run tests:

```bash
./gradlew :website:build
```

The runnable JAR is written under:

```text
website/build/libs/
```

Run a built JAR:

```bash
java -jar website/build/libs/<jar-name>.jar
```

## Test

Run the full test suite:

```bash
./gradlew test
```

Run only the website tests:

```bash
./gradlew :website:test
```

Run one test class:

```bash
./gradlew :website:test --tests dev.christopherbell.whatsforlunch.restaurant.RestaurantServiceTest
```

For quick JavaScript syntax checks, use Node directly. This is only a parser
check; it is not an npm workflow.

```bash
node --check website/src/main/resources/static/js/back-office.js
```

Run the browser-side JavaScript test suite through Gradle:

```bash
./gradlew :website:jsTest
```

This uses Node's built-in test runner against `website/src/test/js/*.test.js`.
Set `NODE_EXE=/path/to/node` when Node is not on `PATH`.

## Backend Architecture

`website` is one Spring Boot deployable organized as a modular monolith. Business
capabilities own their controllers, application/domain behavior, repositories,
persistence documents, and package documentation. `cbell-lib` contains only
domain-neutral behavior with multiple demonstrated consumers.

Capabilities migrate to closed Spring Modulith modules incrementally. A migrated
module exposes cross-module commands, queries, identifiers, result DTOs, semantic
failures, and non-critical events only through a named `api` package. Repositories,
Mongo documents, implementation services, mappers, and internal DTOs are never
module APIs. `permission` is account-owned. `view`, `admin`, and Spring bootstrap
may orchestrate module APIs; business modules may not depend on those layers.

Run the architecture gates with:

~~~shell
./gradlew :website:test --tests 'dev.christopherbell.architecture.*'
~~~

The checked-in ArchUnit store freezes legacy cross-area accesses. Normal test and
CI runs cannot create or update it. After a reviewed boundary repair, reduce the
store explicitly and inspect the diff before committing:

~~~shell
./gradlew :website:test \
  --tests dev.christopherbell.architecture.ModularMonolithArchitectureTest \
  '-Darchunit.freeze.store.default.allowStoreUpdate=true'
git diff -- website/src/test/resources/architecture-baseline
~~~

Accept only removed violations. Module diagrams and canvases are generated under
`website/build/spring-modulith-docs/` and are review artifacts, not tracked files.

## Frontend Architecture

Frontend files are plain browser assets. They are served directly by Spring from:

```text
website/src/main/resources/static
```

Important folders:

- `static/js/app.js` wires shared page behavior.
- `static/js/components/` contains reusable web components.
- `static/js/lib/` contains shared API paths, fetch helpers, feed rendering, and
  utilities.
- `static/js/*.js` page modules add behavior for individual pages.
- `static/css/main.css` contains application styling.

Templates live in:

```text
website/src/main/resources/templates
```

If you add a new page:

1. Add a Thymeleaf template in `templates/`.
2. Add a route in `ViewController` if needed.
3. Add a page script in `static/js/` if the page needs browser behavior.
4. Add shared API routes to `static/js/lib/api.js`.
5. Keep public/protected access rules aligned in `SecurityConfig`.

## Security

Authentication uses JWTs created by `PermissionService`. JWT signing uses the
stable `APP_JWT_SECRET` value so tokens remain valid across app restarts and
instances. Browsers receive the JWT only in an HttpOnly, SameSite cookie and
send the readable Spring CSRF cookie back as a header for mutations; explicit
bearer tokens remain available to non-browser API clients. API protection and
browser security headers are configured in `SecurityConfig`, with method-level
`@PreAuthorize` annotations enforcing feature permissions.

Browser login sends `X-CBELL-Browser-Session: cookie` to select the cookie-only
response. Existing API clients that omit the header continue to receive the JWT
payload for bearer authentication.

Public routes are listed in `SecurityConfig.PUBLIC_URLS`. Admin-only behavior
usually uses:

```java
@PreAuthorize("@permissionService.hasAuthority('ADMIN')")
```

When adding a public API endpoint, update both:

- `SecurityConfig.PUBLIC_URLS`
- the frontend API path in `static/js/lib/api.js`

## Main Features

- Accounts: signup, login, public profiles, follows, password reset.
- Void posts: posts, replies, likes, global feed, following feed, user feeds.
- Messages: user-to-user conversations.
- Notifications: unread counts, read state, mention notifications.
- Reports: users can report posts; admins resolve reports in Back Office.
- Back Office: admin queues and operations.
- Vehicles: VIN decoding, stored vehicles, NHTSA enrichment.
- Raising Canes Box Index: verified weekly Box Combo prices across configured metros.
- What's For Lunch: multi-metro restaurant import and nearby lunch suggestions.
- Photos and blog: content configured through application properties.

## Raising Canes Box Index

Raising Canes Box Index is a public Tool at `/canes-box-tracker`. A scheduled job
samples the configured store closest to each selected metro center once a week,
stores the per-metro results in MongoDB, and exposes chart history through
`GET /api/canes-box-tracker/2026-06-04/history`. The page shows a large percent
index comparing the latest verified weekly average with the previous verified
priced week.

Metro targets and the weekly schedule live under `canes-box-tracker` in
`application.yml`. The collector uses the official ordering API and admin
manual verification as reliable index sources. Public menu fallback URLs are
disabled by default because third-party menu pages can be stale; when enabled for
diagnostics, those matches stay provisional until an admin reviews them in Back
Office. Failed/rejected samples stay visible as excluded datapoints instead of
being averaged or fabricated. Public fallback prices below
`minimum-public-menu-price` are rejected as stale third-party menu data before
they appear on the tracker.

## What's For Lunch Data

WFL restaurant data is imported from OpenStreetMap through Overpass. The import
is leased across scheduled and admin triggers. Back Office requires a dry-run
preview and confirmation before applying a re-fetched, checksum-matched payload.
The default startup-validated coverage includes Austin, the San Francisco Bay
Area, New Orleans, and Dallas. Public WFL pages show source freshness and city
coverage without exposing operator or failure details.

The public WFL page asks for the browser location or a ZIP code and returns up
to three restaurant suggestions within the selected radius. Browser-location
searches use the provided coordinates; ZIP searches resolve the radius origin
from imported Location Census ZIP Code Tabulation Area coordinates. Both paths
still depend on saved restaurant coordinates, so run the import when local data
is empty or missing coordinates.

## Location ZIP Data

Location owns the reusable Census ZCTA coordinate reference data. Public lookups
use `GET /api/location/zip/{zipCode}` after an admin imports the bundled Census
dataset. The payload includes source and source year because the coordinates are
Census internal points, not USPS delivery geometry.

Import or refresh ZIP coordinates from Back Office or with:

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-token>" \
  http://localhost:8081/api/location/zip/import/census
```

Admin preview endpoints (Back Office performs the corresponding confirmed apply calls):

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-token>" \
  http://localhost:8081/api/whatsforlunch/restaurant/2026-07-26/import/openstreetmap/preview

curl \
  -H "Authorization: Bearer <admin-token>" \
  http://localhost:8081/api/whatsforlunch/restaurant/2026-07-26/dedupe-names/preview
```

## Common Development Tasks

Add or change an API endpoint:

1. Find the feature package under `website/src/main/java/dev/christopherbell`.
2. Update the controller and service.
3. Add or update request/response models under `model/`.
4. Update tests for the controller and service.
5. Add the frontend route in `static/js/lib/api.js` if browser code calls it.
6. Update the feature package README.

Add a field to a Mongo-backed model:

1. Update the entity in the feature `model/` package.
2. Update request/detail DTOs if the field is part of the API contract.
3. Update MapStruct mapper tests or service/controller tests.
4. Consider whether old documents need migration or default handling.

Change page behavior:

1. Find the template in `website/src/main/resources/templates`.
2. Find the page script in `website/src/main/resources/static/js`.
3. Keep reusable behavior in `static/js/lib` only if multiple pages need it.
4. Check browser syntax with `node --check`.

## Production

Production runs natively on Windows through the `MongoDB`,
`ChristopherBellDev`, and `cloudflared` Windows services. All three use
Automatic startup. WSL contains no website, database, proxy, tunnel, or
deployment dependency.

Production requires an explicit `SPRING_MONGODB_URI`; there is no localhost
fallback. Pre-refresh validation also requires a strong `APP_JWT_SECRET` and an
explicit mail policy. Use `APP_MAIL_ENABLED=true` with `RESEND_API_KEY` and
`APP_MAIL_FROM`, or `APP_MAIL_ENABLED=false` to intentionally disable delivery.
Startup applies immutable, leased MongoDB migrations before readiness. The
deployment candidate runs first against a verified backup restored into a
disposable database; it never starts against the live database. Back up before
migration releases; application rollback does not reverse data. See the
[MongoDB migration runbook](docs/operations/mongodb-migrations.md).

From an elevated PowerShell prompt, bootstrap and configure the runtime, then
install automatic deployment:

```powershell
.\prod.cmd install -CloudflareTokenPath C:\Secure\cloudflared-token.txt
# Edit C:\ProgramData\christopherbell.dev\config\deploy.json and app.env.
.\prod.cmd install -CloudflareTokenPath C:\Secure\cloudflared-token.txt
.\prod.cmd deploy
.\prod.cmd auto-install
.\prod.cmd verify-startup
```

The first `install` creates protected configuration examples and intentionally
stops until real paths, the smoke-account email, and secrets replace every
placeholder. On a fresh host, `CloudflareTokenPath` points to a temporary,
protected file containing only the rotated tunnel token; delete that file as
soon as installation succeeds. Existing cloudflared services do not require the
token again. `deploy` fetches the exact latest `origin/main` commit into a clean
detached Windows worktree, builds and tests it, backs up the live database,
restores that backup into a bounded disposable candidate database, and validates
the release on port 8081 against only that clone. Cutover stops the old writer
before the new release can run migrations against live data. Once the new
release is started, failures are forward-only: the website remains stopped and
unready for an operator to repair forward; deployment never restarts the prior
binary or automatically restores data. Candidate and production checks cover the
home, blog, WFL, Canes tracker, crawler metadata, ActivityPub NodeInfo, favicon,
liveness, and readiness routes. Protected `deploy.json` configuration lists both
`https://christopherbell.dev/` and `https://www.christopherbell.dev/` in
`publicUrls`; `publicUrl` remains the canonical `www` root. Existing protected
configurations that predate `publicUrls` derive the apex root from that canonical
`www` root in memory, so the first upgraded deployment does not require rewriting
the secret-bearing configuration file.

`auto-install` creates a hidden, noninteractive SYSTEM Scheduled Task that runs
at boot and once per minute. Each invocation checks the remote SHA once and
exits, so there is no persistent terminal process to interrupt or confirm.
Unchanged checks do not fetch, build, or restart the site. A changed SHA enters
the same locked deployment and forward-only cutover pipeline; no inbound webhook, GitHub
runner, routine administrator approval, or manual deployment command is
required.

Common operations:

```powershell
.\prod.cmd status
.\prod.cmd logs
.\prod.cmd releases
.\prod.cmd rollback
.\prod.cmd backup
.\prod.cmd auto-status
.\prod.cmd verify-startup
```

`rollback` changes application release junctions only. Do not use it after an
incompatible migration has crossed the live migration boundary; repair forward
or perform an explicitly approved restore from the retained verified backup.

The `prod` Spring profile also enables Mission Control host integrations. It
sets `command-center.actions.mode=WINDOWS`, reads only the fixed WinSW service
log under `C:\ProgramData\christopherbell.dev\logs`, and invokes only the fixed
WinSW and Windows shutdown executables configured in `application-prod.yml`.
Local/default operation remains simulated. Before changing these paths, confirm
they still match the installed service layout; browser requests cannot supply or
override executable, service, or log paths.

Public crawler and availability contracts:

- `/robots.txt` and `/sitemap.xml` are public, deterministic, and revalidated.
- `/actuator/health/liveness` and `/actuator/health/readiness` are public and
  expose status without component details. The readiness group includes MongoDB.
- `/actuator/health` and component-specific health routes remain protected.
- Thymeleaf emits release-SHA-prefixed CSS, JavaScript, image, and favicon URLs;
  those responses use one-year immutable public caching, while direct
  unversioned paths use a bounded one-hour cache. Relative ES-module imports
  stay within the same versioned namespace.

### MongoDB Backups and Restores

Use the [Windows production runbook](docs/operations/windows-production.md) and
[MongoDB backup and restore runbook](docs/operations/mongodb-backup-restore.md).
Migration authoring and interrupted-start recovery are documented in the
[MongoDB migration runbook](docs/operations/mongodb-migrations.md).

## Troubleshooting

Run `.\prod.cmd status` first for production failures and `.\prod.cmd logs` for
application and WinSW wrapper output. Confirm all three Windows services are running
and `SPRING_MONGODB_URI` targets `mongodb://127.0.0.1:27017`.

Static JS changes are not visible:

- Hard-refresh the browser.
- Restart `:website:bootRun` if template or server config changed.

## Repository Automation

Pull requests and main run the Java 25 build on Ubuntu, macOS, and Windows.
CI caches Gradle state and retains Java, JavaScript, and Gradle diagnostic
reports for 14 days when a matrix job fails. Browser tests write JUnit XML
under `website/build/test-results/jsTest/`.

CodeQL analyzes Java changes and Dependency Review rejects newly introduced
high-or-critical vulnerable dependencies. Dependabot groups weekly Gradle and
GitHub Actions updates. Assigned, milestone, pinned, roadmap, security, and
codex active work is exempt from the documented stale windows. Because the
stale action cannot read GitHub pin metadata, apply the `pinned` label to every
pinned issue; the `roadmap` label protects roadmap work that does not use a
milestone.
