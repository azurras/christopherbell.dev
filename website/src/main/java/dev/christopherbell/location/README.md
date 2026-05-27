# Location

Owns reusable Location reference data and APIs.

## What Lives Here

- Census ZIP Code Tabulation Area coordinate persistence in MongoDB.
- `ZipCoordinateGazetteerReader`, which parses the bundled Census Gazetteer
  resource before imports mutate stored coordinates.
- `ZipCoordinateService`, which validates ZIP and ZIP+4 lookup input and
  refreshes Census ZIP coordinate rows by ZIP key.
- `LocationController`, which exposes the public ZIP lookup endpoint and the
  admin Census import endpoint.
- The `/zip-coordinates` tool page, which exposes the public lookup endpoint for
  manual ZIP coordinate checks.

## ZIP Coordinates

- `GET /api/location/zip/{zipCode}` returns one imported ZIP coordinate.
- The Tools dropdown links to `/zip-coordinates` for a browser UI around this
  endpoint.
- Lookup accepts five-digit ZIP input or ZIP+4 input normalized to the first
  five digits.
- The public payload returns source and source year because imported rows are
  Census ZCTA internal points, not USPS delivery-area geometry.
- `POST /api/location/zip/import/census` is admin-only and refreshes Mongo rows
  from `location/2025_Gaz_zcta_national.txt`.
- The refresh reports processed, created, updated, unchanged, and deleted rows.
  Stale deletes only target persisted Census rows.

## Consumers

What's For Lunch uses this service to resolve ZIP search origins. WFL still owns
restaurant candidate queries, distance checks, filters, and picks.

## Update This Doc

Update this README when Location data sources, ZIP validation, import behavior,
public Location endpoints, or Location consumers change.
