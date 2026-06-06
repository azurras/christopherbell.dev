# Raising Canes Box Index

Tracks weekly Raising Canes Box Combo pricing as a public Tool.

## What Lives Here

- `CanesBoxTrackerController` exposes the public history API at
  `/api/canes-box-tracker/2026-06-04/history`.
- `CanesBoxTrackerService` runs the scheduled weekly collection job, averages
  verified metro prices, persists snapshots, and returns chart-ready history.
- `OfficialCanesBoxPriceClient` talks to the official Raising Canes ordering
  app APIs. It searches the official GraphQL location API by metro coordinates,
  fetches that restaurant's official menu, and reads the Box Combo `cost`.
  The older NomNom restaurant-by-ref API is retained as a secondary official
  fallback before the disabled-by-default public menu fallback.
- `CanesBoxPriceSnapshotRepository` owns MongoDB access for weekly snapshots.
- `model` contains configuration properties, Mongo documents, metro sample
  details, admin review request records, and API response records.

## How Collection Works

The default schedule runs every Monday at 6:00 AM America/Chicago:

```yaml
canes-box-tracker:
  collection:
    cron: "0 0 6 * * MON"
    zone: America/Chicago
```

Each configured metro target represents one sampled metro center. The job uses
the configured latitude and longitude to ask Cane's official ordering API for
nearby restaurants, rejects non-production results such as QA, sandbox, demo, or
non-numeric store references, selects the closest remaining online-orderable
restaurant, fetches that store's official menu, stores successful and failed
samples, records the source used for each metro, and averages only verified
prices. A failure for one metro does not abort the weekly snapshot because the
stored audit trail is more useful with partial data than with no saved history.

Quality status is intentionally separate from fetch status:

- `VERIFIED` is index-eligible. Official API results and manual admin entries
  start verified.
- `PROVISIONAL` is visible but excluded from the public average. Public menu
  fallback matches start provisional because those pages can be stale.
- `EXCLUDED` is visible but excluded from the public average. Failed and
  rejected datapoints use this status.

Each metro datapoint stores the source type, source URL, fetched timestamp, raw
response SHA-256 hash, matched item name, confidence level, price, currency, and
review note when an admin reviews it. This is the evidence trail for future
auditing; do not drop those fields when changing the importer.

The weekly snapshot id is the Monday `weekStartDate`, so rerunning the job for
the same week replaces that week's stored result.

## Configuration

Metro targets live in `application.yml` under `canes-box-tracker.metros` so the
selected store refs can be corrected without changing Java code. The default
set tracks the 25 highest-population U.S. metropolitan statistical areas where
the official Cane's location search returns a production online-orderable store
near the metro center. Rank changes should be based on the current Census metro
population estimates, and store inclusion should be verified against the
official Cane's ordering API before updating configuration. Each target stores
the metro, city center coordinates, restaurant ref, restaurant name, address,
official source URL, and optional `fallback-menu-url`.

The primary official source is the Cane's GraphQL gateway configured by
`graph-ql-url`. Requests mirror the public ordering app headers and only query
fields the official schema exposes; menu products expose `cost`, not generic
`price`, `baseCost`, or `basePrice` fields. The older NomNom
`restaurants/byref` endpoint remains as a secondary official source for
diagnostics and resilience. Public menu fallback URLs remain in configuration
for diagnostics, but `public-menu-fallback-enabled` defaults to `false` because
third-party public menu pages have already returned stale prices.

Public menu fallback pages are not trusted as current data. They can contain
stale third-party menu snapshots, so any fallback price below
`minimum-public-menu-price` is stored as a failed/excluded sample with the raw
response hash preserved for audit. The default floor is `$10.00` because a
single-digit Box Combo price in 2026 is stale enough to be misleading. Official
API prices and manually verified admin entries are not rejected by this fallback
floor. History responses also reapply the same rule to already-saved public-menu
samples, so old bad snapshots do not keep leaking stale prices onto the page.

Reliable index sources are:

- `OFFICIAL_API`: current official ordering API responses.
- `MANUAL_VERIFIED`: admin-entered prices backed by receipt, menu photo, or
  direct store observation evidence.

Everything else must stay non-index-eligible unless an admin explicitly reviews
and promotes it with evidence.

Back Office exposes admin operations to pull a current-week datapoint, approve
or reject provisional metro samples returned by that pull, and enter a manually
verified price with an evidence URL and note.

## Page and Frontend

The public page is `/canes-box-tracker`, served by
`dev.christopherbell.view.tools.ToolsViewController` and rendered by
`templates/canes-box-tracker.html`.

The page script is `static/js/canes-box-tracker.js`. It loads history through
`static/js/lib/api.js`, renders the latest average, renders the large percent
index comparing the latest priced week against the previous priced week, draws a
small inline SVG chart, and lists the latest metro sample statuses, sources, and
quality statuses. The public average and percentage index use verified data
only, so weeks with only provisional/failure data show as insufficient data.

## Update This Doc

Update this README when collection scheduling, metro selection, API shape,
snapshot persistence, or the public tracker page changes.
