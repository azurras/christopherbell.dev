package dev.christopherbell.configuration.postgresql;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.AccountMapperImpl;
import dev.christopherbell.account.AdminAccountQuery;
import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.PostgresAdminAccountQueryService;
import dev.christopherbell.account.deletion.PostgresAccountDeletionOperations;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.delivery.NotificationDeliveryProperties;
import dev.christopherbell.notification.delivery.NotificationEventIdentity;
import dev.christopherbell.notification.delivery.PostgresNotificationFanoutGuard;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.discovery.PostgresVoidDiscoveryQueryRepository;
import dev.christopherbell.post.expiration.PostgresPostExpirationStore;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.PostgresReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import dev.christopherbell.report.query.PostgresReportQueryService;
import dev.christopherbell.report.query.ReportQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.Sort;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresTask3BehaviorContractTest {
  private static final Instant NOW = Instant.parse("2026-08-13T15:00:00Z");
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    var accounts = new PostgresAccountRepository(database.dsl());
    accounts.save(account("behavior-a", "behavior-a@example.test", "behaviora"));
    accounts.save(account("behavior-b", "behavior-b@example.test", "behaviorb"));
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Test
  void discoveryAdminReportAndExpirationQueriesPreserveOrderingAndCounters() throws Exception {
    var posts = new PostgresPostRepository(database.dsl());
    posts.save(post("behavior-post-a", "behavior-a", NOW));
    posts.save(post("behavior-post-b", "behavior-b", NOW.plusSeconds(1)));

    var discovery = new PostgresVoidDiscoveryQueryRepository(
        database.dsl(), new StableCursorCodec());
    assertThat(discovery.newArrivals(Optional.empty(), 1, NOW.minusSeconds(1)).items())
        .extracting(Post::getId).containsExactly("behavior-post-b");

    var admin = new PostgresAdminAccountQueryService(database.dsl(), new AccountMapperImpl());
    var accountPage = admin.getAccounts(new AdminAccountQuery(
        0, 10, "username", Sort.Direction.ASC, AccountStatus.ACTIVE, Role.USER, "behavior"));
    assertThat(accountPage.items()).extracting("id")
        .containsExactly("behavior-a", "behavior-b");

    var reports = new PostgresReportRepository(database.dsl());
    reports.save(report("behavior-report", "behavior-post-a"));
    var reportPage = new PostgresReportQueryService(database.dsl(), reports).query(
        new ReportQuery(ReportStatus.OPEN, ReportType.SPAM, ReportTargetType.POST,
            "BEHAVIORB", NOW.minusSeconds(1), NOW.plusSeconds(10), 0, 10));
    assertThat(reportPage.items()).extracting(PostReport::getId)
        .containsExactly("behavior-report");

    var expiration = new PostgresPostExpirationStore(database.dsl());
    var updated = expiration.incrementCounter(
        "behavior-post-a", "likesCount", 1, NOW.plusSeconds(2), true).orElseThrow();
    assertThat(updated.getLikesCount()).isOne();
    assertThat(updated.getLastExtendedOn()).isEqualTo(NOW.plusSeconds(2));

    database.dsl().execute("set enable_seqscan = off");
    var plan = database.dsl().explain(database.dsl().selectFrom(ACCOUNT)
        .where(ACCOUNT.EMAIL.eq("behavior-a@example.test"))).plan();
    assertThat(plan).contains("account__email_asc");
    database.dsl().execute("reset enable_seqscan");
  }

  @Test
  void fanoutClaimIsAtomicAcrossIndependentConnections() throws Exception {
    var properties = new NotificationDeliveryProperties(
        Duration.ofMinutes(5), Duration.ofMinutes(1), 10);
    var identity = new NotificationEventIdentity(
        "behavior-b", "behavior-a", NotificationType.LIKE, "behavior-post-a");
    try (var firstDatabase = schemas.openDatabase();
         var secondDatabase = schemas.openDatabase();
         var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = executor.submit(() -> {
        start.await();
        return new PostgresNotificationFanoutGuard(firstDatabase.dsl(), properties)
            .tryAcquire(identity, NOW);
      });
      var second = executor.submit(() -> {
        start.await();
        return new PostgresNotificationFanoutGuard(secondDatabase.dsl(), properties)
            .tryAcquire(identity, NOW);
      });
      start.countDown();
      assertThat(java.util.List.of(first.get().isPresent(), second.get().isPresent()))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void accountDeletionPseudonymizesRetainedRowsBeforeDeletingAccount() {
    var accounts = new PostgresAccountRepository(database.dsl());
    accounts.save(account("delete-me", "delete-me@example.test", "deleteme"));
    var posts = new PostgresPostRepository(database.dsl());
    posts.save(post("delete-post", "delete-me", NOW.plusSeconds(20)));
    var reports = new PostgresReportRepository(database.dsl());
    reports.save(PostReport.builder()
        .id("delete-report").postId("delete-post").postText("retained")
        .reportedAccountId("delete-me").reportedUsername("deleteme")
        .reporterAccountId("behavior-b").reporterUsername("behaviorb")
        .reportType(ReportType.SPAM).targetType(ReportTargetType.POST)
        .reason("retained").status(ReportStatus.OPEN).createdOn(NOW.plusSeconds(21)).build());

    var operations = new PostgresAccountDeletionOperations(database.dsl(), ignored -> {});
    operations.ensureTombstone();
    operations.anonymizePublicPosts("delete-me", "deleted-abc123");
    operations.removePrivateData("delete-me");
    operations.pseudonymizeRetainedRecords("delete-me", "deleted-abc123");
    operations.removeReferencesAndAccount("delete-me");

    assertThat(accounts.findById("delete-me")).isEmpty();
    assertThat(posts.findById("delete-post").orElseThrow().getAccountId())
        .isEqualTo("deleted-user");
    assertThat(database.dsl().select(POST_REPORT.REPORTED_ACCOUNT_ID)
        .from(POST_REPORT).where(POST_REPORT.POST_REPORT_ID.eq("delete-report"))
        .fetchOne(POST_REPORT.REPORTED_ACCOUNT_ID)).isEqualTo("deleted-abc123");
  }

  private static Account account(String id, String email, String username) {
    return Account.builder().id(id).createdOn(NOW).email(email).passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(username).build();
  }

  private static Post post(String id, String accountId, Instant createdOn) {
    return Post.builder().id(id).accountId(accountId).text(id).rootId(id).level(0)
        .createdOn(createdOn).expiresOn(createdOn.plus(Duration.ofDays(2)))
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
  }

  private static PostReport report(String id, String postId) {
    return PostReport.builder().id(id).postId(postId).postText("text")
        .reportedAccountId("behavior-a").reportedUsername("behaviora")
        .reporterAccountId("behavior-b").reporterUsername("behaviorb")
        .reportType(ReportType.SPAM).targetType(ReportTargetType.POST)
        .reason("spam").status(ReportStatus.OPEN).createdOn(NOW.plusSeconds(3)).build();
  }
}
