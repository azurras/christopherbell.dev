# Agent Guide

This file is for AI coding agents working in this repository. Follow it before
making changes.

## First Read

1. Read `README.md` for the full project overview.
2. Read the feature package README for the area you are changing.
3. Check `git status --short` before editing. The worktree may already contain
   user changes. Do not revert unrelated files.

Useful feature docs:

- `website/src/main/java/dev/christopherbell/account/README.md`
- `website/src/main/java/dev/christopherbell/admin/README.md`
- `website/src/main/java/dev/christopherbell/configuration/README.md`
- `website/src/main/java/dev/christopherbell/location/README.md`
- `website/src/main/java/dev/christopherbell/post/README.md`
- `website/src/main/java/dev/christopherbell/report/README.md`
- `website/src/main/java/dev/christopherbell/vehicle/README.md`
- `website/src/main/java/dev/christopherbell/whatsforlunch/README.md`
- `website/src/main/resources/static/js/README.md`
- `website/src/main/resources/static/css/README.md`

## Project Facts

- Java 21, Spring Boot 3.4, Gradle Wrapper.
- MongoDB persistence.
- Thymeleaf server-rendered templates.
- Vanilla JavaScript ES modules served directly from
  `website/src/main/resources/static`.
- No npm workflow. There is no `package.json`; do not run `npm install`.
- Default local app URL is `http://localhost:8081`.

## Core Commands

Run from the repository root.

```bash
./gradlew :website:bootRun
./gradlew :website:test
./gradlew :website:build
```

Target one Java test class:

```bash
./gradlew :website:test --tests dev.christopherbell.whatsforlunch.restaurant.RestaurantServiceTest
```

Check JavaScript syntax:

```bash
node --check website/src/main/resources/static/js/back-office.js
```

## Architecture Rules

- Keep backend code organized by feature package, not by technical layer.
- Respect the current small subfeature package layout. Before adding or moving
  code, look for the narrowest existing package that owns that behavior, such
  as `account.passwordreset`, `post.preview`, `vehicle.vin`, or
  `whatsforlunch.restaurant.session`.
- Do not add new behavior to an old broad package root when a focused
  subfeature package already exists. Package roots should stay as facades,
  thin controllers, shared models, or stable entry points only.
- Create a new subfeature package when a change introduces a distinct
  responsibility that would otherwise make an existing service/controller grow
  into a god class. Keep package names domain-specific and update the owning
  package README in the same change.
- Controllers should stay thin. Put business rules in services.
- Prefer small, single-purpose methods that follow KISS. Extract helper methods
  when they make the main flow easier to read, but do not create abstractions
  that only rename one obvious line.
- Persistence belongs behind repositories.
- API request/response shapes belong in the feature `model` package.
- Shared library code belongs in `cbell-lib` only when more than one feature
  needs it and the abstraction is stable.
- Server-rendered page routes live in `dev.christopherbell.view`.
- Cross-cutting filters/security/config live in `dev.christopherbell.configuration`.

## Frontend Rules

- Prefer vanilla JavaScript for frontend changes. Do not introduce frontend
  frameworks, npm packages, transpilers, bundlers, or build steps unless the user
  explicitly asks and the tradeoff is documented.
- Page templates live in `website/src/main/resources/templates`.
- Page scripts live in `website/src/main/resources/static/js`.
- Shared browser helpers live in `static/js/lib`.
- API paths belong in `static/js/lib/api.js`.
- Use `fetchJson` for API calls unless a low-level `fetch` is specifically
  needed.
- Use the shared `sanitize` helper before injecting untrusted content into HTML
  strings.
- When changing frontend code, run `node --check` on touched JS files.

## Security Rules

- Public routes must be listed in `SecurityConfig.PUBLIC_URLS`.
- Protected controller methods should use `@PreAuthorize`.
- Admin-only methods usually use:

```java
@PreAuthorize("@permissionService.hasAuthority('ADMIN')")
```

- If browser code calls a new endpoint, add the path to `static/js/lib/api.js`.
- If the endpoint is public, update `SecurityConfig` and test unauthenticated
  access.
- Never commit real API keys, passwords, JWTs, or `.env`.

## Documentation Rules

Update documentation in the same change when behavior changes.

- All code should be documented at the appropriate level. Public classes,
  public methods, API models, and non-obvious helpers should explain why they
  exist. Keep comments useful and avoid restating obvious code.
- Root onboarding or run/build/test changes: update `README.md`.
- Feature behavior/API/model changes: update that feature package README.
- Frontend shared behavior changes: update `website/src/main/resources/static/js/README.md`
  or a more specific README under `static/js`.
- CSS/layout ownership changes: update `website/src/main/resources/static/css/README.md`.

## Testing Expectations

Pick the smallest useful verification first, then widen when the blast radius is
larger.

- All code changes require unit tests for the affected behavior. Cover success,
  failure, edge cases, validation, and permission paths where they apply.
- Service logic: targeted service tests.
- Controller/API behavior: controller tests.
- Security/public routes: include unauthenticated access coverage where useful.
- JavaScript-only changes: `node --check` on touched files.
- Shared config, security, or model changes: run `./gradlew :website:test`.

Mention any tests that could not be run.

## Worktree Safety

- Do not run destructive git commands unless explicitly asked.
- Do not revert files you did not change.
- Ignore unrelated dirty files.
- If a file you need already has unrelated user edits, preserve them and make
  the smallest compatible change.
- Prefer focused diffs over broad formatting churn.

## Common Change Playbooks

Add an API endpoint:

1. Add controller method in the feature package.
2. Add service method and tests.
3. Add request/response models if needed.
4. Update `SecurityConfig` for public/protected access.
5. Add frontend route to `static/js/lib/api.js` if browser code calls it.
6. Update feature README.

Add a page:

1. Add a Thymeleaf template under `templates`.
2. Add a route in `ViewController`.
3. Add a page script under `static/js` if needed.
4. Wire the template to `/js/app.js` and the page script.
5. Update nav/components only if the page should be discoverable.
6. Update docs.

Change a Mongo model:

1. Update the entity.
2. Update DTOs and mappers when the API contract changes.
3. Handle old documents with missing fields.
4. Add or update tests.
5. Update package README.

## Final Response Checklist

When done, summarize:

- What changed.
- Files that matter most.
- Commands/tests run.
- Any remaining risk or manual follow-up.
