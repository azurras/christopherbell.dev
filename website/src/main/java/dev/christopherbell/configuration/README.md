# Configuration

Owns application-wide Spring and web infrastructure.

## What Lives Here

- Security configuration and route access rules under `security`.
- Public static browser assets such as `/favicon.ico`.
- Public tool pages such as `/zip-coordinates`.
- The public `/command-center` route serves only a data-free page shell. No
  `/api/admin/command-center/**` route is public; every API method requires both
  ADMIN JWT authority and a fresh persisted ADMIN, ACTIVE, approved account.
- Public read-only WFL routes, including nearby restaurant lookup by browser
  coordinates or ZIP code.
- JWT authentication filter wiring under `security`.
- Stable JWT signing through `APP_JWT_SECRET`.
- Login JWTs expire one day after issue; the browser keeps users signed in until
  that token expiration.
- Rate limiting and request size protection filters under `filter`.
- `RateLimitProperties` binds ordered `rate-limit.rules` so environments can
  tune per-endpoint capacity and window settings.
- `ClientIpResolver` resolves effective client IPs from `X-Forwarded-For` only
  when the immediate remote address is listed in `client-ip.trusted-proxies`.
- MongoDB auditing configuration under `mongo`.
- Shared configuration properties that do not yet need a subpackage.
- Other cross-cutting configuration that should not belong to a single feature package.

## Command Center Configuration

`command-center.enabled` gates host sampling and action acceptance;
`sample-interval`, `history-duration`, and `provider-timeout` control cached host
sampling. `cpu-temperature-refresh-interval` and `cpu-temperature-process-timeout`
separately bound the privileged CPU sensor without delaying other providers. `log-path`,
`max-log-lines`, and `max-log-bytes` define the server-owned fixed log boundary.
Threshold properties control warning evaluation without changing raw readings.

`command-center.actions.mode` defaults to `SIMULATED`. The local profile keeps
that mode explicitly. The production profile opts into `WINDOWS` and supplies
the fixed WinSW and `shutdown.exe` paths; callers cannot override them. Challenge
TTL, cooldown, failed-attempt limits, and their window are configurable abuse
controls. The profiles retain `power-delay: 60s` as an operator-visible statement
of the contract, while both the scheduled pending-action time and Windows command
mapping independently enforce the fixed 60-second delay.

Production computer power actions remain disabled unless
`COMMAND_CENTER_POWER_ACTIONS_ENABLED=true`; `GIT_COMMIT` supplies the optional safe commit label.
The production sensor library directory is fixed beneath the service-owned `config` directory;
local/default profiles do not enable native sensor loading.

## Update This Doc

Update this README when public/private routes, security rules, JWT behavior, rate limits, or request limits change.
