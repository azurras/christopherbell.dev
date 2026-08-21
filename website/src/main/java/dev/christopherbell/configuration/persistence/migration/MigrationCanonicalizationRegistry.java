package dev.christopherbell.configuration.persistence.migration;

import java.util.List;
import java.util.Map;

/** Closed executable names for the canonical hashing rules used by Task 6. */
final class MigrationCanonicalizationRegistry {
  static final String HASH = "tagged-canonical-sha256-v1";
  static final String SOURCE = "mongo-source-document-v1";
  static final String TARGET = "ordered-relational-rows-v1";

  private MigrationCanonicalizationRegistry() {}

  static void requireSupported(PostgresqlMigrationCatalog.Kind kind) {
    requireSupported(
        kind.canonicalHash(), kind.sourceCanonicalization(), kind.targetCanonicalization());
  }

  static void requireSupported(
      String hash, String sourceCanonicalization, String targetCanonicalization) {
    if (!HASH.equals(hash)
        || !SOURCE.equals(sourceCanonicalization)
        || !TARGET.equals(targetCanonicalization)) {
      throw new IllegalArgumentException(
          "PostgreSQL migration canonicalization declaration is unsupported.");
    }
  }

  static String sourceHash(
      PostgresqlMigrationCatalog.Kind kind, MigrationSourceDocument source) {
    requireSupported(kind);
    return CanonicalMigrationHasher.sha256(Map.of(
        "kind", source.sourceKind(),
        "schemaVersion", source.schemaVersion(),
        "sourceId", source.sourceId(),
        "payload", source.payload()));
  }

  static String targetRowHash(
      PostgresqlMigrationCatalog.Kind kind, MigrationRelationalRow row) {
    requireSupported(kind);
    return CanonicalMigrationHasher.sha256(List.of(
        row.targetSchema(), row.targetTable(), row.ordinal(), row.values()));
  }

  static String targetDocumentHash(
      PostgresqlMigrationCatalog.Kind kind, List<MigrationRelationalRow> rows) {
    requireSupported(kind);
    return CanonicalMigrationHasher.sha256(rows.stream().map(row -> List.of(
        row.targetSchema(), row.targetTable(), row.ordinal(), row.values())).toList());
  }
}
