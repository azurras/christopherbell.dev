package dev.christopherbell.configuration.persistence;

/** Closed set of persistence adapters during the MongoDB-to-PostgreSQL transition. */
public enum PersistenceBackend {
  MONGODB,
  POSTGRESQL
}
