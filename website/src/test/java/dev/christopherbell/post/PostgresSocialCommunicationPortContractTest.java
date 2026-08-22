package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.message.PostgresMessageRepository;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.notification.PostgresNotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.notification.preference.NotificationPreference;
import dev.christopherbell.notification.preference.PostgresNotificationPreferenceRepository;
import dev.christopherbell.post.hide.HiddenPostThread;
import dev.christopherbell.post.hide.PostgresHiddenPostThreadRepository;
import dev.christopherbell.post.like.PostgresPostLikeStore;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.model.PostTopic;
import dev.christopherbell.post.preview.PostLinkPreviewCacheEntry;
import dev.christopherbell.post.preview.PostgresPostLinkPreviewCacheRepository;
import dev.christopherbell.report.PostgresReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.PageRequest;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresSocialCommunicationPortContractTest {
  private static final Instant NOW = Instant.parse("2026-08-13T14:00:00Z");
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    var accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    accounts.save(account("social-a", "social-a@example.test", "sociala"));
    accounts.save(account("social-b", "social-b@example.test", "socialb"));
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Test
  void postsLikesHidesPreviewsAndReportsRoundTrip() throws Exception {
    var posts = new PostgresPostRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var post = Post.builder()
        .id("post-root")
        .accountId("social-a")
        .text("hello #topic")
        .rootId("post-root")
        .level(0)
        .createdOn(NOW)
        .expiresOn(NOW.plusSeconds(3600))
        .federationOutboundEligible(true)
        .topics(List.of(new PostTopic("topic", "topic")))
        .linkPreviews(List.of(PostLinkPreview.builder()
            .url("https://example.test/page")
            .domain("example.test")
            .title("Example")
            .build()))
        .likesCount(0)
        .threadReplyLikesCount(0)
        .threadReplyCount(0)
        .build();
    assertThat(posts.save(post)).isEqualTo(post);
    assertThat(posts.findByRootIdOrderByCreatedOnAsc("post-root")).containsExactly(post);

    var likes = new PostgresPostLikeStore(database.jdbc(), database.schemas());
    assertThat(likes.like("post-root", "social-b", NOW.plusSeconds(1)).created()).isTrue();
    assertThat(likes.like("post-root", "social-b", NOW.plusSeconds(1)).created()).isFalse();
    assertThat(likes.counts(List.of("post-root"))).containsEntry("post-root", 1);
    assertThat(likes.likedPostIds("social-b", List.of("post-root")))
        .containsExactly("post-root");

    var hidden = new PostgresHiddenPostThreadRepository(database.jdbc(), database.schemas());
    var hiddenThread = HiddenPostThread.builder()
        .id("hidden-thread")
        .accountId("social-b")
        .rootPostId("post-root")
        .createdOn(NOW.plusSeconds(2))
        .build();
    assertThat(hidden.save(hiddenThread)).isEqualTo(hiddenThread);

    var cache = new PostgresPostLinkPreviewCacheRepository(database.jdbc(), database.schemas());
    var cacheEntry = PostLinkPreviewCacheEntry.success(
        "https://example.test/page",
        post.getLinkPreviews().getFirst(),
        NOW,
        NOW.plusSeconds(600));
    assertThat(cache.save(cacheEntry)).isEqualTo(cacheEntry);
    assertThat(cache.findById(cacheEntry.getUrl())).contains(cacheEntry);

    var reports = new PostgresReportRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var report = PostReport.builder()
        .id("report-root")
        .postId("post-root")
        .postText(post.getText())
        .reportedAccountId("social-a")
        .reportedUsername("sociala")
        .reporterAccountId("social-b")
        .reporterUsername("socialb")
        .openDedupeKey("social-b:post-root")
        .reportType(ReportType.SPAM)
        .targetType(ReportTargetType.POST)
        .reason("spam")
        .status(ReportStatus.OPEN)
        .createdOn(NOW.plusSeconds(3))
        .build();
    var pendingAudit = ModerationAuditCommand.create(
        "social-b", "socialb", "REPORT_RESOLVED", "REPORT", report.getId(),
        "Report " + report.getId(), "approved reason", "%s resolved report.",
        Map.of("status", "OPEN"), Map.of("status", "RESOLVED"),
        Map.of("source", "back-office", "reportId", report.getId()));
    report.setPendingModerationAudit(pendingAudit);
    assertThat(reports.save(report)).isEqualTo(report);
    assertThat(reports.findByStatusOrderByCreatedOnDesc(ReportStatus.OPEN))
        .containsExactly(report);
    report.setPendingModerationAudit(null);
    reports.save(report);
    assertThat(reports.findById(report.getId()).orElseThrow().getPendingModerationAudit()).isNull();
  }

  @Test
  void messagesNotificationsAndPreferencesRoundTripWithBoundedQueries() {
    var messages = new PostgresMessageRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var message = Message.builder()
        .id("message-a-b")
        .conversationKey("social-a:social-b")
        .participantIds(Set.of("social-a", "social-b"))
        .senderAccountId("social-a")
        .recipientAccountId("social-b")
        .text("hello")
        .read(false)
        .createdOn(NOW)
        .build();
    assertThat(messages.save(message)).isEqualTo(message);
    assertThat(messages.findByConversationKeyOrderByCreatedOnAsc(
        message.getConversationKey(), PageRequest.of(0, 10))).containsExactly(message);

    var notifications = new PostgresNotificationRepository(database.jdbc(), database.schemas());
    var notification = Notification.builder()
        .id("notification-message")
        .accountId("social-b")
        .actorAccountId("social-a")
        .actorUsername("sociala")
        .messageId(message.getId())
        .messageText(message.getText())
        .notificationType(NotificationType.MESSAGE)
        .read(false)
        .createdOn(NOW.plusSeconds(1))
        .build();
    assertThat(notifications.save(notification)).isEqualTo(notification);
    assertThat(notifications.countByAccountIdAndReadFalse("social-b")).isOne();

    var preferences = new PostgresNotificationPreferenceRepository(database.jdbc(), database.schemas());
    var preference = NotificationPreference.builder()
        .id("preference-b")
        .accountId("social-b")
        .mentions(true)
        .messages(true)
        .createdOn(NOW)
        .lastUpdatedOn(NOW)
        .build();
    assertThat(preferences.save(preference)).isEqualTo(preference);
    assertThat(preferences.findByAccountId("social-b")).contains(preference);
  }

  private static Account account(String id, String email, String username) {
    return Account.builder()
        .id(id)
        .createdOn(NOW)
        .email(email)
        .passwordHash("hash")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .username(username)
        .build();
  }
}
