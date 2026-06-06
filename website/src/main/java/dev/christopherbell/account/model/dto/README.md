# Account DTOs

Owns account API shapes that leave or enter the account feature.

## What Lives Here

- `AccountCreateRequest` describes public account creation input.
- `AccountUpdateRequest` describes admin/profile account update input.
- `AccountDetail` is the full account detail response for authorized callers.
- `AccountProfile` is the intentionally smaller public profile response.
- `AccountUsernameSuggestion` is the public-safe username-only response for
  signed-in autocomplete controls.

## Design Notes

- Keep public profile DTOs separate from detail DTOs so private fields are not
  accidentally exposed.
- Prefer records for request/response shapes because they make API fields
  explicit and immutable.

## Update This Doc

Update this README when account API request or response fields change.
