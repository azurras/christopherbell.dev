# Account

Owns user account lifecycle and authentication-adjacent account behavior. The
top-level `AccountService` remains a facade for existing controllers while
subfeature services own the larger account workflows.

## What Lives Here

- Account CRUD and lookup facade methods.
- `auth` owns login validation and JWT creation for active accounts.
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
- `moderation` owns account approval, status changes, and role updates.
- `trust` owns signed-in user mute/block relationships. Muted and blocked
  account ids are hidden from personal feed reads, and blocks also prevent
  direct messages in either direction.
- Case-insensitive email normalization and lookup for sign-up, login, and password reset.
- Login and password-reset request models reject blank, malformed, or oversized
  fields through Bean Validation before account services run.
- Username sanitization on account creation/update and case-insensitive username uniqueness checks.
- Signed-in username prefix search for recipient autocomplete. The search
  endpoint returns active, public-safe username suggestions only and excludes the
  current caller.
- Public profiles expose usernames and counts only; first and last names stay private to account detail APIs.
- Admin account updates can change account status and promote roles when the
  Back Office user queue needs to grant moderator or administrator privileges.
- Admins can independently grant or revoke persisted shared-folder read/write
  capabilities through the dated account API. These capabilities do not change
  the USER/MOD/ADMIN hierarchy or JWT contents; write always requires read.
- Account DTOs and persistence models under `model`.

## Update This Doc

Update this README when account fields, login behavior, password reset behavior, profile/follow behavior, or account API contracts change.
