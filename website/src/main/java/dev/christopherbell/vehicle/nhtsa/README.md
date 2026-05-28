# NHTSA Vehicle Integration

Owns enrichment and decoding behavior backed by NHTSA data.

## What Lives Here

- `decode` owns NHTSA client calls, public VIN decode caching, rate limiting, and cooldown behavior.
- `enrichment` owns scheduled enrichment for stored vehicle records, import state, and decoded-value application rules.
- `model` owns NHTSA import state records.

## Update This Doc

Update this README when NHTSA endpoints, cooldown rules, decoded field mapping, enrichment deletion rules, or import state behavior changes.
