package dev.christopherbell.configuration.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies a PostgreSQL test connection before test fixtures can mutate it. */
public final class PostgresqlTestDatabaseGuard {
  private PostgresqlTestDatabaseGuard() {}

  public static void verify(JdbcTemplate jdbc, PostgresqlTestDatabaseGuardProperties properties) {
    try {
      var identity = new PostgresqlDatabaseIdentity(
          jdbc.queryForObject("select current_database()", String.class),
          jdbc.queryForObject("select current_schema()", String.class));
      requireSafeIdentity(identity, properties);
      var existingSchema = jdbc.queryForObject(
          "select to_regnamespace(?)::text", String.class, identity.schema());
      if (existingSchema == null) {
        throw new IllegalStateException("PostgreSQL current_schema is unavailable for test fixtures.");
      }
    } catch (DataAccessException failure) {
      throw new PostgresqlDatabaseIdentityCheckException(
          new PostgresqlDatabaseIdentityCheckCause("DATA_ACCESS"));
    }
  }

  public static void requireSafeIdentity(
      PostgresqlDatabaseIdentity identity, PostgresqlTestDatabaseGuardProperties properties) {
    if (!properties.requiredDatabase().equals(identity.database())) {
      throw new IllegalStateException("PostgreSQL current_database is not the required test database.");
    }
    if (identity.schema() == null || !identity.schema().startsWith(properties.schemaPrefix())) {
      throw new IllegalStateException("PostgreSQL current_schema is not a disposable test schema.");
    }
  }
}
