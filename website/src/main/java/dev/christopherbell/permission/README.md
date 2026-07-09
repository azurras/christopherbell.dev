# Permission

Owns shared permission checks.

## What Lives Here

- Authority checks used by controller `@PreAuthorize` expressions.
- JWT login token creation and validation. Generated login tokens expire one day
  after issue so users can stay signed in for the day without reauthenticating.
- Production JWT signing requires a strong configured `app.jwt.secret` or
  `APP_JWT_SECRET`; the local development fallback is not allowed when the
  `prod` profile is active.
- Small reusable permission helpers that keep authorization policy out of controllers.

## Package Shape

This package stays flat while `PermissionService` owns the complete auth helper
surface. Split into `jwt` and `authority` only if token creation/validation or
role checks grow into separate collaborators.

## Update This Doc

Update this README when role names, authority checks, JWT lifetime, or controller authorization conventions change.
