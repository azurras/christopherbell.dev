# Location Models

Owns Location persistence and API shapes.

## What Lives Here

- `ZipCoordinate` stores one persisted ZIP/ZCTA coordinate origin keyed by ZIP
  code.
- `ZipCoordinateDetail` is the public one-ZIP lookup response payload.
- `ZipCoordinateImportResult` is the admin Census refresh summary payload.

## Design Notes

- ZIP codes remain strings so leading zeroes are not lost.
- Source and source year stay on coordinate details so callers can distinguish
  Census ZCTA coordinates from any future Location source.

## Update This Doc

Update this README when Location persistence fields or API models change.
