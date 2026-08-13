package dev.christopherbell.account.trust;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.follow.PostgresAccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresIdentityRelationshipContractTest implements IdentityRelationshipParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static AccountFollowStore follows;
  private static AccountTrustRepository trust;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(database.dsl());
    follows = new PostgresAccountFollowStore(database.dsl());
    trust = new PostgresAccountTrustRepository(database.dsl());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public AccountFollowStore followStore() { return follows; }
  @Override public AccountTrustRepository trustRepository() { return trust; }
  @Override public void ensureAccount(Account account) {
    accounts.findById(account.getId()).orElseGet(() -> accounts.save(account));
  }
}
