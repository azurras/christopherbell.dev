# Report Moderation

Owns the admin report queue and report resolution side effects.

## What Lives Here

- Report list reads for the back office.
- Report reopen and resolution logic.
- Post deletion and user suspension triggered by report resolution.
- Admin activity logging for report moderation decisions.

Keep user report submission out of this package. Report creation belongs in
`report.submission`.
