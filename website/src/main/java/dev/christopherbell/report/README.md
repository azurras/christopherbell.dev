# Report

Owns user reports and moderation actions.

## What Lives Here

- Report submission for posts.
- Report resolution actions, including post deletion and user suspension.
- Report models under `model`.
- `submission` owns user-created report validation, post/account lookup, and open report persistence.
- `moderation` owns admin report list reads, repeat-report context,
  resolution, reopen, post deletion, user suspension, and admin activity logs.
- The `2025-09-03` submission route retains its payload-free success envelope;
  the additive `2026-07-26` route returns the accepted report resource.

## Update This Doc

Update this README when report fields, report email content, moderation actions, or admin report API behavior changes.
