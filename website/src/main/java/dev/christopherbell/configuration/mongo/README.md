# Configuration Mongo

Owns application-wide MongoDB infrastructure configuration.

## What Lives Here

- `MongoAuditingConfig` enables Spring Data Mongo auditing and provides auditor and timestamp sources.
- `lease` provides reusable, atomic, fixed-name MongoDB leases.
- `migration` applies immutable versioned migrations in ID order and records
  their durable lifecycle before application readiness.

## Design Notes

Keep database feature models in their owning feature package. Migration IDs and
checksums are immutable after merge; add a new migration instead of changing an
applied one. See `docs/operations/mongodb-migrations.md` for authoring and
recovery rules.
