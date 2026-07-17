# Shared Folder

Owns the security boundary for the private shared-folder feature before any routes or filesystem
operations exist.

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
  Non-Windows test/local providers retain the portable NIO resolver with no claim of this native
  handle-relative race guarantee.
- `security` reloads the authenticated account from MongoDB for every decision. A persisted
  active approved account needs a shared-folder capability; ADMIN has read and write implicitly,
  and write implies read. JWTs intentionally carry no shared-folder capability.
- `audit` defines a bounded command and sink contract only. No audit persistence bean or
  operation-time sink injection exists until the later persistence task.
- `model`, `service`, and `web` provide the read-only portal slice: authenticated directory
  listings, disk-streamed full or single-range downloads, and safe previews. `web` accepts each
  query path after Spring has decoded it once and passes that value unchanged to the resolver;
  response models never include a local absolute path.

## Configuration

`app.shared-folder` binds the roots, upload and cache limits, retention periods, and enablement
flag. Local and test profiles use build-owned paths; production roots use environment-overridable
`A:/Shared` and `A:/Shared-System` defaults.

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

## Update This Doc

Update this README when resolver guarantees, effective access rules, audit fields, or
shared-folder configuration changes.
