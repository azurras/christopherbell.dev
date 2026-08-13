# Task 1 Report: PostgreSQL persistence foundation

## Status

DONE_WITH_CONCERNS

## Before-Edit Brief

- **Behavior:** Add a PostgreSQL runtime foundation: Flyway, jOOQ, JDBC, PostgreSQL Compose, and exact `mongodb`/`postgresql` persistence selection without changing domain services or creating relational tables.
- **Invariants:** A single backend is selected from the closed transition set; local and test JDBC URLs target database `test`; production backend and JDBC credentials are required; database-backed tests reject any database other than `test` and any non-`cbtest_` schema before fixtures are written.
- **Boundary/API:** `app.persistence.backend` is the sole application backend selector. The `configuration.persistence` package owns configuration parsing, conditional adapter selection, and database identity validation; domain code does not read that property.
- **Effects and failures:** At context initialization, the PostgreSQL test guard reads only `current_database()` and `current_schema()` and rejects an unsafe identity before readiness or fixture mutation. Diagnostics identify invalid keys/identity categories without echoing JDBC credentials or driver messages.
- **Tests and evidence:** RED begins with parsed profile/Compose behavior, conditional Spring bean selection, production validation, and pure database-identity rejection. GREEN repeats those tests, then compiles and runs the relevant regression suite. A real PostgreSQL isolation test will run only after `current_database()` is independently proven to be exactly `test`.

## Files Changed

- `website/build.gradle.kts` and `gradle/verification-metadata.xml`: Flyway, jOOQ, PostgreSQL runtime dependencies and their verified artifacts; no jOOQ generation plugin or generated-table dependency.
- `compose.yaml`: pinned `postgres:18.4` service with database `test`, loopback-only port, named volume, health check, and environment-supplied password.
- `application-local.yml`, `application-test.yml`, `application-prod.yml`: PostgreSQL profile contract, Mongo auto-configuration exclusion in local/test, and no production backend/JDBC credential defaults.
- `configuration/persistence/**`: typed closed backend binding, Mongo/PostgreSQL adapter annotations, disposable-schema identity guard, and opt-in schema-isolation support.
- 58 existing Mongo repositories/query stores plus 21 Mongo infrastructure components: `@MongoPersistence` conditional selection markers.
- Production validation, configuration README, profile/Compose/selection/guard/architecture tests; the old Mongo profile configuration test was replaced.

## RED Evidence

- `GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:test --tests ... --console=plain`
  - Expected RED: `compileTestJava` failed because `MongoPersistence`, `PostgresPersistence`, and PostgreSQL guard types did not exist.
  - Expected RED: the unsupported backend binding test failed before typed `PersistenceBackendProperties` was added.
  - Expected RED: the guard-policy test failed before `test` and `cbtest_` were made non-configurable safety invariants.
  - Expected RED: the PostgreSQL profile exclusion assertion failed before local/test excluded Mongo auto-configuration.

## GREEN Evidence

- `GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:compileJava :website:test --tests 'dev.christopherbell.configuration.PersistenceProfileConfigurationTest' --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendSelectionTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuardTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestSchemaNameTest' --tests 'dev.christopherbell.configuration.LocalMongoComposeConfigurationTest' --tests 'dev.christopherbell.configuration.ProductionSettingsApplicationContextInitializerTest' --tests 'dev.christopherbell.architecture.MongoPersistenceBoundaryRulesTest' --tests 'dev.christopherbell.architecture.MongoPersistenceAdapterSelectionTest' --console=plain`
  - PASS: compile plus 25 focused configuration/architecture tests.
- `:website:test --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestSchemaIsolationIntegrationTest' --console=plain`
  - PASS/SKIPPED: explicit opt-in guard prevented any connection because `POSTGRESQL_INTEGRATION_TESTS` was not enabled.
- `git diff --check`
  - PASS.

## Database Identity Evidence

- No database-backed test was run. A native `postgres` process (PostgreSQL 16) was found listening on port 5432, but no database identity or credentials were provided and it is not the pinned Compose PostgreSQL 18.4 service.
- The integration test validates `current_database()` is exactly `test` before schema creation, then validates the active `cbtest_*` schema before fixture writes; it must be run only with a separately verified disposable test connection.
- `docker compose config` could not run because `docker` is not installed/available in this worktree environment. The parsed Compose configuration test passed.

## Self-Review Findings

- Reviewed the final diff for backend-default, credential-redaction, and database-target escape hatches. Production values have empty defaults; test guard policies reject every target except `test` and `cbtest_`; generated schema names are unique.
- No jOOQ code-generation plugin, `compileJava` codegen dependency, relational table, H2 dependency, service change, or production connection was introduced.
- No actionable code-review finding remains in the non-database scope.

## Commit Hashes

`f9d6794126d5cc792200de5cf89392d2a5d10b88` — `feat: establish PostgreSQL persistence foundation`

## Concerns

- External database proof remains unavailable: do not enable `POSTGRESQL_INTEGRATION_TESTS` until a PostgreSQL 18.4 service is reachable with credentials that have already proven `current_database() = test`.
- Docker is unavailable, so Compose syntax was validated through the repository's parsed-Compose test rather than the requested CLI command.

## Fix Round 1 — Before-Edit Brief

- **Behavior:** The sole `app.persistence.backend` selector must also choose the Spring Boot persistence auto-configurations, not only annotated adapters. PostgreSQL schema isolation is namespace/search-path isolation, not a role/privilege boundary. Database identity failures must retain a safe diagnostic cause without exposing credentials.
- **Invariants:** MongoDB starts no JDBC, jOOQ, or Flyway auto-configuration; PostgreSQL starts no Mongo auto-configuration; missing and unsupported production selectors fail closed; fixture values stay isolated through distinct `cbtest_*` search paths; JDBC fallback database is exactly `test`.
- **Boundary/API:** A Boot `AutoConfigurationImportFilter` is the framework boundary and reads only `app.persistence.backend`. The guard exposes a redacted typed failure. Tests use a minimal `@EnableAutoConfiguration` application context rather than the production component graph.
- **Effects and failures:** Database integration remains opt-in and uses only a separately verified PostgreSQL 18.4 `test` database. The filter is pure environment classification; the guard converts raw database failures to a safe category/cause.
- **Tests and evidence:** RED will be a minimal Mongo application context currently failing because JDBC auto-configuration remains enabled, plus missing filter/typed-guard symbols. GREEN will cover both mini application contexts, filter candidate selection, production missing/invalid selectors, guard redaction/cause, exact JDBC fallback parsing, schema-test compilation, and architecture/compile regression.

### RED Evidence

- `GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:test --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendAutoConfigurationTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuardTest' --tests 'dev.christopherbell.configuration.PersistenceProfileConfigurationTest' --console=plain`
  - Expected `compileTestJava` failure: the backend framework filter and typed redacted identity failure classes did not exist.
- After adding the types, the real mini application contexts failed with a `NullPointerException` in the filter when Boot passed a null candidate. The failure was confined to the new filter and corrected by rejecting a null candidate.

### GREEN Evidence

- Files changed: backend auto-configuration import filter and `spring.factories` registration; safe typed guard failure/cause; real Mongo/PostgreSQL mini application-context tests; production missing/invalid selection tests; exact JDBC fallback parser assertion; corrected search-path fixture-isolation test.
- `GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:compileJava :website:test --tests 'dev.christopherbell.configuration.PersistenceProfileConfigurationTest' --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendSelectionTest' --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendAutoConfigurationTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuardTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestSchemaIsolationIntegrationTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestSchemaNameTest' --tests 'dev.christopherbell.configuration.LocalMongoComposeConfigurationTest' --tests 'dev.christopherbell.configuration.ProductionSettingsApplicationContextInitializerTest' --tests 'dev.christopherbell.architecture.MongoPersistenceBoundaryRulesTest' --tests 'dev.christopherbell.architecture.MongoPersistenceAdapterSelectionTest' --console=plain`
  - PASS: compile and 31 focused tests. Mongo context had only Mongo infrastructure; PostgreSQL context had JDBC/jOOQ and no Mongo infrastructure. The opt-in schema test was skipped without a connection.
- `git diff --check`
  - PASS.

### Database Evidence

- `postgresql-x64-16` remains running on port 5432 (PID 5960, bound to `::` and `0.0.0.0`), while Docker remains unavailable. This is neither the pinned PostgreSQL 18.4 Compose service nor a verified `test` database, so it was not queried or used.
- The corrected test now proves only PostgreSQL namespace/search-path isolation: each connection selects its own `cbtest_*` schema and reads a distinct unqualified fixture value. It makes no role/privilege access-isolation claim.

### Self-Review and Commit

- The filter gates Mongo, JDBC, jOOQ, and Flyway candidate auto-configurations from `app.persistence.backend`; local/test no longer rely on profile-specific exclusion lists. Missing/unsupported selection excludes gated frameworks and fails typed binding/production validation.
- The raw database exception is not retained because it may contain a JDBC URL or password. A typed, credential-free `DATA_ACCESS` cause preserves the actionable diagnostic category.
- Fix Round 1 commit: `8a21e54e2d1d80c1dbefee3e13e2f6393b3ba191` — `fix: gate persistence auto-configuration`.

### Verified PostgreSQL 18.4 Evidence (Follow-up)

- The supplied isolated PostgreSQL 18.4 cluster was independently identified as `current_database() = test`, `current_user = christopherbell_test`, server `127.0.0.1:55432`; it uses local disposable trust authentication and is not a production service.
- Command: `POSTGRESQL_INTEGRATION_TESTS=enabled; SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/test; SPRING_DATASOURCE_USERNAME=christopherbell_test; SPRING_DATASOURCE_PASSWORD=unused-for-disposable-trust-cluster; GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:test --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestSchemaIsolationIntegrationTest' --console=plain`
- Result: PASS. `guardedDisposableSchemasKeepConcurrentFixturesIsolated()` created two unique `cbtest_*` schemas, selected each connection's schema, and read distinct unqualified fixture values (`101` and `202`). Cleanup dropped only those generated schemas.
- This supersedes the earlier unavailable-database concern. Docker CLI remains unavailable; the repository's parsed-Compose test remains the Compose validation evidence.

## Fix Round 2 — Exhaustive Framework Selector Classification

### Before-Edit Brief

- **Behavior:** The sole `app.persistence.backend` selector must gate every resolved Spring Boot 4.1 MongoDB/data-Mongo and JDBC/jOOQ/Flyway persistence auto-configuration, including reactive, initialization, metrics, and endpoint variants.
- **Invariants:** PostgreSQL admits all and only the resolved relational family; MongoDB admits all and only the resolved Mongo family; missing and unsupported values admit neither family; unrelated Boot auto-configuration remains allowed.
- **Boundary/API:** `PersistenceBackendAutoConfigurationImportFilter` classifies the Boot auto-configuration package families. Its test reads the actual `AutoConfiguration.imports` resources available to the test runtime and verifies each candidate's family and result for all four selector states.
- **Effects and failures:** Import filtering remains pure and has no database effect. The real schema test remains opt-in and may write fixtures only after the identity guard validates database `test` and a generated `cbtest_*` schema.
- **Tests and evidence:** RED is the actual resolved-import selector contract against the prior exact-name set. GREEN reruns that contract, the backend contexts and production/profile/boundary tests, compilation, and the supplied PostgreSQL 18.4 schema-isolation integration test.

### Files Changed

- `website/src/main/java/dev/christopherbell/configuration/persistence/PersistenceBackendAutoConfigurationImportFilter.java`: replaces the incomplete class-name allowlists with reviewed Mongo/data-Mongo and JDBC/jOOQ/Flyway package-family classification.
- `website/src/test/java/dev/christopherbell/configuration/persistence/PersistenceBackendAutoConfigurationTest.java`: loads all resolved Boot auto-configuration import resources, rejects unclassified persistence candidates, and validates MongoDB, PostgreSQL, missing, and unsupported selector results.
- `.superpowers/sdd/2026-08-13-christopherbell-dev-postgresql-migration/task-1-report.md`: Fix Round 2 evidence.

### RED Evidence

- Command: `GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:test --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendAutoConfigurationTest' --console=plain`
  - Result: expected failure after test-only change: `selectorGatesEveryResolvedPersistenceAutoConfiguration()` failed at the selector assertion; 1 of 5 tests failed. The existing exact-name filter default-allowed four of the resolved candidates under the MongoDB selection (and correspondingly admitted Mongo candidates under PostgreSQL): the data-Mongo auto-configurations and the Mongo metrics auto-configuration. The complete contract also covers the unresolved relational initialization, JDBC client, pool-metrics, and Flyway endpoint candidates.

### GREEN Evidence

- Command: `GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:test --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendAutoConfigurationTest' --console=plain`
  - Result: PASS, 5 tests. The resolved-import contract discovered and classified all 21 actual persistence candidates: 9 Mongo/data-Mongo candidates and 12 JDBC/jOOQ/Flyway candidates. It verifies both selected values and fail-closed missing/unsupported values. A first post-change run exposed a test-only null `switch` expectation; the expectation was made explicit, then this same command passed.
- Command: `POSTGRESQL_INTEGRATION_TESTS=enabled; SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/test; SPRING_DATASOURCE_USERNAME=christopherbell_test; SPRING_DATASOURCE_PASSWORD=unused-for-disposable-trust-cluster; GRADLE_USER_HOME=A:\Projects\christopherbell.dev-gradle\postgresql-migration; .\gradlew.bat :website:compileJava :website:test --tests 'dev.christopherbell.configuration.PersistenceProfileConfigurationTest' --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendAutoConfigurationTest' --tests 'dev.christopherbell.configuration.persistence.PersistenceBackendSelectionTest' --tests 'dev.christopherbell.configuration.ProductionSettingsApplicationContextInitializerTest' --tests 'dev.christopherbell.architecture.MongoPersistenceBoundaryRulesTest' --tests 'dev.christopherbell.architecture.MongoPersistenceAdapterSelectionTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuardTest' --tests 'dev.christopherbell.configuration.persistence.PostgresqlTestSchemaIsolationIntegrationTest' --console=plain`
  - Result: PASS: `:website:compileJava` and 31 focused configuration, backend-selection, Mongo-boundary, database-guard, and real schema-isolation tests. The integration test passed after the guard selected disposable schemas and read distinct unqualified fixture values.
- `git diff --check`
  - Result: PASS.

### Database Identity Evidence

- The supplied isolated PostgreSQL cluster was independently verified before the integration command as PostgreSQL `18.4`, `current_database() = test`, `current_user = christopherbell_test`, server `127.0.0.1:55432`.
- The enabled integration run used exactly `jdbc:postgresql://127.0.0.1:55432/test` with the disposable trust-authentication placeholder password. `PostgresqlTestSchemaIsolationIntegrationTest` passed after its guard checked the `test` database and generated `cbtest_*` schema names before fixture writes; cleanup dropped only those schemas.

### Self-Review and Commit

- The source classifier covers all package families containing the 21 resolved candidates and preserves non-persistence Boot auto-configuration. The test independently discovers candidate import resources and fails if any Mongo/data-Mongo/JDBC/jOOQ/Flyway candidate lacks a reviewed backend classification.
- Missing or unsupported selector values parse to no backend, causing both persistence families to be excluded. Existing typed binding and production initializer tests continue to fail invalid application startup.
- No domain/service persistence coupling, relational schema, jOOQ generation, live service, or Builder repository change was introduced. No actionable finding remains in the final diff.

### Concerns

- Docker CLI remains unavailable, so Compose validation remains the parsed repository configuration test rather than `docker compose config`; this does not affect the verified isolated PostgreSQL 18.4 integration evidence.
