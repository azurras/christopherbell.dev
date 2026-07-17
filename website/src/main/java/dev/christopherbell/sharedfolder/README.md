# Shared Folder

Owns the security boundary for the private shared-folder feature before any routes or filesystem
operations exist.

## What Lives Here

- `fs` resolves only existing, ordinary descendants of the configured shared root using
  Windows-safe relative names. It rejects drive-qualified and UNC forms, alternate streams,
  dot segments, unsafe DOS names, all ISO control characters, links, NTFS reparse points, and
  filesystem mounts. Every existing segment must retain a canonical identity beneath the root and
  stay on its file store. Callers recheck an existing selected path immediately before any
  mutation.
- `security` reloads the authenticated account from MongoDB for every decision. A persisted
  active approved account needs a shared-folder capability; ADMIN has read and write implicitly,
  and write implies read. JWTs intentionally carry no shared-folder capability.
- `audit` defines a bounded command and sink contract only. No audit persistence bean or
  operation-time sink injection exists until the later persistence task.

## Configuration

`app.shared-folder` binds the roots, upload and cache limits, retention periods, and enablement
flag. Local and test profiles use build-owned paths; production roots use environment-overridable
`A:/Shared` and `A:/Shared-System` defaults.

## Update This Doc

Update this README when resolver guarantees, effective access rules, audit fields, or
shared-folder configuration changes.
