package dev.christopherbell.configuration.persistence.migration;

/** Narrow read-only source capability: there is intentionally no Mongo mutation method. */
public interface MigrationSourceReader {
  SourceBatch readAfter(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      String cursor,
      int limit);
}
