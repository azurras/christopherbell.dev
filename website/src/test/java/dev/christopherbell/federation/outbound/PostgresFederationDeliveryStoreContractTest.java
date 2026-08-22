package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.model.Post;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresFederationDeliveryStoreContractTest {
  private static final Instant NOW = Instant.parse("2026-08-13T16:00:00Z");
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresFederationDeliveryJobRepository deliveries;
  private static Post post;
  private static PostgresPostRepository posts;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions()).save(Account.builder()
        .id("federation-owner").createdOn(NOW).email("federation-owner@example.test")
        .passwordHash("hash").role(Role.USER).status(AccountStatus.ACTIVE)
        .username("federationowner").build());
    post = Post.builder().id("federation-post").accountId("federation-owner")
        .text("hello federation").rootId("federation-post").level(0).createdOn(NOW)
        .expiresOn(NOW.plusSeconds(3600)).federationOutboundEligible(true)
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
    posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    posts.save(post);
    deliveries = new PostgresFederationDeliveryJobRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Test
  void scanCursorEnqueueClaimAndExactOwnerTransitionsAreStable() {
    assertThat(posts.findFederationEligibleAfter(null, null, 10)).extracting(Post::getId)
        .containsExactly("federation-post");
    var cursor = new FederationScanCursor(NOW, "federation-post");
    deliveries.saveCursor(cursor, NOW);
    assertThat(deliveries.loadCursor()).isEqualTo(cursor);

    var peer = new ControlledPeer("peer-a", URI.create("https://peer.example/inbox"));
    deliveries.enqueueIfAbsent(post.getId(), post.getAccountId(), peer, NOW);
    deliveries.enqueueIfAbsent(post.getId(), post.getAccountId(), peer, NOW);
    var claimed = deliveries.claimDue("worker-a", NOW, NOW.plusSeconds(30)).orElseThrow();
    assertThat(claimed.attempts()).isOne();
    assertThat(deliveries.succeed(claimed.id(), "worker-b", 202, NOW.plusSeconds(1)))
        .isFalse();
    assertThat(deliveries.retry(
        claimed.id(), "worker-a", 503, Instant.now().plusSeconds(60), NOW.plusSeconds(1))).isTrue();
    assertThat(deliveries.claimDue("worker-b", Instant.EPOCH, Instant.EPOCH.plusSeconds(30)))
        .isEmpty();
    database.jdbc().sql("""
            update %s set next_attempt_on = current_timestamp - interval '1 second'
            where peer_name = 'peer-a'
            """.formatted(database.schemas().qualifiedTable(
                "federation", "federation_delivery_job"))).update();
    var retried = deliveries.claimDue(
        "worker-b", Instant.EPOCH, Instant.EPOCH.plusSeconds(30)).orElseThrow();
    assertThat(deliveries.succeed(retried.id(), "worker-b", 202, NOW.plusSeconds(61)))
        .isTrue();
  }

  @Test
  void claimEligibilityAndLeaseCompletionUseDatabaseTime() {
    var peer = new ControlledPeer("peer-db-clock", URI.create("https://clock.example/inbox"));
    deliveries.enqueueIfAbsent(post.getId(), post.getAccountId(), peer, NOW);
    database.jdbc().sql("""
            update %s set next_attempt_on = current_timestamp - interval '1 second'
            where peer_name = 'peer-db-clock'
            """.formatted(database.schemas().qualifiedTable(
                "federation", "federation_delivery_job"))).update();

    var claimed = deliveries.claimDue(
        "clock-worker", Instant.EPOCH, Instant.EPOCH.plusSeconds(30)).orElseThrow();
    assertThat(claimed.claimUntil()).isAfter(Instant.now().minusSeconds(1));

    database.jdbc().sql("""
            update %s set claim_until = current_timestamp - interval '1 second'
            where delivery_job_id = :id
            """.formatted(database.schemas().qualifiedTable(
                "federation", "federation_delivery_job"))).param("id", claimed.id()).update();

    assertThat(deliveries.succeed(claimed.id(), "clock-worker", 202, Instant.MAX))
        .isFalse();
  }
}
