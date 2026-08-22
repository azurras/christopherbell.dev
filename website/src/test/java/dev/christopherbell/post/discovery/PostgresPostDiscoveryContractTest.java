package dev.christopherbell.post.discovery;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.PostgresPostRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresPostDiscoveryContractTest implements PostDiscoveryParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static PostRepository posts;
  private static VoidDiscoveryQueryPort discovery;
  private static VoidPeopleDiscoveryQueryPort people;
  private static StableCursorCodec cursors;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    cursors = new StableCursorCodec();
    discovery = new PostgresVoidDiscoveryQueryRepository(
        database.jdbc(), database.schemas(), cursors);
    people = new PostgresVoidPeopleDiscoveryQueryRepository(database.jdbc(), database.schemas());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public PostRepository posts() { return posts; }
  @Override public VoidDiscoveryQueryPort discovery() { return discovery; }
  @Override public VoidPeopleDiscoveryQueryPort people() { return people; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}
