package dev.christopherbell.configuration.postgresql;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_IMPORT_PREVIEW;
import static dev.christopherbell.persistence.jooq.music.Tables.METADATA_EDIT;
import static dev.christopherbell.persistence.jooq.music.Tables.PLAYLIST;
import static dev.christopherbell.persistence.jooq.music.Tables.TRACK;
import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE;
import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION;
import static dev.christopherbell.persistence.jooq.social.Tables.POST;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
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
  void productionShapedPagesUseConstantQueryCountsAndCursorIndexes() throws Exception {
    seedProductionShapedPages(30);
    database.dsl().execute("analyze " + database.dsl().render(POST));
    database.dsl().execute("analyze " + database.dsl().render(MESSAGE));
    database.dsl().execute("analyze " + database.dsl().render(NOTIFICATION));
    database.dsl().execute("analyze " + database.dsl().render(POST_REPORT));
    var executions = new AtomicInteger();
    var counted = countedDatabase(executions);
    var cursors = new StableCursorCodec();

    var feed = new PostgresPostFeedQueryRepository(counted, cursors);
    assertConstantQueries(executions, 4,
        () -> feed.global(Optional.empty(), 5),
        () -> feed.global(Optional.empty(), 24));
    var conversation = new PostgresConversationQueryRepository(counted, cursors);
    assertConstantQueries(executions, 2,
        () -> conversation.page("behavior-a:behavior-b", Optional.empty(), 5),
        () -> conversation.page("behavior-a:behavior-b", Optional.empty(), 24));
    var discovery = new PostgresVoidDiscoveryQueryRepository(counted, cursors);
    assertConstantQueries(executions, 4,
        () -> discovery.newArrivals(Optional.empty(), 5, NOW.minusSeconds(1)),
        () -> discovery.newArrivals(Optional.empty(), 24, NOW.minusSeconds(1)));
    var notifications = new PostgresNotificationQueryRepository(counted, cursors);
    assertConstantQueries(executions, 1,
        () -> notifications.page("behavior-b", Optional.empty(), 5),
        () -> notifications.page("behavior-b", Optional.empty(), 24));
    var reports = new PostgresReportQueryService(counted, new PostgresReportRepository(counted));
    assertConstantQueries(executions, 5,
        () -> reports.query(reportQuery(5)),
        () -> reports.query(reportQuery(24)));

    database.dsl().execute("set enable_seqscan = off");
    try {
      assertThat(database.dsl().explain(database.dsl().selectFrom(POST)
          .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc()).limit(24)).plan())
          .contains("post__post_created_id_desc");
      assertThat(database.dsl().explain(database.dsl().selectFrom(POST)
          .where(POST.PARENT_POST_ID.isNull().and(POST.EXPIRES_ON.gt(NOW.atOffset(java.time.ZoneOffset.UTC))))
          .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc()).limit(24)).plan())
          .contains("Index Scan using")
          .containsAnyOf("post__void_discovery_new", "post__post_created_id_desc");
      assertThat(database.dsl().explain(database.dsl().selectFrom(MESSAGE)
          .where(MESSAGE.CONVERSATION_KEY.eq("behavior-a:behavior-b"))
          .orderBy(MESSAGE.CREATED_ON.desc(), MESSAGE.MESSAGE_ID.desc()).limit(24)).plan())
          .contains("Index Scan using")
          .containsAnyOf("message__message_conversation_created_id_desc",
              "message__participant_created_parent");
      assertThat(database.dsl().explain(database.dsl().selectFrom(NOTIFICATION)
          .where(NOTIFICATION.ACCOUNT_ID.eq("behavior-b"))
          .orderBy(NOTIFICATION.CREATED_ON.desc(), NOTIFICATION.NOTIFICATION_ID.desc()).limit(24)).plan())
          .contains("notification__notification_account_created_id_desc");
      assertThat(database.dsl().explain(database.dsl().selectFrom(POST_REPORT)
          .where(POST_REPORT.STATUS.eq(ReportStatus.OPEN.name()))
          .orderBy(POST_REPORT.CREATED_ON.desc(), POST_REPORT.POST_REPORT_ID.desc()).limit(24)).plan())
          .contains("Index Scan using")
          .containsAnyOf("post_report__report_status_created_id_desc",
              "post_report__report_created_id_desc");
    } finally {
      database.dsl().execute("reset enable_seqscan");
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
  void expirationCleanupIsBatchLimitedObservableAndIdempotent() {
    var previews = new PostgresPostLinkPreviewCacheRepository(database.dsl());
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
    var fanout = new PostgresNotificationFanoutGuard(database.dsl(), properties);
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
    database.dsl().insertInto(TRACK)
        .set(TRACK.TRACK_ID, "delete-track")
        .set(TRACK.RELATIVE_PATH, "delete-track.mp3")
        .set(TRACK.TITLE, "Delete Track")
        .set(TRACK.INDEX_STATUS, "READY")
        .execute();
    database.dsl().insertInto(PLAYLIST)
        .set(PLAYLIST.PLAYLIST_ID, "delete-playlist")
        .set(PLAYLIST.NORMALIZED_NAME, "delete-playlist")
        .set(PLAYLIST.NAME, "Delete Playlist")
        .set(PLAYLIST.UPDATED_BY_ACCOUNT_ID, "delete-me")
        .set(PLAYLIST.UPDATED_AT, NOW.atOffset(java.time.ZoneOffset.UTC))
        .execute();
    database.dsl().insertInto(METADATA_EDIT)
        .set(METADATA_EDIT.METADATA_EDIT_ID, "delete-metadata-edit")
        .set(METADATA_EDIT.TRACK_ID, "delete-track")
        .set(METADATA_EDIT.SOURCE_PATH, "delete-track.mp3")
        .set(METADATA_EDIT.BACKUP_FILE_NAME, "delete-track.backup")
        .set(METADATA_EDIT.BACKUP_SHA256, "a".repeat(64))
        .set(METADATA_EDIT.ORIGINAL_OBSERVED_TOKEN, "before")
        .set(METADATA_EDIT.EDITED_BY_ACCOUNT_ID, "delete-me")
        .set(METADATA_EDIT.CREATED_AT, NOW.atOffset(java.time.ZoneOffset.UTC))
        .set(METADATA_EDIT.EXPIRES_AT, NOW.plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC))
        .set(METADATA_EDIT.STATUS, "READY")
        .execute();
    database.dsl().insertInto(RESTAURANT_IMPORT_PREVIEW)
        .set(RESTAURANT_IMPORT_PREVIEW.IMPORT_PREVIEW_ID, "delete-import-preview")
        .set(RESTAURANT_IMPORT_PREVIEW.ACTOR_ACCOUNT_ID, "delete-me")
        .set(RESTAURANT_IMPORT_PREVIEW.CHECKSUM, "checksum")
        .set(RESTAURANT_IMPORT_PREVIEW.CREATED_COUNT, 0)
        .set(RESTAURANT_IMPORT_PREVIEW.CREATED_ON, NOW.atOffset(java.time.ZoneOffset.UTC))
        .set(RESTAURANT_IMPORT_PREVIEW.DELETED_COUNT, 0)
        .set(RESTAURANT_IMPORT_PREVIEW.EXPIRES_ON,
            NOW.plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC))
        .set(RESTAURANT_IMPORT_PREVIEW.FETCHED_COUNT, 0)
        .set(RESTAURANT_IMPORT_PREVIEW.INVALID_COUNT, 0)
        .set(RESTAURANT_IMPORT_PREVIEW.UNCHANGED_COUNT, 0)
        .set(RESTAURANT_IMPORT_PREVIEW.UPDATED_COUNT, 0)
        .execute();

    var operations = new PostgresAccountDeletionOperations(database.dsl(), ignored -> {});
    operations.ensureTombstone();
    operations.anonymizePublicPosts("delete-me", "deleted:abcdef012345");
    operations.removePrivateData("delete-me");
    operations.pseudonymizeRetainedRecords("delete-me", "deleted:abcdef012345");
    operations.removeReferencesAndAccount("delete-me");

    assertThat(accounts.findById("delete-me")).isEmpty();
    assertThat(posts.findById("delete-post").orElseThrow().getAccountId())
        .isEqualTo("deleted-user");
    assertThat(database.dsl().select(POST_REPORT.REPORTED_ACCOUNT_ID)
        .from(POST_REPORT).where(POST_REPORT.POST_REPORT_ID.eq("delete-report"))
        .fetchOne(POST_REPORT.REPORTED_ACCOUNT_ID)).isEqualTo("deleted:abcdef012345");
    assertThat(database.dsl().select(PLAYLIST.UPDATED_BY_ACCOUNT_ID)
        .from(PLAYLIST).where(PLAYLIST.PLAYLIST_ID.eq("delete-playlist"))
        .fetchOne(PLAYLIST.UPDATED_BY_ACCOUNT_ID)).isEqualTo("deleted-user");
    assertThat(database.dsl().select(METADATA_EDIT.EDITED_BY_ACCOUNT_ID)
        .from(METADATA_EDIT).where(METADATA_EDIT.METADATA_EDIT_ID.eq("delete-metadata-edit"))
        .fetchOne(METADATA_EDIT.EDITED_BY_ACCOUNT_ID)).isEqualTo("deleted-user");
    assertThat(database.dsl().fetchCount(RESTAURANT_IMPORT_PREVIEW,
        RESTAURANT_IMPORT_PREVIEW.IMPORT_PREVIEW_ID.eq("delete-import-preview"))).isZero();
  }

  private static Account account(String id, String email, String username) {
    return Account.builder().id(id).createdOn(NOW).email(email).passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(username).build();
  }

  private static DSLContext countedDatabase(AtomicInteger executions) {
    return DSL.using(database.dsl().configuration().deriveAppending(new DefaultExecuteListener() {
      @Override
      public void executeStart(ExecuteContext context) {
        executions.incrementAndGet();
      }
    }));
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
    var posts = new PostgresPostRepository(database.dsl());
    var messages = new PostgresMessageRepository(database.dsl());
    var notifications = new PostgresNotificationRepository(database.dsl());
    var reports = new PostgresReportRepository(database.dsl());
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
