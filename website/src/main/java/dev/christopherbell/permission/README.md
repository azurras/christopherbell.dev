# Permission

Owns shared permission checks.

## What Lives Here

- Authority checks used by controller `@PreAuthorize` expressions.
- JWT login token creation and validation. Generated login tokens expire one day
  after issue so users can stay signed in for the day without reauthenticating.
- Small reusable permission helpers that keep authorization policy out of controllers.

## Update This Doc

Update this README when role names, authority checks, JWT lifetime, or controller authorization conventions change.
