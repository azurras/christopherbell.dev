package dev.christopherbell.federation.discovery;

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
class PostgresFederationOutboxContractTest implements FederationOutboxParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static PostRepository posts;
  private static FederationOutboxQueryPort outbox;
  private static StableCursorCodec cursors;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(database.dsl());
    posts = new PostgresPostRepository(database.dsl());
    cursors = new StableCursorCodec();
    outbox = new PostgresFederationOutboxQueryRepository(database.dsl(), cursors, posts);
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

  @Override public PostRepository posts() { return posts; }
  @Override public FederationOutboxQueryPort outbox() { return outbox; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) { accounts.save(account); }
}
