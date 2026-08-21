package dev.christopherbell.configuration.persistence.migration;

import java.util.List;

/** Deterministic relational expansion and canonical source digest for one source envelope. */
public record TransformedMigrationDocument(
    String sourceKind, String sourceId, String sourceHash, List<MigrationRelationalRow> rows) {
  public TransformedMigrationDocument {
    rows = List.copyOf(rows);
  }
}
