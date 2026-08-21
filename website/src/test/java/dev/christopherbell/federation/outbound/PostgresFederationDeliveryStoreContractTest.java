package dev.christopherbell.federation.outbound;

import static dev.christopherbell.persistence.jooq.federation.Tables.FEDERATION_DELIVERY_JOB;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    new PostgresAccountRepository(database.dsl()).save(Account.builder()
        .id("federation-owner").createdOn(NOW).email("federation-owner@example.test")
        .passwordHash("hash").role(Role.USER).status(AccountStatus.ACTIVE)
        .username("federationowner").build());
    post = Post.builder().id("federation-post").accountId("federation-owner")
        .text("hello federation").rootId("federation-post").level(0).createdOn(NOW)
        .expiresOn(NOW.plusSeconds(3600)).federationOutboundEligible(true)
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
    posts = new PostgresPostRepository(database.dsl());
    posts.save(post);
    deliveries = new PostgresFederationDeliveryJobRepository(database.dsl());
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
    database.dsl().update(FEDERATION_DELIVERY_JOB)
        .set(FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON,
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
        .where(FEDERATION_DELIVERY_JOB.PEER_NAME.eq("peer-a"))
        .execute();
    var retried = deliveries.claimDue(
        "worker-b", Instant.EPOCH, Instant.EPOCH.plusSeconds(30)).orElseThrow();
    assertThat(deliveries.succeed(retried.id(), "worker-b", 202, NOW.plusSeconds(61)))
        .isTrue();
  }

  @Test
  void claimEligibilityAndLeaseCompletionUseDatabaseTime() {
    var peer = new ControlledPeer("peer-db-clock", URI.create("https://clock.example/inbox"));
    deliveries.enqueueIfAbsent(post.getId(), post.getAccountId(), peer, NOW);
    database.dsl().update(FEDERATION_DELIVERY_JOB)
        .set(FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON,
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
        .where(FEDERATION_DELIVERY_JOB.PEER_NAME.eq("peer-db-clock"))
        .execute();

    var claimed = deliveries.claimDue(
        "clock-worker", Instant.EPOCH, Instant.EPOCH.plusSeconds(30)).orElseThrow();
    assertThat(claimed.claimUntil()).isAfter(Instant.now().minusSeconds(1));

    database.dsl().update(FEDERATION_DELIVERY_JOB)
        .set(FEDERATION_DELIVERY_JOB.CLAIM_UNTIL,
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
        .where(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID.eq(claimed.id()))
        .execute();

    assertThat(deliveries.succeed(claimed.id(), "clock-worker", 202, Instant.MAX))
        .isFalse();
  }
}
