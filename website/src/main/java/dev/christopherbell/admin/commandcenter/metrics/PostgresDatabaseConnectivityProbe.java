package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Duration;
import java.sql.Connection;
import java.sql.SQLException;
import org.jooq.DSLContext;

/** Bounded PostgreSQL connectivity probe selected for the PostgreSQL backend. */
@PostgresPersistence
public class PostgresDatabaseConnectivityProbe
    implements DatabaseConnectivityProbe, PersistenceIdentityProbe {
  private static final String IDENTITY_SQL = "select current_database(), version::text "
      + "from public.flyway_schema_history where success "
      + "order by installed_rank desc limit 1";
  private final DSLContext database;

  public PostgresDatabaseConnectivityProbe(DSLContext database) {
    this.database = database;
  }

  @Override
  public String backendName() {
    return "postgresql";
  }

  @Override
  public boolean ping(Duration timeout) {
    try {
      var timeoutSeconds = timeoutSeconds(timeout);
      return database.connectionResult(connection -> {
        try (var statement = connection.prepareStatement("select 1")) {
          statement.setQueryTimeout(timeoutSeconds);
          try (var rows = statement.executeQuery()) {
            return rows.next();
          }
        }
      });
    } catch (RuntimeException failure) {
      return false;
    }
  }

  @Override
  public PersistenceIdentity identity(Duration timeout) {
    try {
      return database.connectionResult(connection -> readIdentity(
          connection, IDENTITY_SQL, timeoutSeconds(timeout)));
    } catch (RuntimeException failure) {
      throw new IllegalStateException("The PostgreSQL identity probe failed.", failure);
    }
  }

  static PersistenceIdentity readIdentity(
      Connection connection, String query, int timeoutSeconds) throws SQLException {
    try (var statement = connection.prepareStatement(query)) {
      statement.setQueryTimeout(timeoutSeconds);
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || rows.getString(1) == null || rows.getString(2) == null) {
          throw new IllegalStateException("The PostgreSQL identity is incomplete.");
        }
        return new PersistenceIdentity("postgresql", rows.getString(1), rows.getString(2));
      }
    }
  }

  private static int timeoutSeconds(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("The PostgreSQL probe timeout must be positive.");
    }
    var seconds = Math.max(1L, timeout.plusMillis(999).toSeconds());
    return Math.toIntExact(Math.min(seconds, Integer.MAX_VALUE));
  }
}
