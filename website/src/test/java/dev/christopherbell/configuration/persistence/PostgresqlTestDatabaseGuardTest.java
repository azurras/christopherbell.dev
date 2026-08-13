package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresqlTestDatabaseGuardTest {
  private static final PostgresqlTestDatabaseGuardProperties PROPERTIES =
      new PostgresqlTestDatabaseGuardProperties("test", "cbtest_");

  @Test
  void acceptsTheRequiredDatabaseAndDisposableSchema() {
    assertThatCode(() -> PostgresqlTestDatabaseGuard.requireSafeIdentity(
        new PostgresqlDatabaseIdentity("test", "cbtest_accounts"), PROPERTIES))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsEveryDatabaseOtherThanTestWithoutLeakingCredentials() {
    String unsafeDatabase = "production-password=do-not-echo";

    assertThatThrownBy(() -> PostgresqlTestDatabaseGuard.requireSafeIdentity(
        new PostgresqlDatabaseIdentity(unsafeDatabase, "cbtest_accounts"), PROPERTIES))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("current_database")
        .hasMessageNotContaining(unsafeDatabase)
        .hasMessageNotContaining("password");
  }

  @Test
  void rejectsEmptyOrNonDisposableSchemasBeforeFixturesCanBeWritten() {
    assertThatThrownBy(() -> PostgresqlTestDatabaseGuard.requireSafeIdentity(
        new PostgresqlDatabaseIdentity("test", ""), PROPERTIES))
        .hasMessageContaining("current_schema");
    assertThatThrownBy(() -> PostgresqlTestDatabaseGuard.requireSafeIdentity(
        new PostgresqlDatabaseIdentity("test", "public"), PROPERTIES))
        .hasMessageContaining("current_schema");
  }

  @Test
  void rejectsAnyConfigurableTargetOtherThanTestAndCbtestSchemas() {
    assertThatThrownBy(() -> new PostgresqlTestDatabaseGuardProperties("staging", "cbtest_"))
        .hasMessageContaining("required-database");
    assertThatThrownBy(() -> new PostgresqlTestDatabaseGuardProperties("test", "other_"))
        .hasMessageContaining("schema-prefix");
  }

  @Test
  void redactsDatabaseAccessFailureWhilePreservingItsSafeCategory() {
    var jdbc = mock(JdbcTemplate.class);
    var unsafeMessage = "jdbc:postgresql://db.example/test?password=do-not-echo";
    when(jdbc.queryForObject("select current_database()", String.class))
        .thenThrow(new DataAccessResourceFailureException(unsafeMessage));

    assertThatThrownBy(() -> PostgresqlTestDatabaseGuard.verify(jdbc, PROPERTIES))
        .isInstanceOf(PostgresqlDatabaseIdentityCheckException.class)
        .hasCauseInstanceOf(PostgresqlDatabaseIdentityCheckCause.class)
        .hasMessageContaining("DATA_ACCESS")
        .hasMessageNotContaining(unsafeMessage)
        .hasMessageNotContaining("password");
  }
}
