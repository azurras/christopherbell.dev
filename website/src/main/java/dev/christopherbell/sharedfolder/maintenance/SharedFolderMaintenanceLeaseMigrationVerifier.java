package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.jooq.SQLDialect;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

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
      var database = DSL.using(connection, SQLDialect.POSTGRES, settings(schema));
      var table = dev.christopherbell.persistence.jooq.shared_folder.Tables.MAINTENANCE_LEASE;
      database.deleteFrom(table).where(table.LEASE_NAME.eq(
          SharedFolderMaintenanceLeaseDocument.ID)).execute();
      var store = new PostgresSharedFolderMaintenanceLeaseStore(database);
      var first = store.tryAcquire("owner-a", Duration.ofMinutes(1)).orElseThrow();
      if (first.fenceToken() != 1L
          || store.tryAcquire("owner-b", Duration.ofMinutes(1)).isPresent()
          || store.renew(first, Duration.ofMinutes(2)).isEmpty()) {
        return false;
      }
      database.update(table)
          .set(table.EXPIRES_AT, java.time.Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC))
          .where(table.LEASE_NAME.eq(SharedFolderMaintenanceLeaseDocument.ID)).execute();
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

  private static Settings settings(String schema) {
    if (!schema.endsWith("shared_folder")) {
      throw new IllegalArgumentException("Unexpected PostgreSQL schema.");
    }
    return new Settings().withRenderMapping(new RenderMapping().withSchemata(
        new MappedSchema().withInput("shared_folder").withOutput(schema)));
  }
}
