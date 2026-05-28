# RandomVIN Import

Owns opportunistic VIN discovery from randomvin.com.

## What Lives Here

- `importing` owns RandomVIN client access, import throttling, daily caps, cooldowns, permanent-disable behavior, duplicate VIN prevention, and minimal vehicle creation.
- `policy` owns robots.txt fetch and policy evaluation.
- `model` owns RandomVIN import state and robots policy state records.
- Legacy RandomVIN import note cleanup runs from the importing service.

## Update This Doc

Update this README when RandomVIN source rules, robots handling, import caps, cooldown behavior, or imported vehicle defaults change.
