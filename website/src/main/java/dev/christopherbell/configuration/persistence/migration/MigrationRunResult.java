package dev.christopherbell.configuration.persistence.migration;

import java.util.List;

/** Redaction-safe deterministic summary for CLI rendering and automation. */
public record MigrationRunResult(
    PostgresqlMigrationCommand command,
    List<MigrationKindStatus> kinds,
    String statusDigest) {
  public MigrationRunResult {
    kinds = List.copyOf(kinds);
  }
}
