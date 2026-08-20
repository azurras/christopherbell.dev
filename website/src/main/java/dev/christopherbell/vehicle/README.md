# Vehicle

Owns vehicle storage, VIN decoding, and vehicle data enrichment.

## What Lives Here

- `VehicleController`, `VehicleControllerExceptionHandler`, and `VehicleService`
  keep the public API surface stable.
- `api.VehicleMigrationVerifier` publishes real vehicle, VIN, cache, import, and decode adapter
  parity operations for the guarded MongoDB-to-PostgreSQL cutover.
- `core` owns CRUD, mapping, repository access, and data collection state reads.
- `vin` owns VIN-only and batch VIN vehicle creation.
- CRUD and VIN persistence outages use the shared service-unavailable contract
  with preserved diagnostic causes and a redacted public response.
- `nhtsa.decode` owns public VIN decode calls, caching, rate limiting, and NHTSA client access.
  Per-instance admission allows at most eight active public VIN upstream calls;
  cached work does not consume that budget. NHTSA response bodies are bounded at
  2 MiB before JSON parsing, and the configured request deadline covers a body
  that stalls after response headers. Timeout failures release admission permits.
- Public VIN decode anonymous rate-limit keys use the shared trusted-proxy
  client IP resolver.
- `nhtsa.enrichment` owns scheduled enrichment for stored vehicle records.
- `randomvin.importing` owns RandomVIN client access, import state, and minimal vehicle creation.
  VIN source bodies are bounded at 4 KiB and remain covered by the configured
  request deadline through full-body completion.
- NHTSA and RandomVIN import state share `vehicle_import_state`; startup
  validation requires their configured `_id` values to remain distinct.
- `randomvin.policy` owns robots.txt policy evaluation for RandomVIN collection.
  Robots responses are bounded at 256 KiB and an oversized response follows the
  existing fail-closed fetch-failure policy. A body timeout follows the same
  fail-closed policy.
- Vehicle DTOs, persistence models, and import state under `model`.

## Update This Doc

Update this README when vehicle fields, VIN validation, NHTSA behavior, RandomVIN behavior, rate limits, or vehicle API contracts change.
