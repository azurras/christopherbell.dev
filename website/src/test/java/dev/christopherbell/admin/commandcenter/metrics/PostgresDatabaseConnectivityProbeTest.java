package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.DriverManager;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class PostgresDatabaseConnectivityProbeTest {
  @Test
  void identityPreservesTheInternalDatabaseFailure() {
    var database = mock(DSLContext.class);
    var databaseFailure = new IllegalStateException("database boundary failed");
    when(database.connectionResult(any())).thenThrow(databaseFailure);

    assertThatThrownBy(() -> new PostgresDatabaseConnectivityProbe(database)
        .identity(Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The PostgreSQL identity probe failed.")
        .hasCause(databaseFailure);
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
