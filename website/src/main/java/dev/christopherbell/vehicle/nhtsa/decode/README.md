# NHTSA VIN Decode

Owns public VIN decode behavior backed by NHTSA vPIC.

## What Lives Here

- `NhtsaVinClient` calls the NHTSA batch decode endpoint.
- `VehicleVinDecodeService` validates decode input, applies per-client rate
  limits, reads/writes decode cache entries, and cools down when NHTSA is unavailable.
- Decode-specific exceptions and cache/rate-limit repositories.

Keep scheduled stored-vehicle enrichment in `vehicle.nhtsa.enrichment`.
