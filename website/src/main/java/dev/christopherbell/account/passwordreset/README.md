# Account Password Reset

Owns password reset token lifecycle and notification delivery.

## What Lives Here

- Generic reset requests that do not reveal whether an email exists.
- Reset token generation, hashing, expiration, and cleanup.
- Password replacement after a valid reset token.
- Email notification handoff, with local/dev logging when mail is unavailable.

## Update This Doc

Update this README when password reset request, confirmation, token, expiration,
or notification behavior changes.
