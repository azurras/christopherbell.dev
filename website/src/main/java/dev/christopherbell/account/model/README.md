# Account Models

Owns account-domain request objects, enums, and persistence entities.

## What Lives Here

- `Account` is the Mongo-backed account aggregate.
- `AccountLoginRequest` carries login credentials.
- `AccountPasswordResetRequest` and `AccountPasswordResetConfirmRequest` carry
  reset flow input.
- `AccountStatus` and `Role` constrain account state and authorization role
  values.
- `AccountPermission` stores independent account capabilities. Shared-folder
  read/write capabilities are persisted separately from roles so existing role
  hierarchy and JWT authorities remain unchanged.
- `dto` contains API-facing account response/update records.

## Design Notes

- Store normalized email and username values so lookup behavior is consistent.
- Keep password reset token fields on `Account` because the reset lifecycle is
  account-owned and short-lived.
- Do not expose this package directly to public profile pages when a smaller DTO
  can prevent private field leakage.

## Update This Doc

Update this README when account persistence fields, request records, auth roles,
or status values change.
