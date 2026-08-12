# Domain Release Metadata Compatibility Design

## Status

Approved for implementation.

## Problem

The guarded domain-collection cutover resolves two protected releases before any database effect: the currently active legacy release and the target release built from `origin/main`. Both releases can legitimately predate the `domainSchema` property in `release.json`. The current cutover rejects that historical metadata even when the release JAR itself unambiguously identifies its schema generation.

Production characterization on 2026-08-12 proved:

- active release `f4bc817d22abba70901fe4f17a93b4e52081085c` has exact legacy metadata keys `sha, source, builtAt, musicSchema` and no `domainSchema`;
- target release `af66f34218759cd7a0ae4b76a071f0fb44065457` has the same historical key set because it was created by the older release builder before the merged cutover code ran;
- preview remains exact and read-only with 52 kinds, 14 targets, and manifest digest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24`;
- the failed cutover stopped before backup, writer stop, publication, or legacy deletion, and production remained Running on port 8080.

## Decision

When cutover encounters an otherwise valid release whose metadata omits only `domainSchema`, it will derive the missing value from the authoritative executable JAR and atomically persist the explicit property before relying on it.

The classifier uses the exact JAR entry:

`BOOT-INF/classes/dev/christopherbell/configuration/mongo/migration/V015RequireDomainCollectionSchema.class`

- present means `TARGET`;
- absent in a readable, structurally valid executable JAR means `LEGACY`.

Modern metadata containing `domainSchema` remains authoritative and must contain exactly `LEGACY` or `TARGET`. A present value is never overwritten from the JAR.

## Boundary and Data Flow

The compatibility logic belongs at the protected release-metadata boundary in `Production.DomainCollections.psm1`:

1. Resolve and validate the release path and expected 40-character SHA.
2. Read `release.json`, require the stored SHA to match, and validate existing schema fields.
3. If `domainSchema` is present, return it unchanged.
4. If it is absent, open `app.jar` as a ZIP archive and classify the exact V015 entry.
5. Construct the same ordered release metadata plus `domainSchema` as the final property.
6. Publish it through the existing protected temporary-file, ACL, atomic-move, and readback boundary.
7. Re-read the metadata and require the persisted schema to match the derived value.

The cutover calls this compatibility boundary for both target and active legacy releases before the existing exact schema checks. No database command runs before it succeeds.

## Invariants and Failure Behavior

- The release directory identity and `release.json.sha` must match the expected SHA.
- Existing `domainSchema` values are accepted only when they are strings equal to `LEGACY` or `TARGET`; they are never normalized or overwritten.
- Backfill is allowed only when `domainSchema` is absent, not null, empty, mistyped, or invalid.
- The JAR must exist, be readable as a ZIP archive, and contain the normal executable layout needed for classification.
- The V015 entry name is compared exactly and ordinally.
- Metadata mutation is a single protected atomic replacement; failures before replacement leave original bytes intact.
- The derived value is read back and compared before cutover proceeds.
- No Mongo document, collection, index, manifest, ledger, backup, marker, service, listener, junction, or release JAR changes in this compatibility step.

## Tests

Focused PowerShell tests will establish RED before production edits and then prove:

- historical metadata plus a target JAR becomes explicit `TARGET` metadata;
- historical metadata plus a legacy JAR becomes explicit `LEGACY` metadata;
- modern valid metadata is unchanged byte-for-byte and does not require JAR inference;
- invalid SHA, null/mistyped/unknown `domainSchema`, missing JAR, corrupt JAR, and failed protected publication fail closed;
- cutover context invokes compatibility for both releases before schema acceptance;
- the exact production-shaped active and target metadata fixtures reach the existing pre-effect boundary.

After focused GREEN, run the owning domain/deploy/writer Pester suites on PowerShell 7 and Windows PowerShell 5.1, the full PS7 production suite, parser checks, full Gradle check and boot JAR, independent review, GitHub CI/CodeQL, merge, protected preview, and the confirmed cutover retry.

## Rejected Alternatives

- Rebuilding or deleting the existing target release adds unnecessary release-directory mutation and recovery complexity.
- Manually editing protected release metadata is unaudited, non-repeatable, and would not fix future historical releases.
- Permanently inferring schema at every writer start duplicates security-sensitive logic and leaves metadata ambiguous.

