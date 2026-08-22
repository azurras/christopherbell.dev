package dev.christopherbell.account.deletion;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresAccountDeletionContractTest implements AccountDeletionParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static AccountDeletionJobRepository jobs;
  private static AccountDeletionOperations operations;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    jobs = new PostgresAccountDeletionJobRepository(database.jdbc(), database.schemas());
    operations = new PostgresAccountDeletionOperations(
        database.managedJdbc(), database.schemas(), database.transactions(), accountId -> {});
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

  @Override public AccountDeletionJobRepository jobs() { return jobs; }
  @Override public AccountDeletionOperations operations() { return operations; }
  @Override public void createAccount(Account account) { accounts.save(account); }
}
