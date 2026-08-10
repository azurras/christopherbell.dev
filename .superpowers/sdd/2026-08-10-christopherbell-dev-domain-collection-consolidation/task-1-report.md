# Task 1 Report - Canonical Kind-Scoped Persistence Boundary

## Status

Complete.

## Before-Edit Brief

- **Behavior:** Encode Spring-mapped domain values into the canonical
  `_id`/`_kind`/`schemaVersion`/`payload` envelope, decode them losslessly, and
  scope every read, write, update, and delete to the declared kind while mapping
  domain queries, sorts, and updates into the payload.
- **Invariants:** Only the 14 approved target collections and canonical
  lower-case kinds are constructible; `_id` is exactly ordered `kind`, then
  `legacyId`; `_id.kind == _kind`; schema version and envelope shape are exact;
  BSON identity and payload value types survive conversion; callers can address
  only mapped domain fields; and `@Version` saves use atomic compare-and-set so
  stale writers cannot overwrite the winner.
- **Boundary/API:** Preserve the specified `DomainDocumentKind<T>`,
  `NamespacedMongoId`, and `KindScopedMongoOperations<T>` public contracts.
  Domain callers use Java/domain field names; mapped non-ID paths become
  `payload.<stored path>`, while the domain ID maps only to
  `_id.legacyId`. Envelope metadata is never caller-addressable.
- **Effects and failures:** The implementation performs Mongo I/O only against
  `DomainDocumentKind.collection`. Malformed stored envelopes, unsupported or
  unapproved caller query/update shapes, and optimistic contention use distinct
  typed failures whose messages and complete throwable chains are redacted.
  Tests use only a disposable non-production MongoDB listener.
- **Tests and evidence:** First compile and run focused tests against the absent
  types and witness RED for identity order, envelope/query mapping, payload
  conversion, and contention. Then run focused unit tests, a disposable-Mongo
  suite covering scalar and ObjectId identities, Long and Decimal128 values,
  CRUD, index use, kind isolation, and stale-version rejection, plus the
  affected Mongo catalog architecture regression.

## Read-Only Investigation

- Confirmed the isolated worktree is clean on
  `codex/domain-collection-consolidation` and tracks `origin/main`.
- Confirmed the owning package is `dev.christopherbell.configuration.mongo` and
  its README describes shared Mongo infrastructure.
- Confirmed Spring Data MongoDB 5.1 exposes mapping metadata, query/update
  mappers, and raw `Document` operations needed to preserve BSON types without
  exposing envelope metadata to domain callers.
- Confirmed the existing Mongo collection catalog architecture test enumerates
  every production `MongoTemplate` owner, so the new scoped implementation must
  be registered as infrastructure in that test.

## RED Evidence

- Command:
  `$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'christopherbell-dev-domain-consolidation-task1-gradle'; .\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.configuration.mongo.domain.*' --console=plain`
- Result: **FAIL** during `:website:compileTestJava` after 1m50s, before any
  tests could execute. The compiler reported three representative
  `cannot find symbol` errors for the absent `DomainDocumentKind` and
  `MongoKindScopedOperations` boundary types. Test counts: 0 passed, 0 failed,
  0 skipped because compilation correctly stopped the run.
- Reviewer-regression unit command:
  `.\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperationsTest' --console=plain`
- Result: **FAIL** in 58s; 10 total, 5 passed, 5 failed, 0 skipped. The five
  failures proved that `updateFirst` did not advance the payload version,
  caller version updates were accepted, conversion and duplicate-key causes
  exposed raw values, and versioned insert contention retained a raw cause.
- Reviewer-regression real-Mongo command:
  `.\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperationsMongoTest.updateFirstAdvancesVersionSoAStaleSaveCannotOverwriteIt' --console=plain`
- Result: **FAIL**; 1 total, 0 passed, 1 failed, 0 skipped. A previously read
  value could save over an `updateFirst` winner because the stored version had
  not changed.

## Files Changed

- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/DomainDocumentKind.java`
  - Defines immutable, validated metadata and the exact 14-collection allowlist.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/NamespacedMongoId.java`
  - Encodes and validates the exact ordered compound BSON identity.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/KindScopedMongoOperations.java`
  - Defines the required domain-shaped persistence interface.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/DomainDocumentCodec.java`
  - Owns envelope validation, BSON-preserving Spring mapping, identity checks,
    and version initialization/increment.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/DomainMongoFieldMapper.java`
  - Validates domain field paths, rejects root-capable query/update shapes, and
    maps query, sort, and update paths beneath `payload` while preserving the
    domain ID as `_id.legacyId`.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/MongoKindScopedOperations.java`
  - Implements kind-scoped CRUD and atomic versioned save behavior.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/MalformedDomainDocumentException.java`
  - Adds the redacted typed persisted-envelope failure.
- `website/src/main/java/dev/christopherbell/configuration/mongo/domain/UnapprovedDomainFieldException.java`
  - Adds the redacted typed caller-boundary failure.
- `website/src/main/java/dev/christopherbell/configuration/mongo/README.md`
  - Documents the consolidated persistence ownership rule.
- `website/src/test/java/dev/christopherbell/configuration/mongo/domain/DomainDocumentKindTest.java`
- `website/src/test/java/dev/christopherbell/configuration/mongo/domain/NamespacedMongoIdTest.java`
- `website/src/test/java/dev/christopherbell/configuration/mongo/domain/MongoKindScopedOperationsTest.java`
  - Adds focused metadata, identity, codec, query/update mapping, redaction, and
    stale-writer coverage.
- `website/src/test/java/dev/christopherbell/configuration/mongo/domain/MongoKindScopedOperationsMongoTest.java`
  - Adds guarded disposable-Mongo BSON, CRUD, index, isolation, and contention
    evidence.
- `website/src/test/java/dev/christopherbell/architecture/MongoCollectionCatalogTest.java`
  - Registers the single scoped implementation as approved Mongo infrastructure.
- `.superpowers/sdd/2026-08-10-christopherbell-dev-domain-collection-consolidation/task-1-report.md`
  - Records this task's contract, evidence, review, commits, and residual risk.

## GREEN and Regression Evidence

All Gradle commands used the isolated task cache
`$env:TEMP\christopherbell-dev-domain-consolidation-task1-gradle` and
`--no-daemon`.

1. Focused reviewer corrections:
   - Command:
     `.\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperationsTest' --console=plain`
   - Result: **PASS** in 58s; 10 passed, 0 failed, 0 skipped. Coverage proves
     version auto-increment, caller-version rejection, stale contention, and
     redaction through the complete throwable chain.
2. Real disposable MongoDB boundary:
   - Setup: started the installed MongoDB binary hidden on loopback port 27019
     with a unique task-temporary data directory. The connection value was
     supplied only through `DOMAIN_COLLECTION_TEST_URI` and is intentionally
     omitted. The test itself rejects non-loopback, multi-host, and default-port
     targets before connecting.
   - Command:
     `.\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperationsMongoTest' --console=plain`
   - Result: **PASS**; 3 passed, 0 failed, 0 skipped. Evidence covered
     Long and Decimal128 payloads, scalar and ObjectId identities, insert/read/
     query/update/delete, a compound kind/payload index selected by the planner,
     same-ID kind isolation, version initialization, a winning compare-and-set,
     `updateFirst` version advancement, stale-writer rejection, and winner
     preservation.
   - Cleanup: stopped the disposable listener, verified port 27019 was no longer
     listening, and removed only its verified task-temporary directory.
   - Evidence-collection note: the first shell wrapper returned an outer timeout
     while its Gradle child continued. A concurrent retry stopped at
     `processResources` before executing tests because that first build still
     owned the outputs. The original build then completed, and its fresh XML
     recorded the 3/0/0/0 result above; neither wrapper event was a test failure.
3. Focused boundary plus affected architecture regression:
   - Command:
     `.\gradlew.bat --no-daemon :website:test --tests 'dev.christopherbell.configuration.mongo.domain.*' --tests 'dev.christopherbell.architecture.MongoCollectionCatalogTest' --console=plain`
   - Result: **PASS** in 56s; 39 passed, 0 failed, 3 skipped. The three guarded
     Mongo tests skipped because this combined run intentionally omitted the
     disposable URI; all passed in command 2.
4. Full Java regression:
   - Command:
     `.\gradlew.bat --no-daemon :website:test --console=plain`
   - Result: **PASS** in 2m49s; 1,765 total, 1,747 passed, 0 failures, 0 errors,
     18 skipped. Counts were summed from the fresh JUnit XML files.
5. Packaging:
   - Command:
     `.\gradlew.bat --no-daemon :website:bootJar --console=plain`
   - Result: **PASS** in 54s; `BUILD SUCCESSFUL` (no test count for this task).
6. Diff validation:
   - Command: `git diff --cached --check`
   - Result: **PASS**; no whitespace errors. A staged-diff scan also found no
     Mongo URI, production-port, credential, JWT-secret, mail-key, password, or
     Cloudflare literal.

Intermediate implementation correction evidence: the first post-implementation compilation
identified three invalid multi-argument AssertJ calls before tests ran. After
fixing those test-only calls, the next focused run executed 27 tests with 24
passed, 1 failed, and 2 skipped; the sole failure was an unfinished Mockito
stubbing expression caused by building a fixture inside `thenReturn`. Moving
fixture construction before stubbing produced the targeted pass without a
production-code change. The independent review then found the two Important
issues captured in the later RED evidence above. The fixes made version
advancement an atomic responsibility of `updateFirst`, rejected direct version
updates, and removed raw driver/conversion failures from every redacted cause
chain before commands 1-5 were rerun.

## Self-Review

- Reconciled the final diff with every Before-Edit Brief field and Task 1 method.
- Verified only `MongoKindScopedOperations` imports `MongoTemplate` in the new
  package; codecs and mappers are pure boundary helpers, and domain callers see
  only domain field names and domain values.
- Traced caller queries through allowlisted top-level properties, Spring's
  entity-aware query/update mapping, payload namespacing, and the outer `_kind`
  criterion. Projections, unknown paths, raw envelope paths, replacement
  updates, and root-capable expression/JavaScript/schema operators fail closed.
- Traced `updateFirst` through the same mapping path. A versioned kind receives
  one boundary-owned atomic `$inc` of `payload.<versionField>`, while caller
  writes to IDs or version metadata fail closed; this invalidates any stale
  entity read before the update.
- Traced insert and save ownership. Insert always emits the canonical kind and
  compound identity. Versioned save finds only the namespaced kind, initializes
  an absent version, increments the candidate, compares the stored payload
  version atomically, translates insert races to optimistic contention, and
  never permits a stale replacement.
- Traced persisted input through exact envelope field order, exact identity
  order, kind/schema checks, payload-only mapping, and identity re-encoding.
  Stored malformed data is rejected before reaching domain code. Conversion,
  duplicate-key, and optimistic-contention translations retain required Spring
  exception types but never retain raw driver/mapping messages in their causes.
- Mutation check: changing kind injection, field prefixing, ID order, version
  increment/criterion, payload BSON conversion, index path, or stale-write
  outcome causes at least one focused or real-Mongo test to fail.
- `git diff --cached --check`, full tests, and boot packaging passed. No blocker
  or warning remained in the Jane Street testing/review rubric self-review.
- Independent staged-diff review initially reported two Important findings:
  missing `updateFirst` version advancement and raw-value leakage through
  wrapper causes. Both received focused unit and real-Mongo regressions and the
  corrections described above. Final re-review found no Critical, Important,
  or Minor findings and returned **READY** after confirming the post-fix
  disposable-Mongo 3-pass evidence.

## Commits

- `1745956f86d8b4e1724a18a32c5fd0f1e17d737e` -
  `feat: add kind-scoped Mongo persistence`

## Residual Concerns

- The disposable-Mongo suite is deliberately environment-gated in ordinary
  builds; this task ran it explicitly and recorded the evidence above.
- Query projections, raw envelope field names, replacement updates, and
  root-capable query operators are intentionally unsupported because returning
  partial domain values or allowing metadata-capable expressions would weaken
  the boundary. Later adapters must use domain-shaped queries/updates or add a
  separately reviewed typed read-model boundary.
- Tasks 2 and later still own migration definitions, runtime adapter adoption,
  index recreation, enforcement of the final direct-access ban, and production
  cutover. This task performed no production operation.
