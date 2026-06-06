# Account

Owns user account lifecycle and authentication-adjacent account behavior. The
top-level `AccountService` remains a facade for existing controllers while
subfeature services own the larger account workflows.

## What Lives Here

- Account CRUD and lookup facade methods.
- `auth` owns login validation and JWT creation for active accounts.
- `passwordreset` owns reset token storage, expiration, password replacement,
  and reset-link notification handoff.
- `profile` owns self-account detail reads and public username-only profiles,
  including safe activity and network stats for post count, reply count,
  followers, and following.
- `follow` owns follow/unfollow graph updates.
- `moderation` owns account approval, status changes, and role updates.
- `trust` owns signed-in user mute/block relationships. Muted and blocked
  account ids are hidden from personal feed reads, and blocks also prevent
  direct messages in either direction.
- Case-insensitive email normalization and lookup for sign-up, login, and password reset.
- Username sanitization on account creation/update and case-insensitive username uniqueness checks.
- Signed-in username prefix search for recipient autocomplete. The search
  endpoint returns active, public-safe username suggestions only and excludes the
  current caller.
- Public profiles expose usernames and counts only; first and last names stay private to account detail APIs.
- Admin account updates can change account status and promote roles when the
  Back Office user queue needs to grant moderator or administrator privileges.
- Account DTOs and persistence models under `model`.

## Update This Doc

Update this README when account fields, login behavior, password reset behavior, profile/follow behavior, or account API contracts change.
