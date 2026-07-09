# Configuration

Owns application-wide Spring and web infrastructure.

## What Lives Here

- Security configuration and route access rules under `security`.
- Public static browser assets such as `/favicon.ico`.
- Public tool pages such as `/zip-coordinates`.
- Public read-only WFL routes, including nearby restaurant lookup by browser
  coordinates or ZIP code.
- JWT authentication filter wiring under `security`.
- Stable JWT signing through `APP_JWT_SECRET`.
- Login JWTs expire one day after issue; the browser keeps users signed in until
  that token expiration.
- Rate limiting and request size protection filters under `filter`.
- `ClientIpResolver` resolves effective client IPs from `X-Forwarded-For` only
  when the immediate remote address is listed in `client-ip.trusted-proxies`.
- MongoDB auditing configuration under `mongo`.
- Shared configuration properties that do not yet need a subpackage.
- Other cross-cutting configuration that should not belong to a single feature package.

## Update This Doc

Update this README when public/private routes, security rules, JWT behavior, rate limits, or request limits change.
