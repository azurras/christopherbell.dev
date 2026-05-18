# Vehicle

Owns vehicle storage, VIN decoding, and vehicle data enrichment.

## What Lives Here

- Vehicle CRUD APIs and validation.
- Public VIN decode endpoint with rate limiting and cache support.
- NHTSA VIN decoding/enrichment integration under `nhtsa`.
- RandomVIN import workflow under `randomvin`.
- Vehicle DTOs, persistence models, and import state under `model`.

## Update This Doc

Update this README when vehicle fields, VIN validation, NHTSA behavior, RandomVIN behavior, rate limits, or vehicle API contracts change.
