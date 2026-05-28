# Configuration Mongo

Owns MongoDB infrastructure configuration.

## What Lives Here

- `MongoAuditingConfig` enables Spring Data Mongo auditing and provides auditor and timestamp sources.

## Design Notes

Keep database feature models in their owning feature package. This package is only for application-wide Mongo configuration.
