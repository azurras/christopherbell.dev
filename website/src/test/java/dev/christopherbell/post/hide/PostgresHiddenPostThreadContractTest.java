package dev.christopherbell.post.hide;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.model.Post;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresHiddenPostThreadContractTest implements HiddenPostThreadParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static PostgresPostRepository posts;
  private static HiddenPostThreadRepository hiddenThreads;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    hiddenThreads = new PostgresHiddenPostThreadRepository(database.jdbc(), database.schemas());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public HiddenPostThreadRepository hiddenThreads() { return hiddenThreads; }
  @Override public void ensureAccountAndPost(Account account, Post post) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
    posts.save(post);
  }
}
