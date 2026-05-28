# RandomVIN Importing

Owns scheduled collection of generated VINs from randomvin.com.

## What Lives Here

- `RandomVinClient` fetches VIN responses from RandomVIN.
- `RandomVinImportService` enforces enablement, daily caps, cooldowns,
  permanent-disable behavior, robots policy decisions, and duplicate VIN checks.
- `RandomVinImportStateRepository` persists import throttling state.
- Legacy RandomVIN import note cleanup.

Keep robots.txt parsing in `vehicle.randomvin.policy`.
