package dev.christopherbell.configuration.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

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
      var database = DSL.using(connection, SQLDialect.POSTGRES, settings(platformSchema));
      var store = new PostgresApplicationLeaseStore(database);
      var leaseName = "migration-verifier-" + UUID.randomUUID();
      var first = store.tryAcquire(leaseName, "owner-a", Duration.ofMinutes(1)).orElseThrow();
      if (first.fenceToken() != 1L
          || store.tryAcquire(leaseName, "owner-b", Duration.ofMinutes(1)).isPresent()
          || store.renew(first, Duration.ofMinutes(2)).isEmpty()) {
        return false;
      }
      database.update(dev.christopherbell.persistence.jooq.platform.Tables.APPLICATION_LEASE)
          .set(dev.christopherbell.persistence.jooq.platform.Tables.APPLICATION_LEASE.EXPIRES_AT,
              java.time.Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC))
          .where(dev.christopherbell.persistence.jooq.platform.Tables.APPLICATION_LEASE.LEASE_NAME
              .eq(leaseName)).execute();
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

  private static Settings settings(String schema) {
    if (!schema.endsWith("platform")) {
      throw new IllegalArgumentException("Unexpected PostgreSQL schema.");
    }
    return new Settings().withRenderMapping(new RenderMapping().withSchemata(
        new MappedSchema().withInput("platform").withOutput(schema)));
  }
}
