# Domain Release Metadata Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backfill a missing `domainSchema` property in historical protected release metadata from the authoritative executable JAR so the guarded consolidation can classify both releases before any database effect.

**Architecture:** Keep compatibility inside `Production.DomainCollections.psm1`. Validate the exact historical or modern metadata shape, classify a historical release by the exact V015 class entry in `app.jar`, atomically append `domainSchema` through the existing protected JSON writer, and read it back before cutover proceeds.

**Tech Stack:** PowerShell 7 / Windows PowerShell 5.1, Pester 5.9, .NET ZIP APIs, Gradle/Java 25, GitHub Actions.

## Global Constraints

- Backfill only metadata whose exact ordered keys are `sha, source, builtAt, musicSchema` and whose SHA, source, timestamp, and Music schema values are valid.
- Preserve modern metadata whose exact ordered keys are `sha, source, builtAt, musicSchema, domainSchema`; never overwrite an existing `domainSchema`.
- Derive `TARGET` only from the exact JAR entry `BOOT-INF/classes/dev/christopherbell/configuration/mongo/migration/V015RequireDomainCollectionSchema.class`; otherwise derive `LEGACY` only from a readable executable JAR containing `BOOT-INF/classes/dev/christopherbell/Application.class`.
- Publish backfilled metadata only through protected temporary-file, ACL, atomic-move, and readback verification.
- Do not change Mongo data, indexes, manifest, ledger, backup, marker, service, listener, junction, JAR, or rollback behavior in the compatibility step.
- Do not resume production cutover until the fix is merged and the protected preview remains exact.

---

## Document Status

ready-for-execution

## Objective

Make the guarded cutover accept and durably upgrade the exact historical release metadata found in production without weakening modern metadata validation.

## Goals

1. Classify the historical active release as `LEGACY` and the historical new release as `TARGET` from their executable JARs.
2. Persist the derived value atomically before cutover relies on it.
3. Reject ambiguous metadata, corrupt/non-executable JARs, and failed publication without effects.
4. Merge the fix and complete the already-approved guarded consolidation.

## Inputs

- Approved design: `docs/superpowers/specs/2026-08-12-domain-release-metadata-compatibility-design.md`
- Production active release: `f4bc817d22abba70901fe4f17a93b4e52081085c`
- Production target release: `af66f34218759cd7a0ae4b76a071f0fb44065457`
- Exact preview: manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24`, 52 kinds, 14 targets.

## Non-Goals

- No manual protected-file edit, release rebuild, JAR rewrite, or Mongo mutation in the compatibility step.
- No change to migration semantics, deletion allowlist, startup gate, rollback state machine, or public application behavior.

## Assumptions

- Every repository-built executable JAR contains `BOOT-INF/classes/dev/christopherbell/Application.class`.
- V015 is the exact release-schema discriminator used by the current release builder.
- The existing protected JSON writer is the authoritative ACL and atomic-publication boundary.

## Open Questions

None. Any unrecognized metadata or JAR shape blocks cutover.

## Branch

- Base: `origin/main` at `af66f34218759cd7a0ae4b76a071f0fb44065457`
- Feature: `codex/domain-release-metadata-compatibility`
- Worktree: `A:\Projects\christopherbell.dev-worktrees\domain-v014-preview-merged`

## Before-Edit Brief

- **Behavior:** Historical valid release metadata becomes explicit `LEGACY` or `TARGET` metadata from its own executable JAR; modern metadata remains unchanged.
- **Invariants:** Exact SHA/path/metadata/JAR shapes are required, existing schema is authoritative, and any ambiguity or publication mismatch fails before cutover effects.
- **Boundary/API:** `Get-ProductionDomainCollectionReleaseSchema` remains the cutover classification boundary and adds mandatory `Config` only for protected atomic publication.
- **Effects and failures:** Only a missing-property metadata file may be atomically replaced; malformed metadata/JAR, ACL failure, atomic-write failure, or readback mismatch throws a redacted `InvalidDataException` and blocks cutover.
- **Tests and evidence:** Pester fixtures reproduce the two exact production metadata shapes, witness RED at the current strict reader, then prove target/legacy backfill, modern preservation, malformed negatives, dual-release cutover ordering, cross-host parsing, full suites, CI, protected preview, and guarded cutover.

## Task Breakdown

### Task 1: Add exact protected release metadata compatibility

Required skill: invoke `write-jane-street-style-code` and `superpowers:test-driven-development` before code edits.

**Files:**
- Modify: `ops/production/windows/modules/Production.DomainCollections.psm1:298-318,1034-1049`
- Modify: `ops/production/windows/tests/Production.DomainCollections.Orchestration.Tests.ps1:300-520`

**Interfaces:**
- Consumes: protected release directory, expected SHA, `Write-ProductionDomainCollectionProtectedJson`, and `app.jar`.
- Produces: `Get-ProductionDomainCollectionReleaseSchema -Config <config> -Release <path> -Sha <sha>` returning `LEGACY` or `TARGET` after exact validation and any required atomic backfill.

#### Code Edit 1

- File: `ops/production/windows/tests/Production.DomainCollections.Orchestration.Tests.ps1`
- Lines: 300-520
- Action: add
- Current: Cutover-context tests mock `Get-ProductionDomainCollectionReleaseSchema`; no test constructs production-shaped historical release metadata and JARs.
- Proposed: Add a helper that writes exact ordered `release.json` fixtures and minimal executable ZIP/JAR fixtures. Add Pester cases for historical target backfill, historical legacy backfill, modern byte preservation, wrong SHA, invalid/null/mistyped schema, missing/corrupt/non-executable JAR, protected-publication failure, and cutover classification of target then legacy.

```powershell
$schema = Get-ProductionDomainCollectionReleaseSchema `
    -Config $config -Release $release -Sha $sha
$schema | Should -BeExactly 'TARGET'
@((Get-Content $metadataPath -Raw | ConvertFrom-Json).PSObject.Properties.Name) |
    Should -Be @('sha','source','builtAt','musicSchema','domainSchema')
```

- Verification: Before production edits, the two historical success tests fail with `Domain collection release metadata is invalid`; modern and malformed characterization cases retain their expected behavior. Failure cases assert original bytes remain unchanged.

#### Code Edit 2

- File: `ops/production/windows/modules/Production.DomainCollections.psm1`
- Lines: 298-318
- Action: replace
- Current:

```powershell
function Get-ProductionDomainCollectionReleaseSchema {
    param(
        [Parameter(Mandatory)][string]$Release,
        [Parameter(Mandatory)][string]$Sha
    )
    $metadataPath = Join-Path $Release 'release.json'
    try {
        $metadata = Get-Content -LiteralPath $metadataPath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        if ($metadata.sha -isnot [string] -or [string]$metadata.sha -cne $Sha -or
            $metadata.domainSchema -isnot [string] -or
            [string]$metadata.domainSchema -cnotin @('LEGACY','TARGET')) {
            throw 'Release metadata identity is invalid.'
        }
        return [string]$metadata.domainSchema
    } catch {
        throw [IO.InvalidDataException]::new(
            'Domain collection release metadata is invalid.', $_.Exception)
    }
}
```

- Proposed: Add private helpers that validate exact legacy/modern property order and values, open `app.jar` with `System.IO.Compression`, require the application class, classify the exact V015 entry, append `domainSchema` to an ordered copy, publish with `Write-ProductionDomainCollectionProtectedJson`, and re-read through the same validator.

```powershell
function Get-ProductionDomainCollectionReleaseSchema {
    param([Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$Release,
        [Parameter(Mandatory)][string]$Sha)
    # Validate exact metadata. Return an explicit modern schema unchanged.
    # Otherwise classify the exact executable JAR, atomically append the
    # property, re-read, and require the persisted value to match.
}
```

- Verification: Target and legacy RED cases pass; all malformed cases fail with original bytes intact; modern bytes and JAR access remain unchanged.

#### Code Edit 3

- File: `ops/production/windows/modules/Production.DomainCollections.psm1`
- Lines: 1034-1049
- Action: replace
- Current:

```powershell
Get-ProductionDomainCollectionReleaseSchema `
    -Release $targetPath -Sha $targetRelease
Get-ProductionDomainCollectionReleaseSchema `
    -Release $legacyPath -Sha $legacyRelease
```

- Proposed:

```powershell
Get-ProductionDomainCollectionReleaseSchema `
    -Config $Config -Release $targetPath -Sha $targetRelease
Get-ProductionDomainCollectionReleaseSchema `
    -Config $Config -Release $legacyPath -Sha $legacyRelease
```

Update focused direct-call tests and mocks to accept the mandatory `Config` boundary.
- Verification: Cutover context classifies/backfills target first and legacy second, then reaches backup/preview only when schemas are exactly `TARGET` and `LEGACY`.

- [ ] **Step 1: Write production-shaped failing tests**

Create historical target and legacy metadata/JAR fixtures, modern fixture, and fail-closed negative table.

- [ ] **Step 2: Run focused RED**

```powershell
Invoke-Pester -Path .\ops\production\windows\tests\Production.DomainCollections.Orchestration.Tests.ps1 -PassThru
```

Expected: historical target/legacy tests fail at the current missing `domainSchema` check; existing tests remain green.

- [ ] **Step 3: Implement minimal classification and atomic backfill**

Implement only the helpers and call-site parameter needed by the tests.

- [ ] **Step 4: Run focused and cross-host GREEN**

```powershell
Invoke-Pester -Path .\ops\production\windows\tests\Production.DomainCollections.Orchestration.Tests.ps1 -PassThru
powershell.exe -NoProfile -Command "Import-Module Pester -MinimumVersion 5.0; Invoke-Pester -Path '.\ops\production\windows\tests\Production.DomainCollections.Orchestration.Tests.ps1' -PassThru"
```

Expected: zero failures on PS7 and PS5.1.

- [ ] **Step 5: Commit**

```powershell
git add -- ops/production/windows/modules/Production.DomainCollections.psm1 ops/production/windows/tests/Production.DomainCollections.Orchestration.Tests.ps1
git commit -m "fix: classify historical domain releases"
```

### Task 2: Verify, publish, and finish consolidation

**Files:**
- Review: Task 1 diff and retained test evidence.
- Verify: full PowerShell, Node, Gradle, CI, protected preview/cutover logs.

**Interfaces:**
- Consumes: Task 1 commit.
- Produces: merged compatibility fix and completed guarded production consolidation or a fail-closed recoverable stop.

- [ ] **Step 1: Run regression gates**

Run full PS7 production Pester, changed-boundary PS5.1 Pester, PowerShell parsers, Node migration contracts, and `:cbell-lib:test :website:check :website:bootJar --no-daemon --rerun-tasks` with a private Gradle home.

- [ ] **Step 2: Independent review**

Require zero Critical/Important findings for exact metadata/JAR validation, atomic replacement, failure immutability, and pre-effect ordering.

- [ ] **Step 3: Push, CI, and merge**

Push `codex/domain-release-metadata-compatibility`, open a focused PR, require Windows/Linux/macOS, Dependency Review, and all CodeQL jobs green, then squash-merge.

- [ ] **Step 4: Protected read-only preview**

Run merged `prod.cmd mongo-consolidation-preview`; require `PREVIEWED`, exact manifest digest, 52 kinds, 14 targets, service Running on 8080, and 8081 unused.

- [ ] **Step 5: Guarded cutover**

Run merged `prod.cmd mongo-consolidate -ConfirmDomainCollectionCutover`. Require fresh backup and dry restore, isolated candidate, stopped-writer live proof, 52 allowlisted legacy drops, exact 14 collections / 52 kinds / 126 indexes, target service/runtime verification, and no candidate/process/root residue. Stop and preserve recovery evidence on any mismatch.

## Testing

- Focused orchestration Pester on PS7 and PS5.1.
- Full production PowerShell suite and parser checks.
- Node migration syntax/contracts.
- Full Gradle check and boot JAR.
- GitHub Windows/Linux/macOS, Dependency Review, CodeQL.
- Protected preview, cutover, database inventory, service/listener, and HTTP evidence.

## Code Changes

- Add exact historical/modern metadata validation and JAR classification helpers.
- Atomically append only the missing `domainSchema` property.
- Pass `Config` into the cutover release-schema boundary and add production-shaped tests.

## Files and Modules

- `Production.DomainCollections.psm1` owns classification and protected publication.
- `Production.DomainCollections.Orchestration.Tests.ps1` owns unit/integration-style PowerShell evidence.

## Unit Testing

Run focused orchestration Pester on PS7 and PS5.1, including all success and failure fixtures.

## Local Testing

Run protected preview from merged code, validate 52/14 evidence, then execute the existing confirmed cutover and verify listeners, services, HTTP, and exact Mongo inventory.

## Validation

Run full PS7 production Pester, changed-boundary PS5.1, parser checks, Node contracts, Gradle check/bootJar, independent review, and GitHub CI/CodeQL before production retry.

## Rollback or Recovery

Before merge, revert the implementation commit. A metadata-backfill failure leaves original bytes or a verified complete new file and blocks before Mongo effects. During cutover, use only the existing protected rollback command/state machine; never edit database, marker, state, or release metadata manually.

## Risks

- Misclassifying a non-executable ZIP is prevented by the required application class entry.
- Overwriting explicit schema is prevented by the modern-metadata early return.
- Partial metadata publication is prevented by protected temporary-file atomic replacement and readback.
- Backfill after writer effects is prevented by both classifications preceding backup, preview, candidate work, writer stop, publication, and deletion.

## Completion Criteria

- RED proves the two production-shaped historical metadata records fail before the fix.
- Historical target/legacy metadata is atomically made explicit; modern metadata is byte-identical; malformed inputs fail without mutation.
- Focused, cross-host, full, review, CI, and protected preview gates are green.
- Production ends with exact 14 collections, 52 kinds, and 126 indexes, or stops fail-closed with the service/data recoverable and no unauthorized deletion.
