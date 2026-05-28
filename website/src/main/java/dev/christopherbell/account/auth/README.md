# Account Auth

Owns login-specific account behavior.

## What Lives Here

- Email normalization for login lookup.
- Password verification through shared password utilities.
- Active-account enforcement before token creation.
- JWT handoff through `PermissionService`.

## Update This Doc

Update this README when login behavior, token creation, or active-account login
rules change.
