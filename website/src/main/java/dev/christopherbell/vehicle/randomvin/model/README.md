# RandomVIN Models

Owns persistence state for RandomVIN collection.

## What Lives Here

- `RandomVinImportState` records scheduler, cooldown, and permanent-disable
  state for RandomVIN collection.
- `RandomVinRobotsPolicyState` records robots.txt policy evaluation state.

## Design Notes

- Robots policy state is persisted separately because collection should honor
  upstream policy consistently across scheduler runs.
- Cooldown and permanent-disable fields keep importer behavior conservative when
  upstream responses indicate limits or denial.

## Update This Doc

Update this README when RandomVIN scheduler state, robots policy behavior, or
failure handling changes.
