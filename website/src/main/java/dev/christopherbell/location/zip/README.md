# Location ZIP

Owns ZIP coordinate lookup and Census Gazetteer imports.

## What Lives Here

- `LocationController` exposes public ZIP lookup and admin Census import endpoints.
- `ZipCoordinateService` validates ZIP input, maps stored coordinates, and refreshes imported Census rows.
- `ZipCoordinateGazetteerReader` parses the bundled Census Gazetteer text file before database mutation.
- `ZipCoordinateRepository` owns MongoDB access for ZIP coordinate documents.

## Design Notes

This package exists because Location may grow beyond ZIP support later. Keep ZIP-specific validation, import parsing, and persistence here so future location features can be added without expanding a flat root package.

## Update This Doc

Update this README when ZIP lookup rules, import source parsing, endpoint behavior, or persistence rules change.
