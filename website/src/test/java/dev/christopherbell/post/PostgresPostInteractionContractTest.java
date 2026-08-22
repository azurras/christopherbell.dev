package dev.christopherbell.post;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.post.expiration.PostExpirationStore;
import dev.christopherbell.post.expiration.PostgresPostExpirationStore;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.like.PostgresPostLikeStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresPostInteractionContractTest implements PostInteractionParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static AccountRepository accounts;
  private static PostRepository posts;
  private static PostLikeStore likes;
  private static PostExpirationStore expiration;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    likes = new PostgresPostLikeStore(database.jdbc(), database.schemas());
    expiration = new PostgresPostExpirationStore(database.jdbc(), database.schemas());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public AccountRepository accounts() { return accounts; }
  @Override public PostRepository posts() { return posts; }
  @Override public PostLikeStore likes() { return likes; }
  @Override public PostExpirationStore expiration() { return expiration; }
}
