# Copilot Instructions

Use the repository-level `AGENTS.md` as the source of truth for AI assistant
workflows in this project.

Key reminders:

- This is a Java 25 Spring Boot 4.1 app with vanilla JavaScript static assets.
- There is no npm workflow and no `package.json`.
- Prefer vanilla JavaScript. Do not introduce frontend frameworks, npm packages,
  bundlers, or build steps unless explicitly requested.
- Run Gradle commands from the repository root.
- Keep backend changes inside the owning feature package.
- Prefer small, single-purpose methods that follow KISS.
- Document code at the appropriate level and update relevant documentation with
  behavior changes.
- Add unit tests for changed behavior, covering success, failure, edge,
  validation, and permission cases where applicable.
- Update the relevant feature README when behavior changes.
- Add frontend API paths to `website/src/main/resources/static/js/lib/api.js`.
- Keep public route rules in sync with `SecurityConfig.PUBLIC_URLS`.
- Do not revert unrelated dirty worktree changes.

Useful checks:

```bash
./gradlew :website:test
./gradlew :website:jsTest
node --check website/src/main/resources/static/js/<file>.js
```
