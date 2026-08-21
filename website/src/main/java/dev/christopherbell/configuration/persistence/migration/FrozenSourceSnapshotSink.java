package dev.christopherbell.configuration.persistence.migration;

import java.util.List;

/** Transaction-bound receiver for the exact relational snapshot derived under the Mongo freeze. */
@FunctionalInterface
public interface FrozenSourceSnapshotSink {
  void accept(
      PostgresqlMigrationCatalog.Kind kind,
      List<TransformedMigrationDocument> documents);
}
