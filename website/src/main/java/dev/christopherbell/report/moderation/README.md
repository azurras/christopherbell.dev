# Report Moderation

Owns the admin report queue and report resolution side effects.

## What Lives Here

- Report list reads for the back office.
- Repeat-report counts for the reported account so admins can see how often an
  account has open or resolved reports.
- Report reopen and resolution logic.
- Post deletion and user suspension triggered by report resolution.
- Required, bounded moderator reasons and one primary append-only audit event
  for report resolve/reopen decisions, with status and resolution transitions.
- Supplemental post-deletion and account-suspension activity records use safe
  labels and never copy the reported post body into audit target fields.

Keep user report submission out of this package. Report creation belongs in
`report.submission`.
