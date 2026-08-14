# Task 6 implementation report

## Before-edit brief

- **Behavior:** A non-web migration command reads each of the 52 approved consolidated Mongo kinds through an exact manifest scope, transforms deterministic batches into run-owned PostgreSQL staging rows, commits the staged rows and checkpoint atomically, reconciles complete kinds, and publishes one complete kind in one bounded PostgreSQL transaction. `shadow`, `finalize`, `reconcile`, and `status` are the only operations.
- **Invariants:** MongoDB is read-only. Source envelopes, schema versions, kinds, fields, identities, and catalog transformer bindings fail closed. A checkpoint never advances without its complete staged batch. Incomplete or unreconciled kinds never publish. Replaying an unchanged source is idempotent. `finalize` requires source-frozen evidence bound to the release, catalog digest, database identities, source/backup digests, and protected-lock token; Task 6 never deletes Mongo source data. V1-V10 remain immutable.
- **Boundary/API:** `PostgresqlMigrationCli` is a standalone process boundary and does not register an HTTP route or use the ordinary website persistence role. The strict checked-in catalog remains the transformation contract. Migration source, ledger, staging target, reconciler, and publisher are narrow explicit effects; domain services and runtime adapters remain unchanged.
- **Effects and failures:** Source reads are stable-ID ordered and batch-limited. PostgreSQL transactions own either one staged batch/checkpoint or one complete-kind publication. Every wait and batch is bounded. Expected validation/reconciliation failures use typed redacted categories; driver exceptions, payloads, URIs, usernames, passwords, SQL, and row values never enter command output. A failed or interrupted operation resumes only from durable ledger state.
- **Tests and evidence:** RED begins with the closed command parser, fail-before-I/O preflight matrix, exact 52-transformer registry, deterministic canonical hash, checkpoint crash matrix, reconciliation/publication guard, and frozen-source finalize guard. GREEN adds real PostgreSQL V1-V11 upgrade/ledger behavior, a read-only disposable Mongo source, all-kind staging/completeness, interruption/resume/idempotence, exact digest/count readback, query/scale bounds, Tasks 1-5 regression, full checks, cleanup, and self-review.

## RED checkpoints

- Closed command parser: the exact focused compile failed with 14 missing-symbol errors before `PostgresqlMigrationCommand` existed. The accepted parser recognizes only lower-case `shadow`, `finalize`, `reconcile`, and `status`; locale changes and rejected values cannot enter its error.
- Preflight/hash/registry: the exact focused compile failed with 11 missing preflight symbols. The subsequent astral-Unicode regression failed 1/10 because a valid UTF-16 pair was being split; consuming the pair atomically made its SHA-256 match the independently calculated UTF-8 digest.
- Crash/resume engine: `:website:compileTestJava` failed with 12 missing engine/store symbols. The implemented tests inject failure after a source read and inside the second target batch, proving that only a committed first batch advances its cursor.
- Forward schema: the live schema contract expected 11 and failed at the exact migration count while V1-V10 were the only migrations. V11 was therefore necessary; none of V1-V10 changed.

## Implementation

- Added a standalone environment-driven `postgresqlMigration` JavaExec command. Secrets and connection strings are accepted through environment variables, never positional arguments. The checked-in catalog digest is computed by the command, and command output contains only operation, kind count, and a status digest.
- Added exact loopback/database/role/port preflight. Test runs require database `test`, role `christopherbell_test`, and a unique `cbtest_*_` prefix. Production runs require both databases to be `christopherbell`, role `christopherbell_bridge`, and the unprefixed canonical schemas. Observed Mongo and PostgreSQL identities must exactly match requested hosts, ports, databases, and target role before source reads or writes.
- Added 52 exact catalog-bound transformer classes plus strict unknown-field/schema rejection and deterministic typed canonical hashing. Hashing preserves null versus missing, list order and duplicates, UTC instants, dates, UUIDs, BSON identifiers/decimal/binary values, normalized JSON numbers, and valid astral Unicode.
- Added a read-only Mongo capability with stable `_id.legacyId` ordering, opaque typed cursors, bounded pages, exact envelope/id/schema validation, and a source-wide unknown-kind guard for every catalog collection. There is intentionally no source mutation method.
- Added V11 with per-run/per-kind checkpoint, source count/digest, reconciliation, publication state, stable source sequence, and typed staged-row payloads. Stage rows, source hashes, and the next cursor commit atomically. The staged-row codec is versioned, typed, size-bounded, and does not add JSON/JSONB domain storage.
- Added JDBC reconciliation and publication. Reconciliation recomputes ordered source counts/digests and validates staged targets; publication reloads typed values, restricts identifiers to catalog-owned schemas/tables/columns, and inserts the complete kind plus its published marker in one transaction. A failed transaction or repeated completed command converges without partial visibility.

## Focused verification

- Core migration/schema package plus legacy Mongo architecture: 14 suites, 56/56 tests passed, zero skips/failures, `BUILD SUCCESSFUL in 1m47s`.
- Live JDBC failure matrix passed: duplicate batch rollback left zero source rows and the initial checkpoint; a failure injected after typed inserts rolled back both typed rows and the publication marker; retry published exactly two rows; a second retry was idempotent.
- Live Mongo reader passed on disposable `mongodb://127.0.0.1:57018/test`: exact paging, opaque cursor resume, unknown envelope and unknown catalog kind rejection, and byte-identical source readback.
- All 52 manifest transformers passed representative every-conversion fixtures including missing/default rules, Unicode, timestamps, numerics, binary values, duplicate ordered lists, sets, maps, flattened records, child rows, and record lists.
- Scale evidence: 1,001 source documents at batch size 100 produced exactly 11 committed batches plus one empty completion read; all 12 source requests used limit 100.
- End-to-end acceptance ran every catalog kind twice through real disposable Mongo and PostgreSQL: 52/52 kinds were complete and published, the second status digest exactly matched the first, one source document produced one exact typed `application_lease` row, and the Mongo envelope remained byte-identical. `BUILD SUCCESSFUL in 1m16s` with the five legacy Mongo boundary tests.
- Self-review added a dedicated VIN outer-response/nested-raw-map test. It correctly RED because generic presence handling overwrote absent nested-map state with `true`; the catalog transformer now preserves absent versus present-empty raw maps and emits deterministically sorted raw child rows. The accepted focused rerun passed. A live publisher test also proves that the `vin_decode_raw_value.vin` same-kind foreign key is supplied from the source identity, rather than guessed from an `_id` suffix.

## Final verification

- Definitive final-tree `:website:jooqCodegen :cbell-lib:check :website:check`: `BUILD SUCCESSFUL in 7m25s` against `jdbc:postgresql://127.0.0.1:55432/test` with a unique `cbtest_t6_definitive2_` prefix and `mongodb://127.0.0.1:57018/test`. The run executed 29 Gradle tasks (16 executed, 13 up-to-date).
- Exact parsed results: `cbell-lib` 21 suites/123 tests/0 failures/0 skips; website JUnit 351 suites/2,111 tests/0 failures/123 expected skips; JavaScript 343 tests/0 failures; Pester 441 tests/439 passed/0 failures or errors/2 expected skips.
- V1-V10 immutability: retained; only forward V11 was added.
- Runtime wiring: no website backend selector, Spring bean, HTTP route, port, or production listener changed, so an alternate-port website smoke was not applicable.
- Cleanup: verified the disposable PostgreSQL cluster identity as `test|christopherbell_test|18.4`; zero `cbtest_*` schemas, prefixed Flyway histories, or unprefixed canonical schemas remained. Dropped only the disposable Mongo `test` database, stopped only the Mongo process listening on port 57018, verified the port closed, and moved the Task 6 temporary Mongo directory to the Recycle Bin.
- Final Git evidence: staged-diff review and the single Task 6 commit follow this report update; the parent task owns review and push.
