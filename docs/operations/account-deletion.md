# Account deletion and retained records

Administrative account deletion is a resumable privacy operation. The API returns a stable
`deleted:<12 hex>` pseudonym and never returns the deleted account's email, name, username, or
credential fields.

The operation removes the account and its credentials only after all earlier cleanup checkpoints
complete. It deletes private messages, notifications and preferences, trust relationships, hidden
threads, private What's For Lunch state, conversation archive state, and account-owned private
shared-folder work. Public posts remain available under the non-authenticating `deleted-user`
tombstone. Other accounts' follower lists are scrubbed.

Reports, administrative activity, shared-folder audit events, and recycle accountability remain
for operational and moderation history. Account identifiers in those records become the stable
pseudonym, usernames and labels become `deleted-user`, and account-related audit metadata, client
IP data, and free-form administrative messages are removed or replaced with a fixed safe message.

The `account_deletion_jobs` collection retains only the pseudonym, checkpoint/status values,
bounded failure category, and timestamps. A failed attempt can be retried with the original account
ID; completed retries return the stored result without repeating cleanup effects.
