# Configuration Security

Owns Spring Security wiring and request authentication infrastructure.

## What Lives Here

- `SecurityConfig` defines public routes, method security, the filter chain, and security-related beans.
- `JwtAuthenticationFilter` reads bearer tokens, validates them, and populates the Spring Security context.
- `SharedFolderNoStoreFilter` runs before shared-folder authentication and applies
  `Cache-Control: private, no-store` only to the exact versioned shared-folder API prefix,
  including authentication and authorization failures.

## Design Notes

Keep public URL changes here and pair them with security tests. Browser-callable public endpoints must also be documented in the owning feature package.

`/shared` is a deliberately data-free public shell. Do not add `/api/shared-folder/**` to the
public list: those routes require JWT authentication and their controller refreshes effective
shared-folder read access independently.

