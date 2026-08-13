# Domain Cutover Quiesced Snapshot Design

## Problem

The guarded domain-collection cutover creates a production backup and then
captures protected evidence while the legacy writer is still running. Lease
renewals can change documents between those two effects. The isolated candidate
then restores the backup and correctly fails `restore-verify` because its exact
checksums differ from the later evidence. Production recovery is safe, but the
cutover cannot reliably progress.

## Decision

Quiesce the production writer, with automatic recovery suspended, before the
verified backup and protected evidence are created. Keep the writer stopped
through candidate restore, migration, application verification, production
stage/publication, stopped-target re-verification, and legacy deletion. Start
the target writer only after deletion completes, as the existing cutover
contract already requires.

This deliberately accepts several minutes of one-time maintenance downtime in
exchange for one exact immutable snapshot across backup, evidence, candidate
proof, publication, deletion proof, and rollback.

## Recovery

Stopping the writer must produce an explicit recovery owner even if backup or
preview fails before a full cutover context exists. A pre-context failure must
restart the exact active legacy release, restore its prior schema marker, and
return website recovery policy to `Normal`. Once the protected PREVIEWED pair
exists, failures continue through the existing evidence-bound prepublication
recovery state machine. No failure before `DROP_STARTED` may restore the backup
or delete legacy data.

## Rejected Alternatives

- A live preflight snapshot followed by a second final snapshot reduces
  downtime but creates two evidence identities and a more complex crash/retry
  protocol.
- Ignoring or normalizing leases would weaken exact backup equivalence and is
  incompatible with the destructive migration contract.

## Verification

- A focused orchestration regression must fail against the current order and
  prove `stop-suspended` precedes `backup-and-evidence` and candidate work.
- A pre-context backup/preview failure regression must prove exact legacy
  restart, prior-marker preservation, recovery policy normalization, zero
  staging, and zero deletion.
- Candidate failure must prove the writer was already stopped and existing
  guarded prepublication recovery ran.
- Focused Pester must pass under PowerShell 7 and Windows PowerShell 5.1 with
  Pester 5.9; changed files must parse under both hosts.
- The disposable Mongo cutover matrix and the full relevant Windows suite must
  remain green before publication.
