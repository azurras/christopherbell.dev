# Vehicle VIN

Owns vehicle creation flows that begin with VIN input only.

## What Lives Here

- Single VIN normalization and validation before vehicle creation.
- Batch VIN normalization, duplicate detection, and existing-VIN checks.
- Minimal vehicle records created with only VIN and audit timestamps.

Keep public VIN decode calls in `vehicle.nhtsa.decode`; decode lookups do not
create stored vehicle records.
