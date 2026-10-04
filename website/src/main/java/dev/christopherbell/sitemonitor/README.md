# Website Monitor pilot

Private baseline comparison and client reporting for agencies maintaining websites.
`/site-monitor` is a public, data-free tool page. All `/api/site-monitor/v1` workspace,
site, check and text-report endpoints require authentication and a current active
account with no started deletion job. Mutations retain existing CSRF protection.

## Workflow and limits

The free pilot allows ten accounts, five sites per account, five configured pages
per site and ten latest reports per site. Each customer publishes a random token
at `/.well-known/christopherbell-site-monitor.txt`. Ownership is checked before
every baseline or comparison. A fixed demonstration uses our own public website
and clearly bypasses customer ownership verification.

Capture a healthy baseline explicitly. Later checks compare HTTP status, final
destination, title, description, canonical and robots metadata (including the
response header). Up to ten same-origin static assets are checked with HEAD;
external and query-bearing assets are omitted. Repeated HTTP failures, observed
changes and incomplete coverage are separate results. Failed captures preserve
the previous baseline. Each report stores the baseline timestamp it used.

Manual attempts have a 15-minute cooldown. Automatic attempts are due after one
day and are limited to one per minute across instances by durable schedule state.
Failed automatic attempts also wait a day. Results appear in the workspace;
email delivery and billing are deferred. The proposed $29/month price is an
unvalidated experiment and cannot be purchased.

## Boundaries and persistence

`fetch` validates HTTPS port443, rejects credentials/query/fragment and non-public
DNS answers, pins an approved address with the original TLS identity, and follows
only bounded same-origin redirects. GET/HEAD only; no credentials, cookies,
proxies or connection retry. Bodies are bounded to1MiB and a run to45seconds.
The lease and account state are checked before every fetch and after DNS.

`monitor` owns comparison, baseline acceptance and scheduling. The fixed durable
`site-monitor-pilot` lease serializes mutations and is also held while account
deletion starts/cleans private state. Atomic owner, generation and version predicates
reject stale writes/deletes even after a fixed slot is reused by another workspace.
`persistence` uses ten fixed `pilot-0` through `pilot-9` identities for indexed
point reads and one fixed `daily` scheduler identity. Explicit additive kinds
`site_monitor_workspace` and `site_monitor_schedule` live in `application_runtime`;
the historical cutover manifest, digest and indexes are unchanged. Account
deletion removes only matching workspace identities. No raw remote HTML is stored.

Rendered layout, forms, authentication, checkout, external assets, security and
continuous uptime are outside this pilot's coverage. A HEALTHY report states only
that the documented bounded checks passed. Export is private no-store plain text.

## Verification

Native Java and JavaScript tests cover isolation, CSRF, policies, pinned TLS,
deadlines, comparison, cooldown, retention, lease loss and scheduler throttling.
`SiteMonitorMongoIntegrationTest` is opt-in via `SITE_MONITOR_TEST_MONGO_URI`; it
requires one loopback destination, database `test`, and a port other than27017.
Use a fresh disposable Mongo instance; never point this test at live data.
