package dev.christopherbell.configuration.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Exercises the production application-lease adapter under a rollback-only savepoint. */
@PostgresPersistenceSupport
public final class ApplicationLeaseMigrationVerifier {
  private ApplicationLeaseMigrationVerifier() {}

  public static boolean verify(Connection connection, String platformSchema) throws SQLException {
    if (connection.getAutoCommit()) {
      return false;
    }
    var savepoint = connection.setSavepoint("verify_application_lease");
    try {
      var database = JdbcClient.create(new SingleConnectionDataSource(connection, true));
      var schemas = PostgresqlSchemaNames.fromPhysicalSchema(platformSchema);
      var store = new PostgresApplicationLeaseStore(database, schemas);
      var leaseName = "migration-verifier-" + UUID.randomUUID();
      var first = store.tryAcquire(leaseName, "owner-a", Duration.ofMinutes(1)).orElseThrow();
      if (first.fenceToken() != 1L
          || store.tryAcquire(leaseName, "owner-b", Duration.ofMinutes(1)).isPresent()
          || store.renew(first, Duration.ofMinutes(2)).isEmpty()) {
        return false;
      }
      database.sql("update %s set expires_at = :epoch where lease_name = :leaseName".formatted(
              schemas.qualifiedTable("platform", "application_lease")))
          .param("epoch", java.time.Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC))
          .param("leaseName", leaseName)
          .update();
      var second = store.tryAcquire(leaseName, "owner-b", Duration.ofMinutes(1)).orElseThrow();
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
