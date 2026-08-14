package dev.christopherbell.configuration.persistence.migration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical run-owned row representation awaiting reconciliation and publication. */
public record MigrationRelationalRow(
    String targetSchema,
    String targetTable,
    String sourceId,
    int ordinal,
    Map<String, Object> values) {
  public MigrationRelationalRow {
    values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
