package dev.christopherbell.configuration.persistence.migration;

/** Value-free failure for an unknown or invalid source transformation. */
public final class MigrationTransformationException extends IllegalArgumentException {
  MigrationTransformationException() {
    super("PostgreSQL migration source document is invalid.");
  }
}
