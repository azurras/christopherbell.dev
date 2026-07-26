# RandomVIN Import

Owns opportunistic VIN discovery from randomvin.com.

## What Lives Here

- `importing` owns RandomVIN client access, import throttling, daily caps, cooldowns, permanent-disable behavior, duplicate VIN prevention, and minimal vehicle creation.
- `policy` owns robots.txt fetch and policy evaluation.
- `model` owns RandomVIN import state and robots policy state records.
- Legacy RandomVIN import note cleanup runs from the importing service.

## Scheduling Safety

RandomVIN collection is disabled by default. The checked configuration uses a
`PT1M` initial delay, `PT10M` fixed delay, and `PT1M` minimum safe delay;
startup rejects an enabled schedule below that minimum. Each run also honors
the configured daily cap, robots policy, request timeout, and renewable
`vehicles.random-vin.lease-duration` Mongo lease. Lease contention is recorded
as `SKIPPED_LOCKED` without contacting RandomVIN or mutating repository state.

## Update This Doc

Update this README when RandomVIN source rules, robots handling, import caps, cooldown behavior, or imported vehicle defaults change.
