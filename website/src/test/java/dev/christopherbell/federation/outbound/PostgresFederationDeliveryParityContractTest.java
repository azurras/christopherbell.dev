package dev.christopherbell.federation.outbound;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresFederationDeliveryParityContractTest implements FederationDeliveryParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static FederationDeliveryStore deliveries;
  private static Post post;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    var now = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MILLIS);
    var accountId = "federation-parity-owner-" + RUN;
    new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions()).save(Account.builder().id(accountId)
        .createdOn(now).email(accountId + "@example.test").passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(accountId).build());
    post = Post.builder().id("federation-parity-post-" + RUN).accountId(accountId)
        .text("federation").rootId("federation-parity-post-" + RUN).level(0).createdOn(now)
        .expiresOn(now.plus(Duration.ofDays(1))).federationOutboundEligible(true)
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
    new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions()).save(post);
    deliveries = new PostgresFederationDeliveryJobRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public FederationDeliveryStore deliveries() { return deliveries; }
  @Override public Post deliveryPost() { return post; }
}
