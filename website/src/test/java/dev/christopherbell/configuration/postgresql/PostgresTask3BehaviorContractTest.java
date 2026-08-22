package dev.christopherbell.configuration.postgresql;

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
import dev.christopherbell.message.PostgresMessageRepository;
import dev.christopherbell.message.conversation.PostgresConversationQueryRepository;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.notification.PostgresNotificationRepository;
import dev.christopherbell.notification.inbox.PostgresNotificationQueryRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.delivery.NotificationDeliveryProperties;
import dev.christopherbell.notification.delivery.NotificationEventIdentity;
import dev.christopherbell.notification.delivery.PostgresNotificationFanoutGuard;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.discovery.PostgresVoidDiscoveryQueryRepository;
import dev.christopherbell.post.expiration.PostgresPostExpirationStore;
import dev.christopherbell.post.feed.PostgresPostFeedQueryRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.preview.PostLinkPreviewCacheEntry;
import dev.christopherbell.post.preview.PostgresPostLinkPreviewCacheRepository;
import dev.christopherbell.report.PostgresReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import dev.christopherbell.report.query.PostgresReportQueryService;
import dev.christopherbell.report.query.ReportQuery;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresTask3BehaviorContractTest {
  private static final Instant NOW = Instant.parse("2026-08-13T15:00:00Z");
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
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
    var posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    posts.save(post("behavior-post-a", "behavior-a", NOW));
    posts.save(post("behavior-post-b", "behavior-b", NOW.plusSeconds(1)));

    var discovery = new PostgresVoidDiscoveryQueryRepository(
        database.jdbc(), database.schemas(), new StableCursorCodec());
    assertThat(discovery.newArrivals(Optional.empty(), 1, NOW.minusSeconds(1)).items())
        .extracting(Post::getId).containsExactly("behavior-post-b");

    var admin = new PostgresAdminAccountQueryService(accounts, new AccountMapperImpl());
    var accountPage = admin.getAccounts(new AdminAccountQuery(
        0, 10, "username", Sort.Direction.ASC, AccountStatus.ACTIVE, Role.USER, "behavior"));
    assertThat(accountPage.items()).extracting("id")
        .containsExactly("behavior-a", "behavior-b");

    var reports = new PostgresReportRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    reports.save(report("behavior-report", "behavior-post-a"));
    var reportPage = new PostgresReportQueryService(
        database.jdbc(), database.schemas(), reports).query(
        new ReportQuery(ReportStatus.OPEN, ReportType.SPAM, ReportTargetType.POST,
            "BEHAVIORB", NOW.minusSeconds(1), NOW.plusSeconds(10), 0, 10));
    assertThat(reportPage.items()).extracting(PostReport::getId)
        .containsExactly("behavior-report");

    var expiration = new PostgresPostExpirationStore(database.jdbc(), database.schemas());
    var updated = expiration.incrementCounter(
        "behavior-post-a", "likesCount", 1, NOW.plusSeconds(2), true).orElseThrow();
    assertThat(updated.getLikesCount()).isOne();
    assertThat(updated.getLastExtendedOn()).isEqualTo(NOW.plusSeconds(2));

    database.jdbc().sql("set enable_seqscan = off").update();
    var plan = explain("select * from %s where email = 'behavior-a@example.test'"
        .formatted(table("identity", "account")));
    assertThat(plan).contains("account__email_asc");
    database.jdbc().sql("reset enable_seqscan").update();
  }

  @Test
  void productionShapedPagesUseConstantQueryCountsAndCursorIndexes() throws Exception {
    seedProductionShapedPages(30);
    database.jdbc().sql("analyze " + table("social", "post")).update();
    database.jdbc().sql("analyze " + table("communication", "message")).update();
    database.jdbc().sql("analyze " + table("communication", "notification")).update();
    database.jdbc().sql("analyze " + table("social", "post_report")).update();
    var executions = new AtomicInteger();
    var cursors = new StableCursorCodec();

    var feed = new PostgresPostFeedQueryRepository(
        countedJdbc(executions), database.schemas(), cursors);
    assertConstantQueries(executions, 4,
        () -> feed.global(Optional.empty(), 5),
        () -> feed.global(Optional.empty(), 24));
    var conversation = new PostgresConversationQueryRepository(
        countedJdbc(executions), database.schemas(), database.transactions(), cursors);
    assertConstantQueries(executions, 2,
        () -> conversation.page("behavior-a:behavior-b", Optional.empty(), 5),
        () -> conversation.page("behavior-a:behavior-b", Optional.empty(), 24));
    var discovery = new PostgresVoidDiscoveryQueryRepository(
        countedJdbc(executions), database.schemas(), cursors);
    assertConstantQueries(executions, 4,
        () -> discovery.newArrivals(Optional.empty(), 5, NOW.minusSeconds(1)),
        () -> discovery.newArrivals(Optional.empty(), 24, NOW.minusSeconds(1)));
    var notifications = new PostgresNotificationQueryRepository(
        countedJdbc(executions), database.schemas(), cursors);
    assertConstantQueries(executions, 1,
        () -> notifications.page("behavior-b", Optional.empty(), 5),
        () -> notifications.page("behavior-b", Optional.empty(), 24));
    var reportRepository = new PostgresReportRepository(
        countedJdbc(executions), database.schemas(), database.transactions());
    var reports = new PostgresReportQueryService(
        countedJdbc(executions), database.schemas(), reportRepository);
    assertConstantQueries(executions, 5,
        () -> reports.query(reportQuery(5)),
        () -> reports.query(reportQuery(24)));

    database.jdbc().sql("set enable_seqscan = off").update();
    try {
      assertThat(explain("select * from %s order by created_on desc, post_id desc limit 24"
          .formatted(table("social", "post"))))
          .contains("post__post_created_id_desc");
      assertThat(explain(("select * from %s where parent_post_id is null "
          + "and expires_on > timestamptz '2026-08-13T15:00:00Z' "
          + "order by created_on desc, post_id desc limit 24")
          .formatted(table("social", "post"))))
          .contains("Index Scan using")
          .containsAnyOf("post__void_discovery_new", "post__post_created_id_desc");
      assertThat(explain(("select * from %s where conversation_key = 'behavior-a:behavior-b' "
          + "order by created_on desc, message_id desc limit 24")
          .formatted(table("communication", "message"))))
          .contains("Index Scan using")
          .containsAnyOf("message__message_conversation_created_id_desc",
              "message__participant_created_parent");
      assertThat(explain(("select * from %s where account_id = 'behavior-b' "
          + "order by created_on desc, notification_id desc limit 24")
          .formatted(table("communication", "notification"))))
          .contains("notification__notification_account_created_id_desc");
      assertThat(explain(("select * from %s where status = 'OPEN' "
          + "order by created_on desc, post_report_id desc limit 24")
          .formatted(table("social", "post_report"))))
          .contains("Index Scan using")
          .containsAnyOf("post_report__report_status_created_id_desc",
              "post_report__report_created_id_desc");
    } finally {
      database.jdbc().sql("reset enable_seqscan").update();
    }
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
        return new PostgresNotificationFanoutGuard(
            firstDatabase.managedJdbc(), firstDatabase.schemas(), firstDatabase.transactions(), properties)
            .tryAcquire(identity, NOW);
      });
      var second = executor.submit(() -> {
        start.await();
        return new PostgresNotificationFanoutGuard(
            secondDatabase.managedJdbc(), secondDatabase.schemas(), secondDatabase.transactions(), properties)
            .tryAcquire(identity, NOW);
      });
      start.countDown();
      assertThat(java.util.List.of(first.get().isPresent(), second.get().isPresent()))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void expirationCleanupIsBatchLimitedObservableAndIdempotent() {
    var previews = new PostgresPostLinkPreviewCacheRepository(database.jdbc(), database.schemas());
    previews.save(PostLinkPreviewCacheEntry.failure(
        "https://expired-a.example", "FETCH_FAILED", NOW.minusSeconds(10), NOW.minusSeconds(2)));
    previews.save(PostLinkPreviewCacheEntry.failure(
        "https://expired-b.example", "FETCH_FAILED", NOW.minusSeconds(10), NOW.minusSeconds(1)));
    previews.save(PostLinkPreviewCacheEntry.failure(
        "https://fresh.example", "FETCH_FAILED", NOW, NOW.plusSeconds(60)));

    assertThat(previews.deleteExpired(NOW, 1)).isOne();
    assertThat(previews.deleteExpired(NOW, 1)).isOne();
    assertThat(previews.deleteExpired(NOW, 1)).isZero();
    assertThat(previews.findById("https://fresh.example")).isPresent();

    var properties = new NotificationDeliveryProperties(
        Duration.ofMinutes(5), Duration.ofMinutes(1), 10);
    var fanout = new PostgresNotificationFanoutGuard(
        database.managedJdbc(), database.schemas(), database.transactions(), properties);
    var expired = NOW.minus(Duration.ofDays(1));
    assertThat(fanout.tryAcquire(new NotificationEventIdentity(
        "behavior-a", "behavior-b", NotificationType.LIKE, "cleanup-a"), expired)).isPresent();
    assertThat(fanout.tryAcquire(new NotificationEventIdentity(
        "behavior-b", "behavior-a", NotificationType.COMMENT, "cleanup-b"), expired)).isPresent();

    assertThat(fanout.deleteExpired(NOW, 1))
        .extracting("guardsDeleted", "ratesDeleted").containsExactly(1, 1);
    assertThat(fanout.deleteExpired(NOW, 1))
        .extracting("guardsDeleted", "ratesDeleted").containsExactly(1, 1);
    assertThat(fanout.deleteExpired(NOW, 1).totalDeleted()).isZero();
  }

  @Test
  void accountDeletionPseudonymizesRetainedRowsBeforeDeletingAccount() {
    var accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    accounts.save(account("delete-me", "delete-me@example.test", "deleteme"));
    var posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    posts.save(post("delete-post", "delete-me", NOW.plusSeconds(20)));
    var reports = new PostgresReportRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    reports.save(PostReport.builder()
        .id("delete-report").postId("delete-post").postText("retained")
        .reportedAccountId("delete-me").reportedUsername("deleteme")
        .reporterAccountId("behavior-b").reporterUsername("behaviorb")
        .reportType(ReportType.SPAM).targetType(ReportTargetType.POST)
        .reason("retained").status(ReportStatus.OPEN).createdOn(NOW.plusSeconds(21)).build());
    database.jdbc().sql("""
            insert into %s (track_id, relative_path, title, index_status)
            values ('delete-track', 'delete-track.mp3', 'Delete Track', 'READY')
            """.formatted(table("music", "track"))).update();
    database.jdbc().sql("""
            insert into %s
              (playlist_id, normalized_name, name, updated_by_account_id, updated_at)
            values ('delete-playlist', 'delete-playlist', 'Delete Playlist',
              'delete-me', :updatedAt)
            """.formatted(table("music", "playlist")))
        .param("updatedAt", NOW.atOffset(java.time.ZoneOffset.UTC)).update();
    database.jdbc().sql("""
            insert into %s
              (metadata_edit_id, track_id, source_path, backup_file_name,
               backup_sha256, original_observed_token, edited_by_account_id,
               created_at, expires_at, status)
            values ('delete-metadata-edit', 'delete-track', 'delete-track.mp3',
              'delete-track.backup', :sha, 'before', 'delete-me', :createdAt,
              :expiresAt, 'READY')
            """.formatted(table("music", "metadata_edit")))
        .param("sha", "a".repeat(64))
        .param("createdAt", NOW.atOffset(java.time.ZoneOffset.UTC))
        .param("expiresAt", NOW.plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)).update();
    database.jdbc().sql("""
            insert into %s
              (import_preview_id, actor_account_id, checksum, created_count, created_on,
               deleted_count, expires_on, fetched_count, invalid_count, unchanged_count,
               updated_count)
            values ('delete-import-preview', 'delete-me', 'checksum', 0, :createdOn,
              0, :expiresOn, 0, 0, 0, 0)
            """.formatted(table("lunch", "restaurant_import_preview")))
        .param("createdOn", NOW.atOffset(java.time.ZoneOffset.UTC))
        .param("expiresOn", NOW.plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)).update();

    var operations = new PostgresAccountDeletionOperations(
        database.managedJdbc(), database.schemas(), database.transactions(), ignored -> {});
    operations.ensureTombstone();
    operations.anonymizePublicPosts("delete-me", "deleted:abcdef012345");
    operations.removePrivateData("delete-me");
    operations.pseudonymizeRetainedRecords("delete-me", "deleted:abcdef012345");
    operations.removeReferencesAndAccount("delete-me");

    assertThat(accounts.findById("delete-me")).isEmpty();
    assertThat(posts.findById("delete-post").orElseThrow().getAccountId())
        .isEqualTo("deleted-user");
    assertThat(database.jdbc().sql("""
            select reported_account_id from %s where post_report_id = 'delete-report'
            """.formatted(table("social", "post_report"))).query(String.class).single())
        .isEqualTo("deleted:abcdef012345");
    assertThat(database.jdbc().sql("""
            select updated_by_account_id from %s where playlist_id = 'delete-playlist'
            """.formatted(table("music", "playlist"))).query(String.class).single())
        .isEqualTo("deleted-user");
    assertThat(database.jdbc().sql("""
            select edited_by_account_id from %s where metadata_edit_id = 'delete-metadata-edit'
            """.formatted(table("music", "metadata_edit"))).query(String.class).single())
        .isEqualTo("deleted-user");
    assertThat(database.jdbc().sql("""
            select count(*) from %s where import_preview_id = 'delete-import-preview'
            """.formatted(table("lunch", "restaurant_import_preview")))
        .query(Long.class).single()).isZero();
  }

  private static Account account(String id, String email, String username) {
    return Account.builder().id(id).createdOn(NOW).email(email).passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(username).build();
  }

  private static JdbcClient countedJdbc(AtomicInteger executions) {
    return JdbcClient.create(new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return countingConnection(database.dataSource().getConnection(), executions);
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return countingConnection(
            database.dataSource().getConnection(username, password), executions);
      }
    });
  }

  private static String table(String schema, String name) {
    return database.schemas().qualifiedTable(schema, name);
  }

  private static String explain(String statement) {
    return String.join("\n", database.jdbc().sql("explain " + statement)
        .query(String.class).list());
  }

  private static Connection countingConnection(Connection delegate, AtomicInteger executions) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> {
          if (method.getName().startsWith("prepareStatement")) executions.incrementAndGet();
          try {
            return method.invoke(delegate, arguments);
          } catch (InvocationTargetException failure) {
            throw failure.getCause();
          }
        });
  }

  private static void assertConstantQueries(
      AtomicInteger executions, int expected, ThrowingQuery small, ThrowingQuery large)
      throws Exception {
    executions.set(0);
    small.run();
    assertThat(executions).hasValue(expected);
    executions.set(0);
    large.run();
    assertThat(executions).hasValue(expected);
  }

  private static void seedProductionShapedPages(int count) {
    var posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var messages = new PostgresMessageRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var notifications = new PostgresNotificationRepository(database.jdbc(), database.schemas());
    var reports = new PostgresReportRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    for (int index = 0; index < count; index++) {
      var suffix = "%02d".formatted(index);
      var createdOn = NOW.minusSeconds(100 + index);
      posts.save(post("shape-post-" + suffix, "behavior-a", createdOn));
      messages.save(Message.builder().id("shape-message-" + suffix)
          .conversationKey("behavior-a:behavior-b")
          .participantIds(Set.of("behavior-a", "behavior-b"))
          .senderAccountId("behavior-a").recipientAccountId("behavior-b")
          .text("message " + suffix).read(false).createdOn(createdOn).build());
      notifications.save(Notification.builder().id("shape-notification-" + suffix)
          .accountId("behavior-b").actorAccountId("behavior-a").actorUsername("behaviora")
          .notificationType(NotificationType.MENTION).read(false).createdOn(createdOn).build());
      var shapedReport = report("shape-report-" + suffix, "shape-post-" + suffix);
      shapedReport.setReporterUsername("shape");
      reports.save(shapedReport);
    }
  }

  private static ReportQuery reportQuery(int size) {
    return new ReportQuery(ReportStatus.OPEN, ReportType.SPAM, ReportTargetType.POST,
        null, null, null, 0, size);
  }

  @FunctionalInterface
  private interface ThrowingQuery {
    void run() throws Exception;
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
