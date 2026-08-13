# Domain Cutover Quiesced Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the guarded domain-collection cutover take its backup, protected evidence, and candidate proof from one writer-quiesced snapshot.

**Architecture:** Move writer quiescence into cutover-context initialization, immediately before the verified backup. Add an initialization-failure recovery seam that either reconciles an exact committed PREVIEWED pair through the existing state machine or restores and restarts the exact prior legacy release when no pair committed. Remove the later duplicate stop while retaining the fixed-root recheck before production migration.

**Tech Stack:** PowerShell 7/Windows PowerShell 5.1, Pester 5.9, MongoDB migration scripts, native Windows service orchestration.

## Global Constraints

- Never weaken `C:\ProgramData\christopherbell.dev` ACLs.
- Hold the existing fixed-root `deploy.lock` for the entire cutover and recovery sequence.
- Do not stage, publish, rename, drop, or restore production data before the exact stopped snapshot and candidate proof succeed.
- Preserve the current target-writer rule: target startup occurs only after legacy deletion completes.
- Any pre-`DROP_STARTED` failure must restore the legacy writer without restoring the backup or deleting legacy collections.
- Use the existing protected state, marker, fixed-root, and release-switch boundaries; add no ad hoc service/process commands.

---

### Task 1: Quiesce the exact snapshot and recover initialization failures

**Files:**
- Modify: `ops/production/windows/tests/Production.DomainCollections.Orchestration.Tests.ps1:378-515`
- Modify: `ops/production/windows/modules/Production.DomainCollections.psm1:1169-1237`
- Modify: `ops/production/windows/modules/Production.DomainCollections.psm1:1792-1846`
- Modify: `docs/operations/windows-production.md`
- Modify: `docs/operations/mongodb-migrations.md`

**Interfaces:**
- Consumes: `Stop-ProductionDomainCollectionWriter`, `Resolve-ProductionDomainCollectionPrepublicationPublication`, `Read-ProductionDomainSchemaDirection`, `Read-ProductionDomainCollectionProtectedState`, `Invoke-ProductionDomainCollectionFailureRecovery`, `Restore-ProductionDomainCollectionLegacyRelease`, `Start-ProductionDomainCollectionLegacy`, and `Set-ProductionWebsiteRecoveryPolicy`.
- Produces: `Restore-ProductionDomainCollectionSnapshotInitializationFailure -Context <pre-snapshot context>` and a `New-ProductionDomainCollectionCutoverContext` result whose `writerStopped` field is always `$true`.

- [ ] **Step 1: Write the failing orchestration tests**

Add behavior tests that independently require:

```powershell
$script:events.IndexOf('stop-suspended') | Should -BeLessThan `
    $script:events.IndexOf('backup-and-evidence')
```

and that make `New-ProductionDomainCollectionVerifiedBackup` throw after the
real context initializer stops the writer, then assert one exact legacy
restart, `Normal` recovery policy, zero preview/publication, zero staging, and
zero deletion. Update the candidate-failure regression to require the writer
was already stopped and that prepublication recovery still runs once.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
Import-Module Pester -MinimumVersion 5.0 -ErrorAction Stop
Invoke-Pester -Path ops/production/windows/tests/Production.DomainCollections.Orchestration.Tests.ps1 -Output Detailed
```

Expected: the new order assertion fails because `backup-and-evidence` precedes
`stop-suspended`; the initialization-failure test fails because the legacy
restart boundary is not invoked.

- [ ] **Step 3: Implement the minimal quiescence and recovery change**

In `New-ProductionDomainCollectionCutoverContext`, after exact target/legacy
release validation and prior-marker capture, construct the minimal recovery
context, call `Stop-ProductionDomainCollectionWriter`, and only then call
`New-ProductionDomainCollectionVerifiedBackup` and preview. Set
`writerStopped = $true` in the full context.

Add `Restore-ProductionDomainCollectionSnapshotInitializationFailure` with
this closed behavior:

```powershell
Resolve-ProductionDomainCollectionPrepublicationPublication -Config $Context.config
$marker = Read-ProductionDomainSchemaDirection -Config $Context.config
if ($marker -and [string]$marker.state -ceq 'ROLLBACK_IN_PROGRESS') {
    $state = Read-ProductionDomainCollectionProtectedState -Config $Context.config
    Invoke-ProductionDomainCollectionFailureRecovery -Context $state -PostDrop:$false
    return
}
Restore-ProductionDomainCollectionLegacyRelease -State $Context
Start-ProductionDomainCollectionLegacy -State $Context
Set-ProductionWebsiteRecoveryPolicy -Policy Normal
```

Wrap the post-stop context initialization in `try/catch`; preserve the original
failure unless recovery also fails, in which case throw an `AggregateException`
with both causes. Remove the later stop at the orchestration call site.

- [ ] **Step 4: Run focused GREEN and compatibility checks**

Run the orchestration suite under PowerShell 7 and Windows PowerShell 5.1 with
the explicit Pester 5.9 manifest. Parse the changed PowerShell files under both
hosts. Run the domain command, deployment, writer-start, and operations suites
that share the marker/service boundary.

Expected: zero failures; ordering proves writer stop before backup and no
duplicate stop.

- [ ] **Step 5: Run real migration and full Windows verification**

Run the marker-owned disposable Mongo harness and the full PowerShell 7
production suite. Confirm the harness reports the full 52-kind, 126-index,
14-target, 52-drop, and 468-boundary matrix with zero owned process/root residue.

- [ ] **Step 6: Review, document, commit, and publish**

Update both operations documents to state that maintenance downtime starts
before the protected backup and evidence snapshot. Run `git diff --check`,
request independent review, commit the cohesive change, push the branch, open a
PR, wait for required CI, merge, refresh the isolated worktree to merged main,
then rerun the guarded production cutover and exact HTTP/service/database
verification.
