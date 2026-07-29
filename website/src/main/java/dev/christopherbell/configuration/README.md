# Configuration

Owns application-wide Spring and web infrastructure.

## What Lives Here

- Security configuration and route access rules under `security`.
- Public static browser assets such as `/favicon.ico`.
- `PublicMetadataController` serves `/robots.txt` and `/sitemap.xml` with
  explicit revalidation instead of the long-lived immutable browser-asset cache.
- Standard Actuator liveness and readiness groups are public and detail-free;
  the readiness group also checks MongoDB. Other Actuator routes stay protected.
- CSS, JavaScript, images, and favicon URLs use the `GIT_COMMIT` fixed resource
  version. Versioned URLs receive one-year immutable public caching; direct
  unversioned paths use a bounded one-hour cache. The build embeds its exact Git
  SHA as the default, and deployments may override it with `GIT_COMMIT`.
- Public tool pages such as `/zip-coordinates`.
- The public `/command-center` route serves only a data-free page shell. No
  `/api/admin/command-center/**` route is public; every API method requires both
  ADMIN JWT authority and a fresh persisted ADMIN, ACTIVE account.
- Public read-only WFL routes, including nearby restaurant lookup by browser
  coordinates or ZIP code.
- JWT authentication filter wiring under `security`. Explicit bearer headers
  take precedence for API clients; browser requests otherwise authenticate from
  the HttpOnly `CBELL_AUTH` cookie. Exact GET static-resource namespaces bypass
  credential processing so cacheable browser assets never renew or invalidate a
  browser session. Valid browser sessions provide their persisted account-id and
  role snapshot without an account lookup; legacy sessions missing the required
  identity snapshot are deleted and rejected.
- Stable JWT signing through `APP_JWT_SECRET`.
- Production settings validation runs before context refresh and reports only
  invalid setting names, never their values.
- `app.mail.enabled` explicitly controls password-reset delivery; disabled mail
  does not resolve the mail sender.
- Login JWTs and browser authentication cookies expire one day after issue. The
  browser never stores the JWT in JavaScript-readable storage.
- Browser mutations use Spring Security's SPA CSRF contract: the readable
  `XSRF-TOKEN` cookie is echoed as `X-XSRF-TOKEN`. Only requests with an explicit,
  nonblank bearer header bypass CSRF for API compatibility.
- Browser responses set CSP, SAMEORIGIN framing, Permissions Policy,
  strict-origin-when-cross-origin referrer policy, and production-configured HSTS.
  CSP permits same-origin content frames for the persistent media shell while frame ancestry
  remains restricted to the same origin.
  Browser cookie security, HSTS, and the canonical password-reset origin bind
  under `app.browser-security`; production ignores forwarding headers.
- Rate limiting and request size protection filters under `filter`. Ordinary
  bodies bind from the positive typed `app.request-size.default-max` setting;
  streamed shared-folder chunks retain their feature-owned upload-chunk limit.
- `RateLimitProperties` binds ordered `rate-limit.rules` so environments can
  tune per-endpoint capacity and window settings, while `rate-limit.max-buckets`
  hard-bounds process-local client state. Inactive buckets expire after their
  matched rule window. Shared-folder upload, mutation, and transcode
  rules are first-match groups at 240, 60, and 10 requests per minute respectively. Music
  library/queue mutations and metadata rewrites use separate 120 and 10 request-per-minute
  groups. GET, HEAD, range, and progressive media reads do not consume mutation buckets. Rejections use a
  standard API-envelope `429` body with `Retry-After` and `X-RateLimit-*`
  guidance. The mutation
  rule covers deployed `/folders`, `/entries`, and `/admin/recycle/**` writes plus planned aliases;
  transcode admission covers deployed `/media/fallback` plus the planned `/media/jobs` alias.
- `ClientIpResolver` resolves effective client IPs from `X-Forwarded-For` only
  when the immediate remote address is listed in `client-ip.trusted-proxies`. It
  validates every configured IP/CIDR at startup and walks a forwarding chain
  from the nearest trusted hop to the first untrusted client. Production binds
  `CLIENT_IP_TRUSTED_PROXIES` and defaults only to IPv4/IPv6 loopback for the
  local Cloudflare tunnel process.
- MongoDB auditing, fixed-name leases, and immutable versioned migrations under
  `mongo`.
- `FederationSecretApplicationContextInitializer` resolves the production
  ActivityPub identity-encryption key before configuration binding. It honors
  an explicit environment secret or atomically creates and reuses one 32-byte
  file beneath the existing protected production config directory.
- Shared configuration properties that do not yet need a subpackage.
- `SharedFolderProperties` binds `app.shared-folder` storage roots, resource limits, retention
  windows, and the feature gate. Local/test roots stay build-owned; production uses
  environment-overridable dedicated Windows roots.
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
