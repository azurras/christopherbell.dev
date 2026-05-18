# NHTSA Vehicle Integration

Owns enrichment and decoding behavior backed by NHTSA data.

## What Lives Here

- NHTSA client calls and response handling.
- VIN decode caching and cooldown behavior when NHTSA is unavailable or rate limited.
- Scheduled enrichment for stored vehicle records.
- Import state models and decoded-value application rules.

## Update This Doc

Update this README when NHTSA endpoints, cooldown rules, decoded field mapping, enrichment deletion rules, or import state behavior changes.
