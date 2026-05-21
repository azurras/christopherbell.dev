# Configuration

Owns application-wide Spring and web infrastructure.

## What Lives Here

- Security configuration and route access rules.
- Public static browser assets such as `/favicon.ico`.
- Public read-only WFL routes, including nearby restaurant lookup by browser
  coordinates or ZIP code.
- JWT authentication filter wiring.
- Stable JWT signing through `APP_JWT_SECRET`.
- Rate limiting and request size protection filters.
- Other cross-cutting configuration that should not belong to a single feature package.

## Update This Doc

Update this README when public/private routes, security rules, JWT behavior, rate limits, or request limits change.
