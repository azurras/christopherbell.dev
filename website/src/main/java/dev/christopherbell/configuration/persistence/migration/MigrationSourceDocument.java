package dev.christopherbell.configuration.persistence.migration;

import java.util.LinkedHashMap;
import java.util.Map;

/** One validated logical source envelope after Mongo metadata has been separated. */
public record MigrationSourceDocument(
    String sourceKind, int schemaVersion, String sourceId, Map<String, Object> payload) {
  public MigrationSourceDocument {
    payload = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
