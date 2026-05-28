# Configuration Security

Owns Spring Security wiring and request authentication infrastructure.

## What Lives Here

- `SecurityConfig` defines public routes, method security, the filter chain, and security-related beans.
- `JwtAuthenticationFilter` reads bearer tokens, validates them, and populates the Spring Security context.

## Design Notes

Keep public URL changes here and pair them with security tests. Browser-callable public endpoints must also be documented in the owning feature package.

