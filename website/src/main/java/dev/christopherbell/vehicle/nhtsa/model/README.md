# NHTSA Models

Owns persistence state for NHTSA enrichment.

## What Lives Here

- `NhtsaVinImportState` records cooldown, permanent-disable, and scheduler state
  for NHTSA-backed enrichment.

## Design Notes

- Import state is persisted so scheduler decisions survive application restarts.
- Cooldown and permanent-disable fields protect the app from repeatedly calling
  an upstream service after rate-limit or authorization failures.

## Update This Doc

Update this README when NHTSA scheduler state or upstream failure handling
changes.
