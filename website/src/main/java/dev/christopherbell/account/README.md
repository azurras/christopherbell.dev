# Account

Owns user account lifecycle and authentication-adjacent account behavior.

## What Lives Here

- Account CRUD, lookup, approval, profile, follow/unfollow, and login flows.
- Case-insensitive email normalization and lookup for sign-up, login, and password reset.
- Username sanitization on account creation/update and case-insensitive username uniqueness checks.
- Public profiles expose usernames and counts only; first and last names stay private to account detail APIs.
- Password reset request/confirmation endpoints and token handling.
- Mail notification handoff for password reset links.
- Account DTOs and persistence models under `model`.

## Update This Doc

Update this README when account fields, login behavior, password reset behavior, profile/follow behavior, or account API contracts change.
