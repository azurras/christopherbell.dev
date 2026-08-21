package dev.christopherbell.configuration.persistence.migration;

import java.util.LinkedHashMap;
import java.util.Map;

/** One decoded, catalog-owned row ready for transactional publication. */
public record StagedMigrationRow(
    String sourceId,
    String targetSchema,
    String targetTable,
    int targetOrdinal,
    Map<String, Object> values) {
  public StagedMigrationRow {
    values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
