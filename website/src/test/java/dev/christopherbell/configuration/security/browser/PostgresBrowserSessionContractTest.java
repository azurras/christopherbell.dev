package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresBrowserSessionContractTest implements BrowserSessionParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static BrowserSessionRepository sessions;
  private static BrowserSessionAuthenticationStore authentication;
  private static BrowserSessionActivityStore activity;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    sessions = database.bean(PostgresBrowserSessionRepository.class);
    authentication = new PostgresBrowserSessionAuthenticationStore(
        database.jdbc(), database.schemas());
    activity = new PostgresBrowserSessionActivityStore(database.jdbc(), database.schemas());
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

  @Override public BrowserSessionRepository sessions() { return sessions; }
  @Override public BrowserSessionAuthenticationStore authentication() { return authentication; }
  @Override public BrowserSessionActivityStore activity() { return activity; }
  @Override public void createAccount(Account account) { accounts.save(account); }
  @Override
  public void resetFixture() {
    database.jdbc().sql("delete from %s where browser_session_id = :id".formatted(
            database.schemas().qualifiedTable("identity", "browser_session")))
        .param("id", SESSION_ID)
        .update();
    database.jdbc().sql("delete from %s where account_id = :id".formatted(
            database.schemas().qualifiedTable("identity", "account")))
        .param("id", ACCOUNT_ID).update();
  }
}
