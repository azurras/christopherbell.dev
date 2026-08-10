# Configuration Mongo

Owns application-wide MongoDB infrastructure configuration.

## What Lives Here

- `MongoAuditingConfig` enables Spring Data Mongo auditing and provides auditor and timestamp sources.
- `domain` provides the canonical kind-scoped persistence boundary for consolidated
  collections. Domain code supplies only mapped domain field names; the boundary owns
  envelope metadata, exact kind criteria, BSON-preserving conversion, and optimistic
  compare-and-set saves.
- `lease` provides reusable, atomic, fixed-name MongoDB leases.
- `migration` applies immutable versioned migrations in ID order and records
  their durable lifecycle before application readiness.

## Design Notes

Keep database feature models in their owning feature package. Migration IDs and
checksums are immutable after merge; add a new migration instead of changing an
applied one. See `docs/operations/mongodb-migrations.md` for authoring and
recovery rules.

Consolidated runtime adapters must use `KindScopedMongoOperations` instead of
accessing a consolidated collection through `MongoTemplate`, `MongoRepository`,
or `MongoCollection` directly. `DomainDocumentKindRegistry` is the immutable
approval boundary associating each exact lower-case logical kind with one
physical collection; it is the only supported way to construct
`DomainDocumentKind` metadata with a schema version and Java mapping type.
