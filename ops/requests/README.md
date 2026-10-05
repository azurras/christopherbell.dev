# Operations Requests

Agents and operators without administrator rights ask production to run an allowlisted
operation by merging a JSON file to `main` in this folder. The SYSTEM auto-deploy poller reads
requests from the CI-green `main` tip, runs each one once under the operation's own lock and
guards, and publishes the result. A commit that changes only this folder is never deployed.

## Format

One file per request, named `<id>.json`:

```json
{
  "id": "2026-10-05-verify-after-tunnel-renewal",
  "action": "verify-startup",
  "reason": "Confirm services and public routes after renewing the tunnel token.",
  "requestedAt": "2026-10-05T18:00:00Z"
}
```

| Field | Rule |
|---|---|
| `id` | Equals the file name without `.json`; 3-80 lowercase letters, digits or hyphens; never reused |
| `action` | One of the actions below |
| `reason` | One line, 1-200 characters, recorded with the result |
| `requestedAt` | ISO 8601 with a time zone. The poller runs a request only if it first sees it within 24 hours of this time; older or future-dated requests are recorded as `EXPIRED` and never run, so a new host replays nothing |
| `expectedActiveSha` | Full SHA of the release being replaced; required for `rollback`, and the request is `REJECTED` if production has moved |

No other fields are accepted. CI validates every file here with
`ops/production/windows/tests/Production.OpsRequests.Tests.ps1`.

## Actions

| Action | Runs | Notes |
|---|---|---|
| `verify-startup` | `prod.cmd verify-startup` | Read-only checks of services, the poller task and endpoints |
| `backup` | `prod.cmd backup` | Verified MongoDB archive; the live database is only read |
| `restart` | `prod.cmd restart` | Restarts the website and verifies it |
| `redeploy` | `prod.cmd deploy` | Rebuilds and deploys the `main` tip; recorded as a GitHub `Production` deployment |
| `rollback` | `prod.cmd rollback` | Restores the previous release behind the existing schema guards, then holds the rolled-away `main` commit (`auto-status`: `HELD`) until a new commit lands; recorded as a GitHub deployment of the restored release |

Database restores, Mongo consolidation and migration-aware rollback are deliberately absent; they
keep their explicit human confirmations.

## Results

Run `prod.cmd diagnostics` from any account on the host. Its `opsRequests` list shows each
request's `outcome` (`SUCCEEDED`, `FAILED`, `REJECTED` or `EXPIRED`) and a redacted detail. A
failed request is not retried; add a new request with a new `id`. Request files can be deleted
in a later commit once handled.
