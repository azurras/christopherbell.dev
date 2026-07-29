# Configuration Security

Owns Spring Security wiring and request authentication infrastructure.

## What Lives Here

- `SecurityConfig` defines public routes, method security, the filter chain, and security-related beans.
- `JwtAuthenticationFilter` reads bearer tokens, validates them, and populates the Spring Security context.
- `StaticAssetRequestMatcher` bypasses credential handling only for the listed GET favicon, CSS,
  image, JavaScript, vendor, pinned Bootstrap WebJar, and release-versioned asset namespaces.
  Shared-folder worker and media routes remain authentication boundaries.
- `SharedFolderNoStoreFilter` runs before shared-folder authentication and applies
  `Cache-Control: private, no-store` only to the exact versioned shared-folder API prefix,
  including authentication and authorization failures.
- The root worker bootstrap `/shared-folder-auth-sw.js` is public only for an exact `GET` so an
  anonymous browser can install it before the worker has a JWT to forward. Its POSTs, near-miss
  paths, and every `/api/shared-folder/**` endpoint remain protected.
- The blog list/detail APIs, photo list API, and exact Bootstrap 5.3.3 WebJar paths are public for
  `GET` only. Equivalent mutations and other WebJar versions remain authenticated.

## Design Notes

Keep public URL changes here and pair them with security tests. Browser-callable public endpoints must also be documented in the owning feature package.

`/shared` is a deliberately data-free public shell. Do not add `/api/shared-folder/**` to the
public list: those routes require JWT authentication and their controller refreshes effective
shared-folder read access independently.
