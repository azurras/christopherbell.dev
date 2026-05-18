# Vehicle Models

Owns vehicle persistence, VIN decode, and data collection API records.

## What Lives Here

- `Vehicle` is the Mongo-backed vehicle entity.
- `VehicleCreateRequest`, `VehicleUpdateRequest`, `VehicleVinRequest`, and
  `VehicleVinBatchRequest` carry write/decode input.
- `VehicleDetail`, `VehicleVinDecodeResponse`, and `VehicleDataCollectionState`
  are API response shapes.
- `VehicleProperties` binds NHTSA and RandomVIN configuration.
- `VehicleVinDecodeCache` stores cached VIN decode responses.

## Design Notes

- VIN decode cache data is separate from saved vehicles so a lookup does not
  imply a user-managed vehicle record.
- Configuration properties stay in this package because the vehicle feature owns
  external collection behavior.
- Request records keep manual vehicle creation separate from VIN-only decode and
  import flows.

## Update This Doc

Update this README when vehicle fields, VIN request/response shapes, cache
behavior, or collection configuration changes.
