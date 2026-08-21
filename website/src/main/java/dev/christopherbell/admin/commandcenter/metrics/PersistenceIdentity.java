package dev.christopherbell.admin.commandcenter.metrics;

import java.util.Objects;

/** Safe, value-limited identity for the selected persistence backend. */
public record PersistenceIdentity(String backend, String database, String schemaVersion) {
  public PersistenceIdentity {
    requireValue(backend, "backend");
    requireValue(database, "database");
    requireValue(schemaVersion, "schema version");
  }

  private static void requireValue(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("The persistence " + label + " is invalid.");
    }
  }
}
