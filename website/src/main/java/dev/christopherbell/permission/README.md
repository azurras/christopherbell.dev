# Permission

Owns shared permission checks.

## What Lives Here

- `PermissionService`: authority checks used by controller `@PreAuthorize`
  expressions, and the current request's account id.
- Login JWTs are not here: `account.api.LoginTokens` issues and verifies them,
  so the security configuration reaches them through the account area's
  published API.
- Production JWT signing requires a strong configured `app.jwt.secret` or
  `APP_JWT_SECRET`; the local development fallback is not allowed when the
  `prod` profile is active.
- Small reusable permission helpers that keep authorization policy out of controllers.

## Package Shape

This package stays flat while `PermissionService` owns only role checks and the
current account id.

## Update This Doc

Update this README when role names, authority checks, JWT lifetime, or controller authorization conventions change.
