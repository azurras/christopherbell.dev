package dev.christopherbell.configuration.persistence.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

@FunctionalInterface
public interface MigrationRowPublisher {
  void publish(
      Connection connection,
      String schemaPrefix,
      PostgresqlMigrationCatalog.Kind kind,
      List<StagedMigrationRow> rows) throws SQLException;
}
