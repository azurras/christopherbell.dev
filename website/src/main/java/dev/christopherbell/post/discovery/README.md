# Void discovery

This package owns the anonymous, read-only discovery surface for active Void conversations.

The versioned endpoints under `/api/posts/2026-07-28/discovery` provide New arrivals,
Fading soon, Recently revived, active Topics, and the active root conversations for one
topic. Every query uses an opaque timestamp-and-id cursor, clamps page output to 24 items,
excludes expired documents, and returns `Cache-Control: no-store`.

Topic discovery is based only on normalized hashtags persisted with posts. New sorts by
creation time, Fading sorts by expiration time, and Revived sorts by the most recent
confirmed keep-alive or reply. Engagement totals never influence discovery ordering.
