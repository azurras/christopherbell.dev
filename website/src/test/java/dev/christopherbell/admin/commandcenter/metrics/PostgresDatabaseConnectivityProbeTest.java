package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class PostgresDatabaseConnectivityProbeTest {
  @Test
  void identityPreservesTheInternalDatabaseFailure() throws Exception {
    var database = mock(DataSource.class);
    var databaseFailure = new IllegalStateException("database boundary failed");
    when(database.getConnection()).thenThrow(databaseFailure);

    assertThatThrownBy(() -> new PostgresDatabaseConnectivityProbe(database)
        .identity(Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The PostgreSQL identity probe failed.")
        .hasCause(databaseFailure);
  }

  @Test
  void stalledConnectionCheckoutReturnsWithinDeadlineAndClosesLateConnection() throws Exception {
    var database = mock(DataSource.class);
    var connection = mock(Connection.class);
    var checkoutStarted = new CountDownLatch(1);
    var releaseCheckout = new CountDownLatch(1);
    var connectionClosed = new CountDownLatch(1);
    when(database.getConnection()).thenAnswer(invocation -> {
      checkoutStarted.countDown();
      while (true) {
        try {
          releaseCheckout.await();
          break;
        } catch (InterruptedException ignored) {
          // Model a driver or pool that clears cancellation while checkout remains stalled.
        }
      }
      return connection;
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      connectionClosed.countDown();
      return null;
    }).when(connection).close();

    var probe = new PostgresDatabaseConnectivityProbe(database);
    var started = System.nanoTime();
    assertThatThrownBy(() -> probe.identity(Duration.ofMillis(50)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The PostgreSQL identity probe failed.")
        .hasRootCauseInstanceOf(TimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    assertThat(checkoutStarted.getCount()).isZero();

    releaseCheckout.countDown();
    assertThat(connectionClosed.await(1, TimeUnit.SECONDS)).isTrue();
    verify(connection).close();
    verify(connection, never()).prepareStatement(anyString());
  }

  @Test
  void ownedJdbcStatementAppliesTimeoutAndClosesOnQueryFailure() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var queryFailure = new SQLException("query timeout");
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenThrow(queryFailure);

    assertThatThrownBy(() -> PostgresDatabaseConnectivityProbe.readIdentity(
        connection, "select current_database(), '27'", 1))
        .isSameAs(queryFailure);

    verify(statement).setQueryTimeout(1);
    verify(statement).close();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void realQueryTimeoutCancelsWorkAndReleasesTheStatement() throws Exception {
    try (var connection = DriverManager.getConnection(
        System.getenv("SPRING_DATASOURCE_URL"),
        System.getenv("SPRING_DATASOURCE_USERNAME"),
        System.getenv("SPRING_DATASOURCE_PASSWORD"))) {
      var started = System.nanoTime();
      assertThatThrownBy(() -> PostgresDatabaseConnectivityProbe.readIdentity(
          connection, "select current_database(), '27' from pg_sleep(3)", 1))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("canceling statement due to user request");
      var elapsed = Duration.ofNanos(System.nanoTime() - started);
      org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(Duration.ofSeconds(2));

      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("select 1")) {
        org.assertj.core.api.Assertions.assertThat(rows.next()).isTrue();
        org.assertj.core.api.Assertions.assertThat(rows.getInt(1)).isOne();
      }
    }
  }
}
