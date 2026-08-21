package dev.christopherbell.configuration.persistence.migration;

/** One exact catalog-bound transformation from a Mongo kind to relational rows. */
public interface MigrationTransformer {
  String sourceKind();

  TransformedMigrationDocument transform(MigrationSourceDocument source);
}
