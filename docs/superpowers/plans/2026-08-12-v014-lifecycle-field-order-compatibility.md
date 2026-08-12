# V014 Lifecycle Field-Order Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make guarded domain-collection preview accept the exact durable V014 migration-record order observed in production while retaining the exact fresh-converter order and rejecting every other shape.

**Architecture:** Keep compatibility at the mongosh storage-validation boundary. The real-Mongo fixture will exercise both approved lifecycle orders; the production validator will compare ordered keys against two immutable literal sequences before applying all existing value and BSON-type checks unchanged.

**Tech Stack:** mongosh JavaScript, Node.js test contracts, PowerShell/Pester disposable Mongo harness, Gradle/Java 25 release checks.

## Global Constraints

- Accept only `_id, checksum, description, status, ownerToken, startedAt, completedAt, _class` and `_id, checksum, description, status, ownerToken, startedAt, _class, completedAt`.
- Preserve the exact V014 ID, checksum, description, `APPLIED` status, non-empty owner token, BSON Date types, `_class`, and `music_runtime_state` existence checks.
- Do not mutate or normalize the production migration record.
- Do not change command arguments, redacted results, manifest digest, ledger, backup, cutover, rollback, or deletion behavior.
- No production cutover or deletion is permitted until the merged fix passes the guarded read-only preview.

---

## Document Status

ready-for-execution

## Objective

Restore guarded production preview compatibility with the exact durable V014 migration record without weakening any other migration authority or cutover invariant.

## Goals

1. Reproduce the production lifecycle order through the real Mongo engine boundary.
2. Accept exactly the fresh-converter and durable-completion orders.
3. Preserve every existing V014 value, BSON type, class, and source-authority check.
4. Merge the reviewed fix and require a successful read-only production preview before cutover.

## Inputs

- Approved design: `docs/superpowers/specs/2026-08-12-v014-lifecycle-field-order-compatibility-design.md`
- Production observation: `_id, checksum, description, status, ownerToken, startedAt, _class, completedAt`
- Merged consolidation base: `origin/main` at `2b38e4be6071d8ec5c692beb21e996ba4fa2ea57`
- Existing disposable Mongo harness and protected Windows production command.

## Branch

- Base: `origin/main`
- Feature: `codex/domain-v014-lifecycle-order`
- Isolated worktree: `A:\Projects\christopherbell.dev-worktrees\domain-collection-consolidation`

## Non-Goals

- No production migration-record rewrite or normalization.
- No manifest, ledger, envelope, index, startup-gate, backup, cutover, rollback, or public API change.
- No production mutation before the fixed preview passes after merge.

## Assumptions

- The two witnessed orders correspond to fresh conversion and durable completion update.
- MongoDB preserves top-level BSON field insertion order for these records.
- The existing marker-owned disposable harness remains isolated from production.

## Open Questions

None. Any third V014 order or value/type mismatch remains invalid and blocks preview.

## Before-Edit Brief

- **Behavior:** The read-only preview accepts the exact durable production V014 record and the exact fresh-converter record.
- **Invariants:** Only those two ordered key sequences are allowed; all existing field/value/type/source checks remain exact.
- **Boundary/API:** The mongosh `assertV014Authority` storage boundary changes without changing migration commands or results.
- **Effects and failures:** Validation remains read-only and returns the existing redacted failure for every unapproved record.
- **Tests and evidence:** The real-Mongo fixture supplies RED/GREEN evidence; Node, Pester, Gradle, independent review, CI, and protected preview supply completion evidence.

## Task Breakdown

### Task 1: Prove and implement the two exact lifecycle orders

Required skill: invoke `write-jane-street-style-code` and `superpowers:test-driven-development` before the code edits below.

**Files:**
- Modify: `ops/production/windows/tests/domain-collection-migration.mongo.js:73-111,302-306`
- Modify: `ops/production/windows/scripts/Invoke-DomainCollectionMigration.js:352-365`

**Interfaces:**
- Consumes: `DomainCollectionMigration.execute(rootDatabase, args)` and the existing `seed(databaseName)` real-Mongo fixture.
- Produces: unchanged migration-engine result JSON; V014 authority accepts exactly two ordered key sequences.

#### Code Edit 1

- File: `ops/production/windows/tests/domain-collection-migration.mongo.js`
- Lines: 73-111
- Action: replace

Current:

```javascript
function seed(databaseName) {
  // The V014 fixture places completedAt before _class.
}
```

Proposed:

```javascript
function seed(databaseName, v014Order = "durable") {
  assert(["durable", "fresh"].includes(v014Order), "V014 fixture order is invalid");
  // Construct the exact selected literal order; durable is the default.
}
```

Verification: The disposable harness fails before production code changes because durable V014 preview is rejected, then passes after the validator change; the dedicated V014 database uses `fresh` and still previews before its checksum-negative assertion.

#### Code Edit 2

- File: `ops/production/windows/tests/domain-collection-migration.mongo.js`
- Lines: 302-306
- Action: replace

Current:

```javascript
const v014 = seed(databases.v014);
```

Proposed:

```javascript
const v014 = seed(databases.v014, "fresh");
command(databases.v014, "preview");
```

Verification: The fresh order succeeds before the checksum is changed; the subsequent wrong-checksum preview remains a controlled failure.

#### Code Edit 3

- File: `ops/production/windows/scripts/Invoke-DomainCollectionMigration.js`
- Lines: 352-365
- Action: replace

Current:

```javascript
const exactKeys = ["_id", "checksum", "description", "status", "ownerToken", "startedAt",
  "completedAt", "_class"];
if (!migration || !sameValue(Object.keys(migration), exactKeys)
    || migration.checksum !== V014_CHECKSUM || migration.description !== V014_DESCRIPTION
    || migration.status !== "APPLIED" || typeof migration.ownerToken !== "string"
    || migration.ownerToken.length === 0 || !(migration.startedAt instanceof Date)
    || !(migration.completedAt instanceof Date) || migration._class !== MIGRATION_RECORD_TYPE) {
  fail("Mongo V014 authority is absent or malformed.");
}
```

Proposed:

```javascript
const v014KeyOrders = Object.freeze([
  Object.freeze(["_id", "checksum", "description", "status", "ownerToken", "startedAt",
    "completedAt", "_class"]),
  Object.freeze(["_id", "checksum", "description", "status", "ownerToken", "startedAt",
    "_class", "completedAt"])
]);
const hasExactKeys = migration
  && v014KeyOrders.some((keys) => sameValue(Object.keys(migration), keys));
if (!hasExactKeys
    || migration.checksum !== V014_CHECKSUM || migration.description !== V014_DESCRIPTION
    || migration.status !== "APPLIED" || typeof migration.ownerToken !== "string"
    || migration.ownerToken.length === 0 || !(migration.startedAt instanceof Date)
    || !(migration.completedAt instanceof Date) || migration._class !== MIGRATION_RECORD_TYPE) {
  fail("Mongo V014 authority is absent or malformed.");
}
```

Verification: Both literal orders pass through `execute`; arbitrary order, extra/missing fields, and every existing wrong value/type remain rejected.

- [ ] **Step 1: Write the failing durable-record regression**

Change the default V014 fixture so `_class` is inserted before `completedAt`, matching the production lifecycle:

```javascript
document = {
  _id: "014-consolidate-music-runtime-state",
  checksum: "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb",
  description: "Consolidate Music queue and radio runtime state",
  status: "APPLIED",
  ownerToken: "v014-owner",
  startedAt: ISODate("2026-08-10T00:00:00.000Z"),
  _class: "dev.christopherbell.configuration.mongo.migration.MigrationRecord",
  completedAt: ISODate("2026-08-10T00:01:00.000Z")
};
```

Add a `v014Order` fixture mode and seed the dedicated V014 negative database with the fresh order before changing its checksum. This keeps both legitimate orders under the executable engine boundary.

- [ ] **Step 2: Run the disposable Mongo harness to verify RED**

Run:

```powershell
Import-Module Pester -MinimumVersion 5.0
& .\ops\production\windows\tests\Invoke-DomainCollectionDisposableMongoTest.ps1
```

Expected: FAIL during preview with `Mongo V014 authority is absent or malformed` because the durable order is not yet accepted. Verify the harness stops its exact mongod PID and removes its marker-owned root.

- [ ] **Step 3: Implement the minimal exact compatibility check**

Replace the single `exactKeys` literal with two frozen orders and an exact ordered comparison:

```javascript
const v014KeyOrders = Object.freeze([
  Object.freeze(["_id", "checksum", "description", "status", "ownerToken", "startedAt",
    "completedAt", "_class"]),
  Object.freeze(["_id", "checksum", "description", "status", "ownerToken", "startedAt",
    "_class", "completedAt"])
]);
const hasExactKeys = migration
  && v014KeyOrders.some((keys) => sameValue(Object.keys(migration), keys));
if (!hasExactKeys
    || migration.checksum !== V014_CHECKSUM || migration.description !== V014_DESCRIPTION
    || migration.status !== "APPLIED" || typeof migration.ownerToken !== "string"
    || migration.ownerToken.length === 0 || !(migration.startedAt instanceof Date)
    || !(migration.completedAt instanceof Date) || migration._class !== MIGRATION_RECORD_TYPE) {
  fail("Mongo V014 authority is absent or malformed.");
}
```

The final function preserves each existing predicate exactly once.

- [ ] **Step 4: Run focused GREEN and contract checks**

Run the disposable harness again and require 3/3 with zero skips. Then run:

```powershell
node --check .\ops\production\windows\scripts\Invoke-DomainCollectionMigration.js
node --check .\ops\production\windows\tests\domain-collection-migration.mongo.js
node --test .\ops\production\windows\tests\domain-collection-migration.test.js
```

Expected: disposable Mongo 3/3; Node contracts 9/9; syntax exit 0.

- [ ] **Step 5: Commit the cohesive fix**

```powershell
git add -- ops/production/windows/scripts/Invoke-DomainCollectionMigration.js `
  ops/production/windows/tests/domain-collection-migration.mongo.js
git commit -m "fix: accept durable V014 migration records"
```

### Task 2: Verify, review, publish, and retry preview

**Files:**
- Review: `ops/production/windows/scripts/Invoke-DomainCollectionMigration.js`
- Review: `ops/production/windows/tests/domain-collection-migration.mongo.js`
- Verify: repository-wide build/test outputs and protected preview logs

**Interfaces:**
- Consumes: Task 1 commit and existing production command `prod.cmd mongo-consolidation-preview`.
- Produces: reviewed merged fix and a successful protected preview result; no mutating action.

- [ ] **Step 1: Run regression gates**

Run:

```powershell
$env:GRADLE_USER_HOME = 'A:\Projects\christopherbell.dev-worktrees\domain-collection-consolidation\.gradle-v014-fix'
.\gradlew.bat :cbell-lib:test :website:check :website:bootJar --no-daemon --rerun-tasks
Import-Module Pester -MinimumVersion 5.0
Invoke-Pester -Path .\ops\production\windows\tests -PassThru
```

Require zero failures/errors, record guarded skips, and confirm `website.jar` exists.

- [ ] **Step 2: Apply the review rubric**

Inspect the production/test diff together. Confirm the two arrays are the only accepted orders; arbitrary reordering, missing/extra keys, wrong values/types, and missing authoritative collection still fail. Request independent read-only review and require zero Critical/Important findings.

- [ ] **Step 3: Push and merge through CI**

Push `codex/domain-v014-lifecycle-order`, open a focused PR to `main`, wait for Windows/Linux/macOS builds, dependency review, and CodeQL to reach terminal success, then squash-merge.

- [ ] **Step 4: Retry the protected read-only preview**

From the merged tree, launch elevated:

```powershell
.\prod.cmd mongo-consolidation-preview
```

Require exit 0, action `preview`, state `PREVIEWED`, the exact manifest digest, 52 kind metrics, 14 index metrics, and protected evidence. Confirm production port 8080 remains listening, candidate port 8081 remains unused, and no service/database/marker/backup mutation occurred.

- [ ] **Step 5: Continue only through the guarded cutover boundary**

After successful preview, run the existing exact-confirmation cutover workflow. It owns the fresh checksummed backup, dry restore, isolated candidate, stopped-writer live proof, one-at-a-time legacy drops, exact 14/52/126 verification, target start, public/runtime verification, and rollback evidence. Stop fail-closed on any mismatch.

## Code Changes

- One executable fixture gains an explicit two-case V014 lifecycle-order mode.
- One production validator accepts two exact ordered key sequences.
- No other production behavior changes.

## Files and Modules

- `ops/production/windows/tests/domain-collection-migration.mongo.js` — real-Mongo V014 lifecycle fixtures.
- `ops/production/windows/scripts/Invoke-DomainCollectionMigration.js` — protected V014 authority validation.
- `docs/superpowers/specs/2026-08-12-v014-lifecycle-field-order-compatibility-design.md` — approved design.

## Unit Testing

- `node --check` both changed JavaScript files.
- `node --test ops/production/windows/tests/domain-collection-migration.test.js` must pass 9/9.

## Local Testing

- `Invoke-DomainCollectionDisposableMongoTest.ps1` must pass 3/3 with zero skips and remove its exact process/root.
- The elevated production `mongo-consolidation-preview` must return `PREVIEWED` without service, marker, backup, or database mutation.

## Validation

- Run full `:cbell-lib:test :website:check :website:bootJar`.
- Run the complete PS7 production Pester suite and changed-boundary PS5.1 suite.
- Require independent review with zero Critical/Important findings and terminal green GitHub CI/CodeQL.

## Rollback or Recovery

Before merge, revert the single fix commit if either approved order or any malformed negative fails. After merge, a failed preview remains read-only and blocks cutover; no database rollback is required. No direct production-record edit is permitted.

## Risks

- Accepting a broad field set would weaken authority; exact ordered literals prevent that.
- A fixture-only test could miss production behavior; the real mongosh/Mongo harness and protected preview close that gap.
- Running cutover before merged preview would be unsafe; the plan explicitly gates mutation on preview success.

## Completion Criteria

- RED is observed against the durable production order.
- Both approved orders pass and malformed orders/values/types fail.
- Focused, full, cross-host, disposable-Mongo, review, and CI gates are green.
- Merged protected preview returns exact 52-kind/14-target evidence.
- The guarded cutover completes with exactly 14 collections and 126 indexes, or stops fail-closed with production unchanged/recoverable.
