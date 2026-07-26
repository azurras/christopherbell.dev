package dev.christopherbell.configuration.mongo.migration;

/** Durable lifecycle states for a migration record. */
public enum MigrationStatus {
  RUNNING,
  APPLIED,
  FAILED
}
