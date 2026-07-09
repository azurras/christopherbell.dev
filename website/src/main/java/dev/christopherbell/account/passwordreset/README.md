# Account Password Reset

Owns password reset token lifecycle and notification delivery.

## What Lives Here

- Generic reset requests that do not reveal whether an email exists.
- Reset token generation, hashing, expiration, and cleanup.
- Password replacement after a valid reset token.
- Email notification handoff. Reset URLs and bearer tokens are not written to
  application logs when mail is unavailable or delivery fails.

## Update This Doc

Update this README when password reset request, confirmation, token, expiration,
or notification behavior changes.
