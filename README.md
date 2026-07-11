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
- MongoDB
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
export RESEND_API_KEY=re_your_resend_key
export APP_MAIL_FROM=noreply@your-verified-domain.com
export APP_JWT_SECRET=replace-with-at-least-32-random-characters
```

For local secrets, copy `.env.example` to `.env` and fill in values. Do not
commit `.env`.

## Run Locally

Start MongoDB first. Then run commands from the repository root:

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

Backend code is organized by feature, not by technical layer. For example,
`post`, `account`, `vehicle`, and `whatsforlunch` each own their controller,
service, repository, mapper, models, and package docs.

Common pattern inside a feature:

- `*Controller` defines HTTP endpoints.
- `*Service` owns business rules.
- `*Repository` owns MongoDB access.
- `*Mapper` converts between persistence models and API DTOs.
- `model/` contains entities, request DTOs, response DTOs, and enums.
- `README.md` explains the feature's technical behavior.

Cross-cutting web infrastructure lives in `configuration`. Server-rendered page
routes live in `view`.

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
instances. API protection is configured in `SecurityConfig` and method-level
`@PreAuthorize` annotations.

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
is admin-only and can be triggered from Back Office. The default import coverage
includes Austin, the San Francisco Bay Area, New Orleans, and Dallas.

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

Admin endpoints:

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-token>" \
  http://localhost:8081/api/whatsforlunch/restaurant/2026-05-17/import/openstreetmap

curl -X POST \
  -H "Authorization: Bearer <admin-token>" \
  http://localhost:8081/api/whatsforlunch/restaurant/2026-05-17/dedupe-names
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

Build:

```bash
./gradlew :website:build
```

Set production configuration with environment variables:

```bash
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATA_MONGODB_URI=mongodb://<host>:<port>/<db>
export SERVER_PORT=8080
export RESEND_API_KEY=re_your_resend_key
export APP_MAIL_FROM=noreply@your-verified-domain.com
```

Run:

```bash
java -jar website/build/libs/<jar-name>.jar
```

### MongoDB Backups and Restores

Use the [MongoDB backup and restore runbook](docs/operations/mongodb-backup-restore.md)
for production backup commands, expected archive storage, restore steps, and
restore smoke checks.

## Troubleshooting

Gradle wrapper has Windows line endings in WSL:

```bash
sed -i 's/\r$//' gradlew
chmod +x gradlew
```

Cannot connect to MongoDB:

- Confirm MongoDB is running.
- Confirm the URI matches the active profile.
- Override with `SPRING_DATA_MONGODB_URI` if needed.

Static JS changes are not visible:

- Hard-refresh the browser.
- Restart `:website:bootRun` if template or server config changed.
