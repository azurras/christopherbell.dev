package dev.christopherbell.post;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresPostRepositoryContractTest implements PostRepositoryParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostRepository posts;

  @BeforeAll
  static void migrate() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions()).save(Account.builder()
        .id(OWNER).createdOn(CREATED.minusSeconds(1))
        .email(OWNER + "@example.test").passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(OWNER).build());
    posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
  }

  @AfterAll static void cleanup() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }
  @Override public PostRepository parityPosts() { return posts; }
}
