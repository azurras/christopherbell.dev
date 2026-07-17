# Task 4 Review Fix Design

Date: 2026-07-17

Status: Approved for implementation

## Goal

Close every finding from the independent review without weakening fresh authorization, owner
scope, relative-path-only responses, or the native Windows no-NIO-fallback boundary.

## Native containment

Mutation traversal returns an owned root-to-leaf handle chain instead of closing ancestors as each
child opens. The mutation boundary's retained visible and system root handles and every descendant
handle in a mutation chain use mutation-specific share semantics that omit delete sharing. Source,
destination, and observed replacement chains remain live through the final native operation. A
sharing violation or incompatible external handle maps to conflict. The Task 3 read bridge remains
unchanged except for any interface signature needed by both modes.

## Conditional replacement and recovery

Replacement never calls an unconditional overwrite primitive. The observed destination is pinned,
renamed by its held handle to a random private quarantine name, and the source or staging file is
then moved to the empty final name with replacement disabled. A failed second move restores the
quarantined object before returning. Upload session state persists quarantine and finalization
metadata so a recreated service can reconcile interrupted replacement. Quarantine keys are random,
validated, private, owner/session bound, and removed after successful commit. Recovery examines
stable identity and fails closed on ambiguity. Portable providers use explicit no-replace moves and
only support conditional replacement where same-volume quarantine and create-new semantics can be
verified; unsupported guarantees return 503.

One-shot mutation replacement has an owner-scoped bounded Mongo recovery journal with PREPARED,
TARGET_QUARANTINED, SOURCE_MOVED, and RESTORE_PENDING phases. The journal stores only validated
relative paths, random private keys, and stable identities. Startup and every mutation reconcile
unfinished records. If an external racer occupies the final name and blocks restoration, recovery
preserves the quarantined target and remains RESTORE_PENDING rather than overwriting either object.

## Durable upload append and lifecycle

Upload sessions gain a durable pending-append transition containing offset, length, digest,
request/instance lease token, and bounded lease expiry. The request stages and verifies its bounded
chunk before acquiring the optimistic-version lease, keeping the physical append window short.
Only the owning token may complete or immediately reconcile the append. Another instance must wait
for lease expiry; after expiry it truncates partial or full uncommitted bytes to the persisted
committed offset before accepting a retry. No JVM-global monitor is used, and a live writer's
in-flight or just-committed bytes cannot be truncated by a competing instance.

Expiry reconciliation is shared by status, append, and complete. Expired ACTIVE sessions become
EXPIRED before any finalization. Cancellation remains CANCEL_PENDING until private staging deletion
is confirmed; later status/cancel calls retry and reconcile deletion.

## Error and validation contract

Service entry points validate all request objects and fields, forbid root mutation, and reject
non-positive uploads before filesystem or repository I/O. Observation helpers require fresh read
authorization. Domain failures map consistently in native and portable modes:

- 400: malformed or unsafe input
- 404: valid relative item/session not found
- 409: stale observation, collision, incompatible external handle, or state conflict
- 413: configured upload/chunk limit exceeded, including unknown-length streams
- 503: native/provider boundary unavailable or unable to guarantee the operation
- 507: configured storage reserve cannot be preserved

Error responses retain the application's bounded safe envelope and never expose host paths.

## Browser workflow

Create/status responses advertise the server chunk size and committed chunk offset/length/digest
metadata needed for resume. The browser re-hashes every already committed local chunk and compares
it with the server record before resuming, so a different same-name/same-size file cannot splice
bytes. New chunks are hashed as they upload; no whole-file pre-read is required.

One operation gate prevents submit/drop double execution. The active operation owns an
AbortController. Pause aborts the current request while preserving the session; resume continues
after prefix proof. Cancel aborts first and then requests durable server cancellation. Transient
network and 5xx failures retry with bounded backoff; validation, authorization, conflict, and
payload errors do not retry. Explicit replacement remains dependent on the observed target token.

## Verification

Each review item begins with a focused failing regression test. Final verification runs focused
shared-folder tests, real Windows native and explicitly enabled junction tests, full Java tests,
all JavaScript tests, touched JavaScript syntax checks, diff checks, and cache hygiene using
external Gradle user and project caches.

## Second independent re-review remediation

The second independent review of commit `7975b9e8e0818ffaca7100c03042538f35e046b7`
identified four Critical and four Important concurrency and replacement-integrity gaps. Durable
mutation journals and upload finalization records therefore carry per-writer lease tokens and
bounded expiries. Startup, status, complete, and pre-operation recovery skip live leases; an expired
record must be claimed through its Mongo optimistic version before physical reconciliation. The
writer refreshes its lease at each durable physical phase, and only the exact ended writer token may
be expired during ambiguous-save reconciliation.

Portable replacement identity is content-sensitive for ordinary files (stable metadata plus
SHA-256) and requires replacement directories to be empty. Identity and directory emptiness are
checked before displacement, immediately after quarantine, and once more after the physical-phase
test seam before source/staging movement. An observed replacement target that disappears is a 409
with source or staging intact in portable and native modes. Case-only portable rename is one atomic
rename attempt and fails closed when the provider cannot guarantee it; no visible UUID intermediate
name exists.

Append validates id, non-negative offset, body, and a required 32-byte base64url SHA-256 digest as
400 before authorization, repository, or native-boundary work. A final progress save that commits
and then throws is resolved by reloading durable ACTIVE chunk proof and never truncating committed
bytes. Durable APPENDING is reconciled only when the exact matching lease has been proven and first
expired through an optimistic save. Native missing, collision/share, and unknown failures map to
404, 409, and 503 respectively through real service tests.
