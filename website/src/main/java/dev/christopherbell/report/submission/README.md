# Report Submission

Owns the user-facing report creation flow.

## What Lives Here

- Validation for report creation requests.
- Lookup of the current reporter, reported post, and reported account.
- Creation of open `PostReport` records with snapshot metadata.

Keep moderation actions out of this package. Admin resolution belongs in
`report.moderation`.
