package dev.christopherbell.configuration.persistence.migration;

/** Optional source-wide validation that rejects undeclared envelopes before staging. */
public interface MigrationSourceCatalogGuard {
  void requireOnlyCatalogKinds(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog catalog);
}
