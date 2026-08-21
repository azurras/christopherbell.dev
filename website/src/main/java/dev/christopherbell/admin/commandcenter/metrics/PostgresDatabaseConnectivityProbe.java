package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Bounded PostgreSQL connectivity probe selected for the PostgreSQL backend. */
@PostgresPersistence
public class PostgresDatabaseConnectivityProbe
    implements DatabaseConnectivityProbe, PersistenceIdentityProbe {
  private static final String IDENTITY_SQL = "select current_database(), version::text "
      + "from public.flyway_schema_history where success "
      + "order by installed_rank desc limit 1";
  private final DataSource database;

  public PostgresDatabaseConnectivityProbe(DataSource database) {
    this.database = database;
  }

  @Override
  public String backendName() {
    return "postgresql";
  }

  @Override
  public boolean ping(Duration timeout) {
    try {
      return withinDeadline(timeout, (connection, timeoutSeconds) -> {
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
      return withinDeadline(timeout, (connection, timeoutSeconds) ->
          readIdentity(connection, IDENTITY_SQL, timeoutSeconds));
    } catch (RuntimeException failure) {
      throw new IllegalStateException("The PostgreSQL identity probe failed.", failure);
    }
  }

  private <T> T withinDeadline(Duration timeout, SqlOperation<T> operation) {
    var queryTimeoutSeconds = timeoutSeconds(timeout);
    var timeoutNanos = timeout.toNanos();
    var cancelled = new AtomicBoolean();
    var task = new FutureTask<T>(() -> {
      try (var connection = database.getConnection()) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
          throw new CancellationException("The PostgreSQL probe was cancelled during checkout.");
        }
        return operation.apply(connection, queryTimeoutSeconds);
      }
    });
    Thread.ofVirtual().name("postgresql-connectivity-probe").start(task);
    try {
      return task.get(timeoutNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      cancelled.set(true);
      task.cancel(true);
      throw new CompletionException(failure);
    } catch (InterruptedException failure) {
      cancelled.set(true);
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new CompletionException(failure);
    } catch (ExecutionException failure) {
      var cause = failure.getCause();
      if (cause instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new CompletionException(cause);
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

  @FunctionalInterface
  @PostgresPersistenceSupport
  private interface SqlOperation<T> {
    T apply(Connection connection, int timeoutSeconds) throws SQLException;
  }
}
