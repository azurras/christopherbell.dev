# NHTSA Enrichment

Owns scheduled enrichment of stored vehicles from NHTSA data.

## What Lives Here

- `NhtsaVinEnrichmentService` finds stored VINs that still need enrichment.
- Batch decode orchestration and decoded-value application rules.
- Import state persistence, cooldown, and permanent-disable behavior.
- Deletion of stored vehicle records when NHTSA cannot return usable data.

Keep one-off public VIN decode requests in `vehicle.nhtsa.decode`.

The scheduled enrichment job stays quiet at info level when no VINs are due. It logs actual enrichment, deletion, and failure events rather than every scheduler start and completion.
