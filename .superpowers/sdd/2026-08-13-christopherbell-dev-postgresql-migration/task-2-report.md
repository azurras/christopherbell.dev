# Task 2 Report - Canonical PostgreSQL Schema and jOOQ Generation

## Status

complete

## Before-Edit Brief

- **Behavior:** Applying the immutable PostgreSQL Flyway migration set to an empty, uniquely prefixed disposable test target must create the complete ten-domain relational schema, and the build must generate canonical typed jOOQ sources from that migrated schema without embedding the disposable prefix.
- **Invariants:** The schema contains exactly the ten approved canonical domains and exactly one migration-catalog entry for each of the 52 `DomainCollectionManifest` kinds; every schema-qualified migration identifier uses validated `${schema_prefix}`; primary keys, foreign keys, delete actions, optimistic versions, expiration columns, UTC timestamps, numeric precision, uniqueness, and cursor/query indexes are explicit; unknown catalog kinds, properties, fields, aliases, wildcards, duplicate keys, and unjustified JSONB fail closed.
- **Boundary/API:** Immutable Flyway SQL is the only DDL authority; the strict migration catalog is the future transformer contract; generated jOOQ types are the future PostgreSQL adapter contract; this task does not add domain adapters or the runtime migration engine.
- **Effects and failures:** Schema preparation and validation perform bounded writes only in database `test` and only to ten exact schemas sharing one owned `cbtest_<run>_` prefix; cleanup drops only those exact owned schemas. JDBC credentials come from required environment variables and must not be logged. Flyway/catalog/code-generation failures stop immediately with safe kind/path or configuration metadata and preserve their underlying cause without exposing credentials.
- **Tests and evidence:** RED evidence will be failing behavioral tests that attempt strict catalog loading, empty-schema Flyway migration, catalog/manifest coverage, and schema constraint/index inspection before the artifacts exist. GREEN evidence will use real PostgreSQL 18.4 schema outcomes, exact 52-kind comparison, generated-source compilation, and two clean unique-prefix code-generation runs whose canonical generated trees have byte-identical hashes, followed by focused Task 2 tests, compilation, and Task 1 persistence regressions.

## Pre-Write PostgreSQL Identity

- Observed at: 2026-08-13 (America/Chicago)
- Server: `127.0.0.1:55432`
- PostgreSQL: `18.4`
- `current_database()`: `test`
- `current_user`: `christopherbell_test`
- Existing schemas whose names start with `cbtest_`: none
- Credential placeholder for this disposable trust-authenticated cluster: `unused-for-disposable-trust-cluster`
- Planned owned prefix form: one unique `cbtest_<run>_` prefix producing exactly ten flat schemas; cleanup will name and drop only those ten exact schemas.

## Files Changed

- `build.gradle.kts` - pins the jOOQ Gradle code-generation plugin at `3.21.5`.
- `gradle/verification-metadata.xml` - records SHA-256 checksums for the pinned plugin/codegen artifacts and their newly resolved transitive artifacts.
- `website/build.gradle.kts` - applies jOOQ codegen, declares the codegen driver and test dependency, creates the isolated Flyway preparation source set, maps ten prefixed input schemas to canonical output schemas, adds generated sources to `main`, and wires guarded preparation/cleanup around generation.
- `website/src/jooqPreparation/java/dev/christopherbell/codegen/PostgresqlJooqSchemaTool.java` - validates database `test` and an owned unique prefix, requires a clean target, applies Flyway, and drops only the ten exact owned schemas.
- `website/src/main/java/dev/christopherbell/configuration/persistence/migration/PostgresqlMigrationCatalog.java` - strict immutable typed catalog and closed conversion/semantics vocabulary.
- `website/src/main/java/dev/christopherbell/configuration/persistence/migration/PostgresqlMigrationCatalogLoader.java` - bounded YAML parsing with duplicate-key, alias/anchor, wildcard, unknown-property, and invalid-rule rejection.
- `website/src/main/java/dev/christopherbell/configuration/persistence/migration/PostgresqlMigrationCatalogValidator.java` - runtime manifest/source-field/dependency/declared-target validation so unknown kinds and fields fail closed outside the test suite too.
- `website/src/main/java/dev/christopherbell/configuration/persistence/migration/PostgresqlMigrationCatalogException.java` - safe catalog-boundary exception.
- `website/src/main/resources/db/migration/V1__create_schemas_and_migration_ledger.sql` - ten schemas and migration run/source ledgers.
- `website/src/main/resources/db/migration/V2__create_identity_social_communication_federation.sql` - identity, social, communication, and federation relational schema.
- `website/src/main/resources/db/migration/V3__create_music_shared_folder.sql` - music and shared-folder relational schema.
- `website/src/main/resources/db/migration/V4__create_mobility_lunch_canes_platform.sql` - mobility, lunch, Canes, and remaining platform relational schema.
- `website/src/main/resources/db/migration/postgresql-migration-catalog.yml` - 52 explicit source-kind transformation contracts and all current persisted top-level fields/child relations.
- `website/src/test/java/dev/christopherbell/configuration/persistence/migration/PostgresqlMigrationCatalogLoaderTest.java` - strict parser boundary tests.
- `website/src/test/java/dev/christopherbell/configuration/persistence/migration/PostgresqlMigrationCatalogTest.java` - exact manifest, collection, schema-version, persisted-field, and concrete-target coverage.
- `website/src/test/java/dev/christopherbell/configuration/persistence/migration/PostgresqlSchemaTestSupport.java` - guarded real-PostgreSQL migration/cleanup harness.
- `website/src/test/java/dev/christopherbell/configuration/persistence/migration/PostgresqlSchemaContractTest.java` - behavioral schema, constraint, key, index, precision, uniqueness, coordinate, playlist, and lease assertions.
- `website/src/test/java/dev/christopherbell/configuration/persistence/migration/JooqGenerationReproducibilityTest.java` - two-clean-prefix byte equality and canonical source hashing.
- `website/src/test/java/dev/christopherbell/codegen/PostgresqlJooqSchemaToolTest.java` - identifier-length and persisted ownership-marker cleanup safeguards against PostgreSQL truncation and cross-run deletion.

## RED Evidence

- Catalog loader RED: `:website:compileTestJava` initially failed because the strict loader did not exist. After the first implementation, duplicate-key input was still wrapped as a mapping error; the test stayed red until duplicate causes were classified explicitly.
- Catalog coverage RED: the loader/coverage tests rejected the absent resource, then passed only after all 52 manifest kinds and all reflected persisted fields had explicit contracts.
- Runtime drift RED: after the checked-in catalog passed, mutated full-catalog inputs containing an unknown manifest kind, an invented persisted field, or an undeclared target table were still accepted. The loader remained red until production validation was tied to the exact manifest owner fields, ID paths, dependencies, and declared target tables.
- Schema RED against real PostgreSQL 18.4:
  - before DDL, Flyway applied `0` migrations where `4` were required;
  - restaurant insertion failed with missing-table SQL state `42P01` instead of coordinate-check SQL state `23514`;
  - named FK/delete-rule lookup found no matching constraint;
  - after V1/V2 only, Flyway applied `2` migrations and music/lunch contracts remained absent.
- jOOQ RED: `:website:compileTestJava` failed on missing `org.jooq.codegen` and `org.jooq.meta.jaxb` packages before codegen was configured.
- Dependency-integrity RED: first plugin resolution was rejected by Gradle dependency verification until the pinned artifacts received generated SHA-256 entries.
- Preparation-path RED: the first isolated preparation run saw zero migrations because its source set did not carry Flyway resources; adding `src/main/resources` to that isolated runtime made the real preparation path apply all four migrations. The failed prefix was explicitly cleaned before reuse.
- Independent-review RED/hardening:
  - the new identifier-boundary test initially failed compilation because codegen preparation had no 63-byte prefix guard;
  - the ownership-marker integration test initially failed compilation because no injectable guarded prepare/clean boundary existed;
  - review established that `MusicAccessAttempt.count` and `WhatsForLunchSession.restaurantResetCount` are Java `long` values while their first DDL draft used `integer`;
  - review also established that `createSchemas(true)` precreated all ten schemas, so V1's prior `IF NOT EXISTS` statements could not prove migration ownership.
- GREEN fixes: ASCII-only prefixes are bounded so the longest full schema identifier is at most 63 bytes; an advisory lock serializes same-prefix runs; cleanup requires both a local UUID token and its exact migration-ledger row; the two long columns are `bigint`; and Flyway now uses a unique owned history table in existing `public` with `createSchemas(false)`, while strict V1 statements create the ten domain schemas.
- Two-live-prefix RED: a second clean prefix initially failed with Flyway's non-empty-schema-without-history error while the first prefix's unique history table remained in `public`.
- Two-live-prefix GREEN: each unique history safely bootstraps at baseline version `0`; strict migrations V1-V4 still execute, and the integration test holds two migrated prefixes plus two histories live concurrently before exact cleanup.

## GREEN Evidence

- Final verification command (private `GRADLE_USER_HOME`, real disposable PostgreSQL, and a unique jOOQ prefix):
  - `./gradlew.bat :website:jooqCodegen :website:compileJava :website:test` with focused Task 2 tests and the five Task 1 regression classes.
  - Final result after runtime drift and review hardening: `BUILD SUCCESSFUL` in 1m15s; 28 tests passed, zero failed/skipped among the selected tests.
- Task 2 behavioral results:
  - 4 immutable Flyway migrations applied to each empty target;
  - exactly ten owned schemas and exactly the catalog-declared base tables plus two platform migration-ledger tables;
  - every catalog key/field target resolved to a real relational column;
  - no application JSON/JSONB or timestamp-without-time-zone columns;
  - no catalog table lacked a primary key;
  - named FK delete actions, numeric precision/scale, cursor indexes, coordinate pairing, VIN uniqueness, playlist uniqueness/order, and database-time lease claim/renew/release passed.
  - both migrated Java `long` counters accepted and returned `2,147,483,648`; generated jOOQ fields are `TableField<..., Long>` backed by `SQLDataType.BIGINT`.
  - a deliberately corrupted cleanup token was rejected by the persisted ledger marker check and left the owned schema intact; restoring the exact token allowed exact cleanup.
  - two distinct prefixed schema sets and their unique histories remained live simultaneously; both independently applied four strict migrations and cleaned without crossing ownership boundaries.
- Exact catalog evidence: 52 YAML `sourceKind` entries matched `DomainCollectionManifest.ALL_KINDS` exactly, including collection/schema-version, reflected non-ID persisted fields, ID source path, target schema, canonical hash, reconciliation, and port-query declarations.
- Final database cleanup query: `current_database=test`; remaining schemas beginning `cbtest_`: `<none>`; remaining owned `public.flyway_cbtest_*` history tables: `<none>`; remaining codegen ownership-token files: `0`.
- `git diff --check`: exit 0.

## jOOQ Reproducibility Evidence

- Programmatic reproducibility test generated from two clean, distinct prefixes:
  - `cbtest_t2_c60451c7_50e6_478d_aa63_591fc42e588a_`
  - `cbtest_t2_770d3352_af33_4568_ae04_30a16a56110f_`
- The test compares the complete sorted relative-path-to-UTF-8-content maps for direct byte equality, computes a boundary-safe SHA-256 over sorted paths and bytes, asserts the hashes match, and rejects either disposable prefix in every generated source.
- Canonical generated-tree SHA-256 from both runs: `0bd7f2e7ed4dddcc6a1d5351fdc18c52c13f2675c41341c873c36aae9c3d0f97`.
- Real Gradle generation used `cbtest_jooq_task2finale_`, prepared all four migrations, generated 205 Java sources, compiled them, and automatically removed the ten exact prefixed schemas, its exact public history table, and its ownership token.
- Post-generation inspection: 205 generated `.java` files; zero `cbtest_` matches. Generated sources remain under `website/build/generated-src/jooq/main` and are not committed.
- The generation log named the safe database/prefix only; the credential placeholder did not appear.

## Self-Review

- Requirements reread against the final diff: no Task 3+ adapter, Task 6 migration runtime, production/service configuration, or production data changes are present.
- DDL authority: all table/schema/index creation is in immutable Flyway SQL; the codegen preparer invokes Flyway rather than issuing DDL. Its only direct SQL write is exact, guarded test-schema cleanup.
- Prefix safety: production-compatible SQL accepts the empty prefix; the codegen/test tools accept only owned `cbtest_<run>_` prefixes, validate `current_database() = test`, enforce PostgreSQL's 63-byte identifier limit, require clean unique schemas/history, acquire a prefix advisory lock, and name all ten schemas explicitly for cleanup.
- Cleanup ownership: preparation records a random UUID in both a private build token and the prefixed platform migration ledger. Cleanup checks both under the same advisory lock before any `CASCADE`, removes non-platform schemas first and the marker-bearing platform schema last, and refuses missing/mismatched ownership.
- Catalog boundary: input is bounded to 1 MB and 64 nesting levels; duplicate properties, anchors, aliases, unknown fields, unsupported conversion/presence/delete/version/expiry rules, duplicate source kinds, noncanonical identifiers, undeclared targets, and wildcard/JSON targets fail closed.
- Relational review: 70 explicit non-PK indexes plus named uniqueness/FK/check constraints cover the current identity, expiration, lease, cursor, and query boundaries; every timestamp is UTC-aware and money/coordinate/duration precision is explicit.
- Generated-code review: canonical schema output names prevent run prefixes from entering Java; volatile annotation date/version fields and ambiguous implicit join paths are disabled; generated sources compiled successfully.
- Independent reviewer found three issues in the first complete draft: truncation/race exposure in cleanup, two narrowed Java `long` fields, and automatic schema creation bypassing V1. The first re-review then found cross-prefix Flyway history interference in `public`. All findings were fixed with focused behavioral tests. Final narrow re-review: approved, with no remaining Critical or Important issue.

## Commits

- Task 2 implementation commit: `feat: define canonical PostgreSQL schema` (this cohesive commit; exact hash is recorded in Git history and the task handoff).

## Concerns

- No known Task 2 correctness blocker.
- Gradle still reports repository-existing deprecation warnings at a later, unrelated delegated-task declaration in `website/build.gradle.kts`; this task did not introduce that declaration.
