# Vehicle Core

Owns stored vehicle CRUD and vehicle data collection state reads.

## What Lives Here

- `VehicleCrudService` validates full vehicle create/update requests and handles
  read, update, and delete behavior.
- `VehicleRepository` owns MongoDB access for vehicle documents.
- `VehicleMapper` converts between vehicle entities and API DTOs.
- `VehicleDataCollectionStateService` combines NHTSA and RandomVIN import state
  for the back office.

Keep VIN-only creation in `vehicle.vin` and external source behavior in the
NHTSA or RandomVIN subpackages.
