package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Exercises fixture isolation only when an operator explicitly supplies a verified test database. */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresqlTestSchemaIsolationIntegrationTest {
  private static final PostgresqlTestDatabaseGuardProperties PROPERTIES =
      new PostgresqlTestDatabaseGuardProperties("test", "cbtest_");

  @Test
  void guardedDisposableSchemasKeepConcurrentFixturesIsolated() throws SQLException {
    var firstSchema = PostgresqlTestSchemaName.create(PROPERTIES.schemaPrefix()).value();
    var secondSchema = PostgresqlTestSchemaName.create(PROPERTIES.schemaPrefix()).value();
    var url = requiredEnvironment("SPRING_DATASOURCE_URL");
    var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
    var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");

    try (var first = DriverManager.getConnection(url, username, password);
         var second = DriverManager.getConnection(url, username, password)) {
      requireTestDatabase(first);
      requireTestDatabase(second);
      createSchema(first, firstSchema);
      createSchema(second, secondSchema);
      try {
        first.setSchema(firstSchema);
        second.setSchema(secondSchema);
        requireSafeIdentity(first);
        requireSafeIdentity(second);
        createFixture(first, 101);
        createFixture(second, 202);
        assertThat(unqualifiedFixtureValue(first)).isEqualTo(101);
        assertThat(unqualifiedFixtureValue(second)).isEqualTo(202);
      } finally {
        dropSchema(first, firstSchema);
        dropSchema(second, secondSchema);
      }
    }
  }

  private static void requireSafeIdentity(java.sql.Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
         var result = statement.executeQuery("select current_database(), current_schema()")) {
      result.next();
      PostgresqlTestDatabaseGuard.requireSafeIdentity(
          new PostgresqlDatabaseIdentity(result.getString(1), result.getString(2)), PROPERTIES);
    }
  }

  private static void requireTestDatabase(java.sql.Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
         var result = statement.executeQuery("select current_database()")) {
      result.next();
      PostgresqlTestDatabaseGuard.requireSafeIdentity(
          new PostgresqlDatabaseIdentity(result.getString(1), "cbtest_identity_probe"), PROPERTIES);
    }
  }

  private static void createSchema(java.sql.Connection connection, String schema) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("create schema \"" + schema + "\"");
    }
  }

  private static void createFixture(java.sql.Connection connection, int value) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("create table fixture (value integer not null)");
      statement.execute("insert into fixture (value) values (" + value + ")");
    }
  }

  private static int unqualifiedFixtureValue(java.sql.Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
         var result = statement.executeQuery("select value from fixture")) {
      result.next();
      return result.getInt(1);
    }
  }

  private static void dropSchema(java.sql.Connection connection, String schema) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("drop schema if exists \"" + schema + "\" cascade");
    }
  }

  private static String requiredEnvironment(String key) {
    var value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(key + " must be set when PostgreSQL integration tests are enabled.");
    }
    return value;
  }
}
