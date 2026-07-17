# Shared Folder

Owns the security boundary for the private shared-folder feature before any routes or filesystem
operations exist.

## What Lives Here

- `fs` resolves only existing, ordinary descendants of the configured shared root using
  Windows-safe relative names. It rejects drive-qualified and UNC forms, alternate streams,
  dot segments, unsafe DOS names, all ISO control characters, links, NTFS reparse points, and
  filesystem mounts. Every existing segment must retain a canonical identity beneath the root and
  stay on its file store. Linux reads `/proc/self/mountinfo` and fails closed when its mount facts
  are unavailable or malformed; the Windows provider relies on native reparse-point and
  file-store checks. Callers recheck an existing selected path immediately before any mutation.
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
  are denied before a filesystem service runs.
- `GET /entries` returns only decoded relative paths and ordinary-entry metadata. `GET /content`
  uses a `FileSystemResource` plus `ResourceRegion` for disk streaming and supports exactly one
  HTTP byte range, including correct `206`, `416`, `Content-Range`, `Accept-Ranges`, and `HEAD`
  semantics. It never calls `readAllBytes`.
- `GET /preview` returns bounded UTF-8 text as JSON for text files; allowlisted raster image,
  audio, video, and PDF types stream inline. HTML, SVG, and every unknown type stay attachment
  only. Previews send `nosniff`; PDFs additionally send a restrictive sandbox CSP. Download and
  preview filenames use framework-built RFC 5987 content-disposition values.

## Update This Doc

Update this README when resolver guarantees, effective access rules, audit fields, or
shared-folder configuration changes.
