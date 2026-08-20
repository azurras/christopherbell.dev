# Account

Owns user account lifecycle and authentication-adjacent account behavior. The
top-level `AccountService` remains a facade for existing controllers while
subfeature services own the larger account workflows.

## What Lives Here

- Account CRUD and lookup facade methods.
- `api.AccountMigrationVerifier` publishes narrow real-adapter parity operations for the guarded
  MongoDB-to-PostgreSQL cutover.
- Account creation returns `201 Created` with a `Location` header for the
  canonical account resource. Synchronous updates and deletes return `200 OK`,
  and bodyless delete routes do not require a request `Content-Type` header.
- `auth` owns login validation and JWT creation for active accounts.
- Login returns one public rejection for unknown, invalid-password, and inactive
  accounts. Legacy PBKDF2 verification is padded to the current work factor and
  successful upgrades reuse the verified salt, so concurrent upgrades produce
  the same self-describing `pbkdf2-sha256` hash.
- Login completion updates only credential and login metadata through a conditional
  atomic write, preserving concurrent lifecycle, role, and permission changes and
  minting tokens from the returned current account state.
- Browser login opts into cookie mode with `X-CBELL-Browser-Session: cookie` and
  receives no JWT in the JSON body. The JWT is set in an HttpOnly, SameSite=Lax
  cookie plus a separate non-secret UI marker. Existing API login without that
  header still returns the JWT payload for bearer clients. Logout clears both
  browser cookies.
- `passwordreset` owns reset token storage, expiration, password replacement,
  and reset-link notification handoff. Reset links use the configured public
  application origin rather than request or forwarded host headers.
- `profile` owns self-account detail reads and public username-only profiles,
  including safe activity and network stats for post count, reply count,
  followers, and following.
- `follow` owns follow/unfollow graph updates.
- `moderation` owns account status changes and role updates. `AccountStatus` is
  the single lifecycle authority; signup creates active accounts and there is no
  separate approval flag or pending-approval queue.
- `trust` owns signed-in user mute/block relationships. Muted and blocked
  account ids are hidden from personal feed reads, and blocks also prevent
  direct messages in either direction.
- Case-insensitive email normalization and lookup for sign-up, login, and password reset.
- Login and password-reset request models reject blank, malformed, or oversized
  fields through Bean Validation before account services run.
- Username sanitization on account creation/update and case-insensitive username uniqueness checks.
- Credential-provider failures are explicit internal service failures: causes
  remain available to diagnostics while API clients receive the safe generic
  internal-error envelope.
- Signed-in username prefix search for recipient autocomplete. The search
  endpoint returns active, public-safe username suggestions only and excludes the
  current caller.
- Public profiles expose usernames and counts only; first and last names stay private to account detail APIs.
- Admin account updates can change account status and promote roles when the
  Back Office user queue needs to grant moderator or administrator privileges.
- Account deletion is resumable through the `2026-07-26` API. Retained posts
  pseudonymize author and edit-audit ids, remove the deleted account from like
  sets, and reconcile denormalized like counts. The legacy `2025-09-03` response
  shape remains available for older clients.
- Admins can independently grant or revoke persisted shared-folder read/write
  capabilities through the dated account API. These capabilities do not change
  the USER/MOD/ADMIN hierarchy or JWT contents; write always requires read.
- Successful password, role, status, and capability changes revoke every opaque
  browser session for the account after the authoritative account write succeeds.
  As a safety fallback for split-write failures, ordinary cookie authentication
  also validates the session fingerprint against minimal current account security
  state joined in the session lookup.
- Account DTOs and persistence models under `model`.

## Update This Doc

Update this README when account fields, login behavior, password reset behavior, profile/follow behavior, or account API contracts change.
