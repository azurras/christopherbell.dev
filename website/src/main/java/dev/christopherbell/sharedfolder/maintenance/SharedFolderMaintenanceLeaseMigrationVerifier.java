package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/** Exercises the production maintenance-lease adapter under a rollback-only savepoint. */
@PostgresPersistenceSupport
public final class SharedFolderMaintenanceLeaseMigrationVerifier {
  private SharedFolderMaintenanceLeaseMigrationVerifier() {}

  public static boolean verify(Connection connection, String schema) throws SQLException {
    if (connection.getAutoCommit()) {
      return false;
    }
    var savepoint = connection.setSavepoint("verify_maintenance_lease");
    try {
      var database = org.springframework.jdbc.core.simple.JdbcClient.create(
          new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
      var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
          .fromPhysicalSchema(schema);
      var table = schemas.qualifiedTable("shared_folder", "maintenance_lease");
      database.sql("delete from %s where lease_name = :name".formatted(table))
          .param("name", SharedFolderMaintenanceLeaseDocument.ID).update();
      var store = new PostgresSharedFolderMaintenanceLeaseStore(database, schemas);
      var first = store.tryAcquire("owner-a", Duration.ofMinutes(1)).orElseThrow();
      if (first.fenceToken() != 1L
          || store.tryAcquire("owner-b", Duration.ofMinutes(1)).isPresent()
          || store.renew(first, Duration.ofMinutes(2)).isEmpty()) {
        return false;
      }
      database.sql("update %s set expires_at = :epoch where lease_name = :name".formatted(table))
          .param("epoch", java.time.Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC))
          .param("name", SharedFolderMaintenanceLeaseDocument.ID).update();
      var second = store.tryAcquire("owner-b", Duration.ofMinutes(1)).orElseThrow();
      return second.fenceToken() == 2L
          && store.renew(first, Duration.ofMinutes(1)).isEmpty()
          && !store.release(first)
          && store.release(second);
    } finally {
      connection.rollback(savepoint);
      connection.releaseSavepoint(savepoint);
    }
  }

}
