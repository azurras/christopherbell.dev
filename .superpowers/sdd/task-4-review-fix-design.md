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

## Third independent re-review remediation

The third whole-change review of `fc7847a06276897ea42f12b79d85942b55177436`
identified four Critical, four Important, and one Minor issue. Native visible mutation leaves now
need a bridge open mode that requests mutation access while sharing read only. Root and ancestor
handles retain their existing read/write sharing and delete denial so traversal stays compatible,
but the final source and observed-target leaf deny both write and delete sharing from the last
metadata recheck through rename, quarantine, or deletion. Native durable replacement and upload
replacement use those leaf capabilities, so equal-size external writes and child creation cannot
invalidate identity after validation.

Leases are renewable capabilities rather than fixed delays. Mutation recovery and upload session
repositories expose atomic `@Query`/`@Update` renewal methods keyed by id, exact token, state, and
current phase; renewal changes expiry and update time without incrementing `@Version`, so a writer's
entity version remains usable. Content-digest loops renew periodically, and every physical
transition first fences on a successful renewal. Losing the token stops the old writer before the
next physical action. Expired APPENDING recovery first changes the exact expired lease token to a
new recovery token through optimistic save, then fences/renews that claim before truncation or
private-chunk deletion; a stale reconciler never touches bytes after another instance restores
ACTIVE or starts a new append.

Portable case-only rename first establishes whether the differently spelled name is the same
object. A case-insensitive provider may perform one atomic same-object rename. A case-sensitive
provider treats a differently cased existing object as a normal collision and uses one strict
no-replace move when the target is absent; it never uses an atomic-move option that providers may
interpret as overwrite. Browser replacement adopts the server-listed canonical target name and
path, persists that spelling for resume, and still proves every committed prefix chunk before a
case-insensitive local-name match. Mutation payloads use the canonical listed replacement name.

Portable private storage requires a pre-created ordinary system root. A dedicated private-root
boundary captures canonical, file-key, file-store, link/reparse, and mount facts for every existing
ancestor and the root, rejects unsafe providers, creates only validated direct children without
following links, and rechecks the full identity chain immediately before and after every staging or
quarantine create/open/move/delete. Visible-root failures and private-root failures are classified
as missing (404), conflict (409), or unavailable/unsafe (503) rather than folded into 409. Native
create, append, complete, and cancel use the same exact NTSTATUS classifier; status zero and unknown
statuses are 503 unless the operation itself explicitly created a semantic conflict.

Private regular-file contents are never exposed to upload or mutation services as raw paths. The
portable boundary opens them with `NOFOLLOW_LINKS`, requires a stable provider identity and a link
count of one, retains a channel and exclusive write lock through the operation, and rechecks the
leaf and ancestor identities afterward. Symlinks, Windows reparse points, hardlinks, unsupported
providers, and mid-operation name substitution fail closed. Boundary-owned move and delete methods
validate the private leaf before and after no-replace transitions. The pre-created system root is a
service-private trust boundary and must grant mutation access only to the website service identity;
Windows production additionally uses retained native handles and denies write/delete sharing on
mutation file handles.

The browser does not retry upload-session POST. A single ambiguous create may leave an owner-scoped
private orphan that expires normally; chunk PUT, status GET, and idempotent complete behavior keep
their bounded transient retry policy.

## Fifth independent re-review remediation

The fifth whole-change review of `09a0e7669c80e3c025137352caefbd4c62527495`
identified three Critical and four Important issues. Atomic expired-lease claims now increment the
Mongo `@Version`, and every claimant reloads and proves the claimed token, state, phase, and offset
before touching physical data. A stale ordinary save therefore cannot overwrite a recovery claim
or commit progress after recovery truncates uncommitted bytes.

Portable private leaves use provider-backed stable identities only; metadata fingerprints are not
treated as object identity. On Windows, the portable fallback retains native parent and leaf
handles and performs file-channel operations through the held leaf handle, denying concurrent
write, delete, and rename. Create-new explicitly captures the identity of the created named leaf.
Move-out validation removes any substituted unsafe leaf from the visible destination before
reporting failure, preserving outside content and preventing an unsafe visible artifact.

Explicit replacement creation treats an observed target that disappears as 409 in portable and
native paths. Native private-directory initialization creates only after an exact missing status;
unknown or unavailable opens fail closed. Append reserve checks include both staging growth and the
temporary chunk copy, with overflow treated as an unreservable peak.

## Sixth independent re-review remediation

The sixth whole-change review of `14f00cf26b68d0cec1af2248ca6f157324bdac3b`
identified three Critical, one Important, and one Minor issue, all rooted in deployable portable
pathname mutation. No Java provider contract can bind a later pathname move/delete to the exact
observed leaf or guarantee an atomic no-replace transition after the final identity check.

Deployable visible writes therefore require the retained native Windows mutation boundary. Folder
creation, rename, move, delete, upload finalization, and finalization recovery return 503 before a
visible transition when that capability is unavailable. Portable reads and private upload staging,
append, status, and cancellation remain supported. Legacy portable mutation state-machine tests use
an explicit test-only marker that Spring production construction never supplies; those tests do not
represent a deployable provider capability.

Portable private move-in/move-out is likewise unavailable outside that test-only state-machine
harness, eliminating path-based journal transitions and post-move cleanup of an unowned visible
racer. The custom retained Windows file channel stops transfer operations on legal zero progress
instead of spinning.
