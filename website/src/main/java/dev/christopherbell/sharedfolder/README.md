# Shared Folder

Owns the security boundary and conflict-safe read/write portal for the private shared-folder
feature.

## What Lives Here

- `fs` resolves only existing, ordinary descendants of the configured shared root using
  Windows-safe relative names. It rejects drive-qualified and UNC forms, alternate streams,
  dot segments, unsafe DOS names, all ISO control characters, links, NTFS reparse points, and
  filesystem mounts. On enabled Windows deployments, `WindowsSharedFolderReadBoundary` opens the
  configured root once with native `NtCreateFile`, retains that directory handle for its lifecycle,
  and opens every requested component relative to that handle with `OBJ_CASE_INSENSITIVE` and
  `OBJ_DONT_REPARSE`. Directory entries are enumerated from the already opened handle; native file
  streams compare `FileIdInfo` (volume serial plus 128-bit file ID) between metadata probe and the
  later stream open. There is no path-based NIO read fallback in that Windows mode. A fair JVM
  lifecycle lock prevents the root handle from closing during an in-process traversal, but the
  held native root is the security boundary; application locks and ACLs are defense in depth only.
  Non-Windows test/local providers retain the portable NIO resolver for reads and private upload
  staging only; deployable visible writes fail with `503` without a retained mutation capability.
- `security` reloads the authenticated account from MongoDB for every decision. A persisted
  active approved account needs a shared-folder capability; ADMIN has read and write implicitly,
  and write implies read. JWTs intentionally carry no shared-folder capability.
- `audit` persists bounded, expiring operation events through a best-effort sink. Request and
  service boundaries inject the recorder for accepted work, safe rejection categories, logical
  range deduplication, scheduled recovery, and administrator queries.
- `model`, `service`, `upload`, and `web` provide authenticated directory listings,
  disk-streamed full or single-range downloads, safe previews, conflict-safe mutations, and
  resumable uploads. `web` accepts each path after Spring has decoded it once and passes that
  value unchanged to the resolver; response models never include a local absolute path.

## Configuration

`app.shared-folder` binds the roots, upload and cache limits, retention periods, and enablement
flag. Local and test profiles use build-owned paths; production roots use environment-overridable
`A:/Shared` and `A:/Shared-System` defaults.

Both configured roots must exist before the application starts. The system root is an ordinary,
non-linked, non-reparse, non-mount directory on the same filesystem as the visible root; the
application deliberately does not create that configured root. Deployment grants the website
service identity access to the pre-created root. The application may create only its validated
direct staging and quarantine children beneath it. Portable private create/open/delete operations
capture canonical path, file identity, filesystem, mount, link, and reparse facts for every
ancestor and recheck them around the operation. Portable private-to-visible and visible-to-private
moves are unavailable because a Java pathname move cannot bind the transitioned leaf to the
earlier observation. An unavailable or changed private boundary fails with `503 Service
Unavailable` and never falls back to an unchecked production path.

## Read-Only Portal

- `GET /shared` is a public, data-free HTML shell only. It contains no folder data and the browser
  redirects unauthenticated visitors to login before making any protected read request.
- Every `/api/shared-folder/2026-07-17/**` route first reloads the authenticated account through
  `SharedFolderAccessService.requireRead()`. Revoked, inactive, unapproved, or missing accounts
  are denied before a filesystem service runs. The exact versioned API prefix sets
  `Cache-Control: private, no-store` before security or controller handling, so successful reads,
  range/HEAD responses, and protected errors cannot be retained by browser or intermediary caches.
- `GET /entries` returns only decoded relative paths and ordinary-entry metadata. `GET /content`
  uses a revalidating disk-backed resource plus `ResourceRegion` for disk streaming and supports
  exactly one HTTP byte range, including correct `206`, `416`, `Content-Range`, `Accept-Ranges`,
  and `HEAD` semantics. It never calls `readAllBytes` or exposes an absolute local path.
- `GET /preview` returns bounded UTF-8 text as JSON for text files; allowlisted raster image,
  audio, video, and PDF types stream inline. HTML, SVG, and every unknown type stay attachment
  only. Native media `Range` requests are preserved through to Spring's resource streaming.
  Previews send `nosniff`; PDFs additionally send a restrictive sandbox CSP. Download and preview
  filenames use framework-built RFC 5987 content-disposition values.

## Conflict-Safe Writes

- Folder creation, rename, move, and delete reload the account with `requireWrite()` for every
  operation. ADMIN has write access implicitly; other accounts need the persisted shared-folder
  write capability. The versioned API remains `private, no-store` for successful writes and
  errors.
- Every existing source is addressed with an opaque observed token from a fresh listing. A stale
  source, collision, or changed replacement target returns `409 Conflict`. Replacing an existing
  destination is never implicit: the client must opt in and supply that destination's current
  observed token. Providers without a retained leaf mutation capability return `503` before any
  visible transition; they do not attempt a portable pathname move/delete.
- Enabled Windows deployments perform create, write, flush, truncate, rename, move, and delete
  with retained native directory handles and `NtCreateFile`/`NtSetInformationFile`. Opens are
  relative and use `OBJ_DONT_REPARSE`; source and replacement-target file IDs are rechecked while
  held. Mutations have no path-based NIO fallback in native mode. Volume capacity is queried from
  the retained system-root handle and arithmetic overflow fails closed.
- Portable mutation coordination remains covered by an explicit test-only harness. Spring
  production construction never supplies that marker, so it is not a deployable mode.

## Resumable Uploads

- Upload sessions are owner-scoped, optimistic-versioned Mongo records. Private staging lives
  under the configured system root, never the visible shared root, and finalization is a
  same-volume handle-relative rename on the retained native boundary. Without that boundary,
  completion returns `503` while retaining private staging for a later supported deployment.
  Ordered chunks are streamed to disk, SHA-256 checked, and
  idempotent at the recorded offset; concurrent append/terminal operations serialize around the
  durable session state.
- The browser sends 8 MiB chunks, shows byte progress, supports cancel and drag/drop, and can
  resume a matching local file after refresh. Browser resume storage contains only the session
  identifier and non-secret file metadata—never an observed token or bearer credential. A
  case-insensitive resume match still re-hashes every committed prefix chunk before trusting local
  bytes. Explicit replacement uses the canonical spelling returned by the server listing.
- Creating an upload session is intentionally attempted once because POST is not idempotent; an
  ambiguous failure may leave only an owner-scoped expiring session. Chunk PUT, status, complete,
  and cancellation retain their bounded retry/reconciliation behavior.
- The configured per-file maximum returns `413 Payload Too Large`; insufficient capacity after
  the configured reserve returns `507 Insufficient Storage`. Finalization and cancellation use
  durable `FINALIZING` and `CANCEL_PENDING` phases so a database save failure after the physical
  operation can be reconciled from stable file identity instead of duplicating or losing work.
  Terminal sessions report `COMPLETED`, `CANCELLED`, or `EXPIRED` and cannot accept more chunks.

## Update This Doc

Update this README when resolver or native-handle guarantees, effective access rules, mutation
conflict rules, upload state, audit fields, or shared-folder configuration changes.
