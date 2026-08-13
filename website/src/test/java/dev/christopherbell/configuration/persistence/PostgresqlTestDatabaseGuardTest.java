package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
}
