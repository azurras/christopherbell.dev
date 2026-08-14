package dev.christopherbell.configuration.persistence.migration;

/** Value-free operator error for a rejected migration boundary. */
public final class MigrationPreflightException extends IllegalArgumentException {
  MigrationPreflightException(String message) {
    super(message);
  }
}
