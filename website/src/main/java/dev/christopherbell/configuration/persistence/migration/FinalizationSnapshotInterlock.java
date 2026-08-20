package dev.christopherbell.configuration.persistence.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Deterministic transaction seam immediately before publication of a verified frozen snapshot. */
@FunctionalInterface
interface FinalizationSnapshotInterlock {
  FinalizationSnapshotInterlock NONE = (connection, context) -> {};

  void beforePublication(Connection connection, ValidatedMigrationContext context)
      throws SQLException;
}
