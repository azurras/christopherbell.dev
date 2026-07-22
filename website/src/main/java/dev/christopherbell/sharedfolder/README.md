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
  staging only. On macOS, the portable boundary rejects filesystem roots directly and detects
  nested mounts through the parent/child file-store comparison. Operating-system aliases above
  the configured root are allowed after canonical capture; the configured root and every
  descendant must still be ordinary, non-linked entries. Deployable visible writes fail with
  `503` without a retained mutation capability.
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

## Media Playback and Worker Handoff

- The browser first asks the website to authorize a direct probe, then tries the original
  authenticated media URL. The website does not infer browser codec support from a filename or
  container. A native playback error requests one fixed `VIDEO_MP4` or `AUDIO_M4A` profile; the
  isolated worker performs the actual media inspection in the worker task. The website never
  accepts an executable, codec, or extra-argument field and never launches a media process.
- Media jobs are owner-scoped Mongo records with bounded global and per-account admission. Their
  cache identity includes the relative source path, size, modification time, fixed profile, and
  profile version. Ready matches are reused; changed source metadata or profile versions create a
  different cache identity. An active match is reused only by its owner, while a completed cache
  is readable by any account that still has shared-folder read access. Queue saturation returns
  `429`, reserve denial returns `507`, and a private-storage failure returns `503`.
- Worker handoff JSON lives only below fixed private system-root directories. It contains schema
  version 1, opaque job/cache IDs, validated absolute source/output/status/cancellation paths,
  source revision facts, a fixed profile enum, deadline, maximum output size, and initial buffer
  size. Spring fully writes and flushes `{jobId}.json` before publishing `{jobId}.ready`; the
  worker watches the marker and then reads the matching descriptor. Worker status JSON is
  size-bounded and accepted only for the matching opaque job with a known state, bounded output
  count, and safe failure category. Spring serializes admission, cancellation, reconciliation, and
  descriptor publication under one bounded scheduler lock. It publishes only one active descriptor,
  recovers an interrupted publication idempotently, and advances unattended terminal jobs; the
  worker adds its own cross-process lock in the worker task. Missing status means no update;
  malformed protocol, source revision changes, and output-limit violations are distinct terminal
  failures, while private-storage failures remain retryable service unavailability.
- `GET /media/jobs/{id}/stream` rechecks read access, restricts active work to its owner, and lets
  any current reader use a completed shared cache. A growing output streams sequentially after the
  configured initial buffer with bounded polling, then follows the worker's atomic ready-cache
  publish. Completed outputs regain normal single-range semantics.
  Seeking is intentionally limited while the derivative is still growing and becomes available
  when the job is ready. Client disconnects stop that response without canceling shared server
  state; explicit owner cancellation publishes a fixed marker for the worker.
- The cache defaults to 250 GB, uses persisted Mongo `lastAccessedAt` ordering, and excludes active
  or currently streamed outputs from eviction. Media conversion preserves the default 100 GB
  free-space reserve, reserves the full output cap for every active job, and independently checks
  the actual partial and completed file lengths against that cap.

## Update This Doc

Update this README when resolver or native-handle guarantees, effective access rules, mutation
conflict rules, upload state, audit fields, or shared-folder configuration changes.
