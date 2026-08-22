package dev.christopherbell.account.auth;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresAccountLoginContractTest implements AccountLoginParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static AccountRepository accounts;
  private static AccountLoginStore logins;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    logins = new PostgresAccountLoginStore(
        database.managedJdbc(), database.schemas(), database.transactions());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) {
      database.close();
    }
    if (schemas != null) {
      schemas.close();
    }
  }

  @Override public AccountRepository accounts() { return accounts; }
  @Override public AccountLoginStore loginStore() { return logins; }
}
