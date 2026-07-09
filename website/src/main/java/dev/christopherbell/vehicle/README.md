# Vehicle

Owns vehicle storage, VIN decoding, and vehicle data enrichment.

## What Lives Here

- `VehicleController`, `VehicleControllerExceptionHandler`, and `VehicleService`
  keep the public API surface stable.
- `core` owns CRUD, mapping, repository access, and data collection state reads.
- `vin` owns VIN-only and batch VIN vehicle creation.
- `nhtsa.decode` owns public VIN decode calls, caching, rate limiting, and NHTSA client access.
- Public VIN decode anonymous rate-limit keys use the shared trusted-proxy
  client IP resolver.
- `nhtsa.enrichment` owns scheduled enrichment for stored vehicle records.
- `randomvin.importing` owns RandomVIN client access, import state, and minimal vehicle creation.
- `randomvin.policy` owns robots.txt policy evaluation for RandomVIN collection.
- Vehicle DTOs, persistence models, and import state under `model`.

## Update This Doc

Update this README when vehicle fields, VIN validation, NHTSA behavior, RandomVIN behavior, rate limits, or vehicle API contracts change.
