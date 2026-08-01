# Vehicle

Owns vehicle storage, VIN decoding, and vehicle data enrichment.

## What Lives Here

- `VehicleController`, `VehicleControllerExceptionHandler`, and `VehicleService`
  keep the public API surface stable.
- `core` owns CRUD, mapping, repository access, and data collection state reads.
- `vin` owns VIN-only and batch VIN vehicle creation.
- CRUD and VIN persistence outages use the shared service-unavailable contract
  with preserved diagnostic causes and a redacted public response.
- `nhtsa.decode` owns public VIN decode calls, caching, rate limiting, and NHTSA client access.
  Per-instance admission allows at most eight active public VIN upstream calls;
  cached work does not consume that budget. NHTSA response bodies are bounded at
  2 MiB before JSON parsing.
- Public VIN decode anonymous rate-limit keys use the shared trusted-proxy
  client IP resolver.
- `nhtsa.enrichment` owns scheduled enrichment for stored vehicle records.
- `randomvin.importing` owns RandomVIN client access, import state, and minimal vehicle creation.
  VIN source bodies are bounded at 4 KiB.
- `randomvin.policy` owns robots.txt policy evaluation for RandomVIN collection.
  Robots responses are bounded at 256 KiB and an oversized response follows the
  existing fail-closed fetch-failure policy.
- Vehicle DTOs, persistence models, and import state under `model`.

## Update This Doc

Update this README when vehicle fields, VIN validation, NHTSA behavior, RandomVIN behavior, rate limits, or vehicle API contracts change.
