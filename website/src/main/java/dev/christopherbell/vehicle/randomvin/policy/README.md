# RandomVIN Policy

Owns RandomVIN robots.txt evaluation.

## What Lives Here

- Fetching RandomVIN robots.txt with the configured user agent.
- Parsing user-agent groups and allow/disallow rules.
- Returning fail-closed policy decisions when robots checks cannot be read.

Keep import state updates and VIN persistence in `vehicle.randomvin.importing`.
