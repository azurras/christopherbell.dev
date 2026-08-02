# Vehicle VIN

Owns vehicle creation flows that begin with VIN input only.

## What Lives Here

- Single VIN normalization and validation before vehicle creation.
- Batch VIN normalization, duplicate detection, and existing-VIN checks.
- Minimal vehicle records created with only VIN and audit timestamps.

Keep public VIN decode calls in `vehicle.nhtsa.decode`; decode lookups do not
create stored vehicle records.

The existing `POST /api/vehicles/2026-05-09/vin/decode` contract remains the
single-VIN boundary. `POST /api/vehicles/2026-07-26/vin/decode/batch` accepts up
to `vehicles.vin-decoder.max-batch-size` positions and returns one ordered
success or specific error for every submitted position. Each position consumes
one rate-limit token.

Process-local per-client decode buckets expire after two inactive rate-limit
windows and are capped by `vehicles.vin-decoder.maximum-buckets` (10,000 by
default). When the cap is reached, the least recently used bucket is evicted;
that rare client receives a fresh local allowance on its next request.

Decode cache entries are reused only while their `decoderVersion` matches
`vehicles.vin-decoder.decoder-version` and `expiresOn` is in the future. Fresh
entries live for `vehicles.vin-decoder.cache-ttl`; failed stale refreshes do not
extend those timestamps or return stale data.
