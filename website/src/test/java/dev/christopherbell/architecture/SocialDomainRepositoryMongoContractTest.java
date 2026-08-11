package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.deletion.AccountDeletionJob;
import dev.christopherbell.account.deletion.AccountDeletionJobRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustRelationship;
import dev.christopherbell.account.trust.AccountTrustRepository;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.admin.activity.AdminActivityRepository;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.security.browser.BrowserSession;
import dev.christopherbell.configuration.security.browser.BrowserSessionRepository;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.notification.preference.NotificationPreference;
import dev.christopherbell.notification.preference.NotificationPreferenceRepository;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.hide.HiddenPostThread;
import dev.christopherbell.post.hide.HiddenPostThreadRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.preview.PostLinkPreviewCacheEntry;
import dev.christopherbell.post.preview.PostLinkPreviewCacheRepository;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Behavioral contracts for every explicit Task 3 repository port against real MongoDB. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class SocialDomainRepositoryMongoContractTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static MongoClient client;

  private MongoTemplate mongo;
  private DomainMongoOperationsFactory factory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Repository contracts require one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Repository contracts require a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createDatabase() {
    String database = "social_repository_contract_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    mongo = new MongoTemplate(client, database);
    factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
  }

  @AfterEach
  void dropDatabase() {
    mongo.getDb().drop();
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void accountPortPreservesCrudQueriesOrderingPagingAndUniqueness() {
    var repository = new dev.christopherbell.account.MongoAccountRepository(factory);
    var alpha = account("account-a", "alpha@example.test", "Alpha", AccountStatus.ACTIVE, true);
    alpha.setPasswordResetTokenHash("reset-a");
    var beta = account("account-b", "beta@example.test", "beta", AccountStatus.ACTIVE, true);
    var gamma = account("account-c", "gamma@example.test", "gamma", AccountStatus.INACTIVE, false);

    assertThat(repository.save(alpha)).isEqualTo(alpha);
    repository.save(beta);
    repository.save(gamma);

    assertThat(repository.findById(alpha.getId())).contains(alpha);
    assertThat(repository.existsById(alpha.getId())).isTrue();
    assertThat(repository.findAll(PageRequest.of(0, 2, Sort.by("username"))))
        .extracting(Account::getUsername).containsExactly("Alpha", "beta");
    assertThat(repository.findAllById(List.of(beta.getId(), gamma.getId())))
        .extracting(Account::getId).containsExactlyInAnyOrder(beta.getId(), gamma.getId());
    assertThat(repository.findByEmail(alpha.getEmail())).contains(alpha);
    assertThat(repository.findByEmailIgnoreCase("ALPHA@EXAMPLE.TEST")).contains(alpha);
    assertThat(repository.findByPasswordResetTokenHash("reset-a")).contains(alpha);
    assertThat(repository.findByUsername("Alpha")).contains(alpha);
    assertThat(repository.findByUsernameAndStatus("Alpha", AccountStatus.ACTIVE)).contains(alpha);
    assertThat(repository.findByUsernameIgnoreCase("ALPHA")).contains(alpha);
    assertThat(repository.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
        "ALPHA", AccountStatus.ACTIVE)).contains(alpha);
    assertThat(repository.countByStatus(AccountStatus.ACTIVE)).isEqualTo(2);
    var activePage = repository.findByStatus(AccountStatus.ACTIVE, PageRequest.of(0, 1));
    assertThat(activePage).hasSize(1);
    assertThat(activePage.getTotalElements()).isEqualTo(2);
    assertThat(repository.findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
        "A", AccountStatus.ACTIVE, PageRequest.of(0, 10)))
        .extracting(Account::getUsername).containsExactly("Alpha");
    assertThat(repository.findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
        Set.of(alpha.getId(), beta.getId(), gamma.getId()),
        AccountStatus.ACTIVE,
        PageRequest.of(0, 10)))
        .extracting(Account::getUsername).containsExactly("Alpha", "beta");

    createUniqueIndex(Account.class, "email", "account_email_unique");
    assertThatThrownBy(() -> repository.save(account(
        "account-duplicate", alpha.getEmail(), "different", AccountStatus.ACTIVE, false)))
        .isInstanceOf(DuplicateKeyException.class);

    repository.deleteById(alpha.getId());
    assertThat(repository.existsById(alpha.getId())).isFalse();
  }

  @Test
  void accountDeletionJobPortSavesAndLoadsCheckpoints() {
    var repository = adapter(
        AccountDeletionJobRepository.class,
        "dev.christopherbell.account.deletion.MongoAccountDeletionJobRepository");
    var job = AccountDeletionJob.started("deletion-pseudonym");

    var saved = repository.save(job);
    assertThat(saved.getId()).isEqualTo(job.getId());
    assertThat(saved.getStatus()).isEqualTo(job.getStatus());
    assertThat(repository.findById(job.getId())).get().satisfies(stored -> {
      assertThat(stored.getId()).isEqualTo(saved.getId());
      assertThat(stored.getStatus()).isEqualTo(saved.getStatus());
      assertThat(stored.getNextStep()).isEqualTo(saved.getNextStep());
    });
  }

  @Test
  void accountTrustPortPreservesEveryRelationshipQueryAndDelete() {
    var repository = adapter(
        AccountTrustRepository.class,
        "dev.christopherbell.account.trust.MongoAccountTrustRepository");
    var first = trust("trust-1", "owner-1", "target-1", AccountTrustType.MUTE);
    var second = trust("trust-2", "owner-1", "target-2", AccountTrustType.BLOCK);
    var third = trust("trust-3", "owner-2", "target-1", AccountTrustType.MUTE);
    repository.save(first);
    repository.save(second);
    repository.save(third);

    assertThat(repository.findByOwnerAccountIdAndTargetAccountIdAndType(
        "owner-1", "target-1", AccountTrustType.MUTE)).contains(first);
    assertThat(repository.findByOwnerAccountIdAndTypeIn(
        "owner-1", Set.of(AccountTrustType.MUTE, AccountTrustType.BLOCK)))
        .containsExactlyInAnyOrder(first, second);
    assertThat(repository.findByTargetAccountIdAndOwnerAccountIdInAndType(
        "target-1", Set.of("owner-1", "owner-2"), AccountTrustType.MUTE))
        .containsExactlyInAnyOrder(first, third);
    assertThat(repository.findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(
        "owner-1", Set.of("target-1", "target-2"), Set.of(AccountTrustType.BLOCK)))
        .containsExactly(second);
    assertThat(repository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        "owner-1", "target-1", AccountTrustType.MUTE)).isTrue();

    repository.deleteByOwnerAccountIdAndTargetAccountIdAndType(
        "owner-1", "target-1", AccountTrustType.MUTE);
    assertThat(repository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        "owner-1", "target-1", AccountTrustType.MUTE)).isFalse();
  }

  @Test
  void browserSessionPortPreservesAllDeleteForms() {
    var repository = adapter(
        BrowserSessionRepository.class,
        "dev.christopherbell.configuration.security.browser.MongoBrowserSessionRepository");
    var first = session("session-1", "account-1");
    var second = session("session-2", "account-1");
    var third = session("session-3", "account-2");
    assertThat(repository.save(first)).isEqualTo(first);
    repository.save(second);
    repository.save(third);

    repository.delete(first);
    repository.deleteById(third.getId());
    assertThat(repository.deleteByAccountId("account-1")).isEqualTo(1);
    assertThat(count(BrowserSession.class)).isZero();
  }

  @Test
  void messagePortPreservesSingleBulkConversationAndParticipantMethods() {
    var repository = adapter(MessageRepository.class,
        "dev.christopherbell.message.MongoMessageRepository");
    var early = message("message-1", "conversation-1", "account-1", second(1));
    var late = message("message-2", "conversation-1", "account-1", second(3));
    var other = message("message-3", "conversation-2", "account-2", second(2));
    assertThat(repository.save(early)).isEqualTo(early);
    assertThat(repository.saveAll(List.of(late, other)))
        .containsExactly(late, other);

    assertThat(repository.findByConversationKeyOrderByCreatedOnAsc(
        "conversation-1", PageRequest.of(0, 10)))
        .extracting(Message::getId).containsExactly("message-1", "message-2");
    assertThat(repository.findByParticipantIdsContainingOrderByCreatedOnDesc(
        "account-1", PageRequest.of(0, 1)))
        .extracting(Message::getId).containsExactly("message-2");
  }

  @Test
  void notificationPortPreservesLookupOrderingPaginationAndUnreadCount() {
    var repository = adapter(NotificationRepository.class,
        "dev.christopherbell.notification.MongoNotificationRepository");
    var early = notification("notification-1", "account-1", false, second(1));
    var late = notification("notification-2", "account-1", false, second(3));
    var read = notification("notification-3", "account-1", true, second(2));
    assertThat(repository.save(early)).isEqualTo(early);
    repository.save(late);
    repository.save(read);

    assertThat(repository.findById(early.getId())).contains(early);
    assertThat(repository.findByAccountIdOrderByCreatedOnDesc(
        "account-1", PageRequest.of(0, 2)))
        .extracting(Notification::getId).containsExactly("notification-2", "notification-3");
    assertThat(repository.countByAccountIdAndReadFalse("account-1")).isEqualTo(2);
  }

  @Test
  void notificationPreferencePortGeneratesAnIdAndLoadsByAccount() {
    var repository = adapter(
        NotificationPreferenceRepository.class,
        "dev.christopherbell.notification.preference.MongoNotificationPreferenceRepository");
    var preference = NotificationPreference.builder()
        .accountId("account-1")
        .mentions(true)
        .build();

    var saved = repository.save(preference);

    assertThat(saved.getId()).matches("[0-9a-f]{24}");
    assertThat(repository.findByAccountId("account-1")).contains(saved);
  }

  @Test
  void postPortPreservesEveryQueryCountPageAndDeleteMethod() {
    var repository = adapter(PostRepository.class,
        "dev.christopherbell.post.MongoPostRepository");
    var root = post("post-root", "account-1", "thread-1", null, second(1), second(9));
    var reply = post("post-reply", "account-1", "thread-1", "post-root", second(2), second(4));
    var noExpiry = post("post-no-expiry", "account-2", "thread-2", null, second(3), null);
    assertThat(repository.save(root)).isEqualTo(root);
    repository.save(reply);
    repository.save(noExpiry);

    assertThat(repository.findById(root.getId())).contains(root);
    assertThat(repository.count()).isEqualTo(3);
    assertThat(repository.findByAccountIdOrderByCreatedOnDesc("account-1"))
        .extracting(Post::getId).containsExactly("post-reply", "post-root");
    assertThat(repository.findByAccountIdOrderByCreatedOnDesc(
        "account-1", PageRequest.of(0, 1)))
        .extracting(Post::getId).containsExactly("post-reply");
    assertThat(repository.findAll(PageRequest.of(
        0, 2, Sort.by(Sort.Direction.DESC, "createdOn"))))
        .extracting(Post::getId).containsExactly("post-no-expiry", "post-reply");
    assertThat(repository.findByRootIdOrderByCreatedOnAsc("thread-1"))
        .extracting(Post::getId).containsExactly("post-root", "post-reply");
    assertThat(repository.findByExpiresOnLessThanEqual(second(5), PageRequest.of(0, 10)))
        .extracting(Post::getId).containsExactly("post-reply");
    assertThat(repository.countByExpiresOnAfter(second(5))).isEqualTo(1);
    assertThat(repository.findByExpiresOnAfter(second(5), PageRequest.of(0, 1)))
        .extracting(Post::getId).containsExactly("post-root");
    assertThat(repository.findByExpiresOnIsNull(PageRequest.of(0, 10)))
        .extracting(Post::getId).containsExactly("post-no-expiry");
    assertThat(repository.countByAccountIdAndParentIdIsNull("account-1")).isEqualTo(1);
    assertThat(repository.countByAccountIdAndParentIdIsNotNull("account-1")).isEqualTo(1);

    repository.delete(reply);
    repository.deleteById(root.getId());
    repository.deleteAll(List.of(noExpiry));
    assertThat(repository.count()).isZero();
  }

  @Test
  void hiddenThreadPortPreservesSaveLookupListAndExactDelete() {
    var repository = adapter(
        HiddenPostThreadRepository.class,
        "dev.christopherbell.post.hide.MongoHiddenPostThreadRepository");
    var first = HiddenPostThread.builder()
        .accountId("account-1").rootPostId("root-1").build();
    var second = HiddenPostThread.builder()
        .accountId("account-1").rootPostId("root-2").build();
    first = repository.save(first);
    second = repository.save(second);

    assertThat(first.getId()).matches("[0-9a-f]{24}");
    assertThat(repository.findByAccountIdAndRootPostId("account-1", "root-1"))
        .contains(first);
    assertThat(repository.findByAccountId("account-1"))
        .containsExactlyInAnyOrder(first, second);
    repository.deleteByAccountIdAndRootPostId("account-1", "root-1");
    assertThat(repository.findByAccountId("account-1")).containsExactly(second);
  }

  @Test
  void linkPreviewCachePortSavesAndLoadsByUrlIdentity() {
    var repository = adapter(
        PostLinkPreviewCacheRepository.class,
        "dev.christopherbell.post.preview.MongoPostLinkPreviewCacheRepository");
    var entry = PostLinkPreviewCacheEntry.failure(
        "https://example.test/article", "SAFE_FAILURE", second(1), second(20));

    assertThat(repository.save(entry)).isEqualTo(entry);
    assertThat(repository.findById(entry.getUrl())).contains(entry);
  }

  @Test
  void reportPortPreservesEveryLookupOrderingPaginationAndCount() {
    var repository = adapter(ReportRepository.class,
        "dev.christopherbell.report.MongoReportRepository");
    var early = report("report-1", "reporter-1", "post-1", "reported-1",
        "dedupe-1", ReportStatus.OPEN, second(1));
    var late = report("report-2", "reporter-2", "post-2", "reported-1",
        "dedupe-2", ReportStatus.OPEN, second(3));
    var resolved = report("report-3", "reporter-1", "post-3", "reported-1",
        null, ReportStatus.RESOLVED, second(2));
    assertThat(repository.save(early)).isEqualTo(early);
    repository.save(late);
    repository.save(resolved);

    assertThat(repository.findById(early.getId())).contains(early);
    assertThat(repository.findByStatusOrderByCreatedOnDesc(ReportStatus.OPEN))
        .extracting(PostReport::getId).containsExactly("report-2", "report-1");
    assertThat(repository.findAllByOrderByCreatedOnDesc())
        .extracting(PostReport::getId).containsExactly("report-2", "report-3", "report-1");
    assertThat(repository.findAllByOrderByCreatedOnDesc(PageRequest.of(0, 2)))
        .extracting(PostReport::getId).containsExactly("report-2", "report-3");
    assertThat(repository.findByOpenDedupeKey("dedupe-1")).contains(early);
    assertThat(repository.findFirstByReporterAccountIdAndPostIdAndStatus(
        "reporter-1", "post-1", ReportStatus.OPEN)).contains(early);
    assertThat(repository.countByReportedAccountIdAndStatus(
        "reported-1", ReportStatus.OPEN)).isEqualTo(2);
  }

  @Test
  void adminActivityPortPreservesInsertSaveLookupGeneratedIdAndTop25Ordering() {
    var repository = adapter(
        AdminActivityRepository.class,
        "dev.christopherbell.admin.activity.MongoAdminActivityRepository");
    AdminActivity generated = null;
    for (int index = 0; index < 26; index++) {
      var activity = AdminActivity.builder()
          .id(index == 0 ? null : "activity-" + index)
          .action("action-" + index)
          .createdOn(second(index))
          .build();
      var inserted = repository.insert(activity);
      if (index == 0) generated = inserted;
    }

    assertThat(generated).isNotNull();
    assertThat(generated.getId()).matches("[0-9a-f]{24}");
    generated.setMessage("updated");
    assertThat(repository.save(generated).getMessage()).isEqualTo("updated");
    assertThat(repository.findById(generated.getId())).get()
        .extracting(AdminActivity::getMessage).isEqualTo("updated");
    assertThat(repository.findTop25ByOrderByCreatedOnDesc())
        .hasSize(25)
        .extracting(AdminActivity::getCreatedOn)
        .isSortedAccordingTo((left, right) -> right.compareTo(left));
  }

  private <T> T adapter(Class<T> port, String implementationName) {
    try {
      var implementation = Class.forName(implementationName);
      var constructor = implementation.getDeclaredConstructor(DomainMongoOperationsFactory.class);
      constructor.setAccessible(true);
      return port.cast(constructor.newInstance(factory));
    } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
        | IllegalAccessException | InvocationTargetException failure) {
      throw new IllegalStateException("Cannot construct repository adapter " + implementationName, failure);
    }
  }

  private void createUniqueIndex(Class<?> type, String field, String name) {
    var kind = DomainCollectionManifest.forType(type);
    mongo.getCollection(kind.collection()).createIndex(
        new Document("_kind", 1).append("payload." + field, 1),
        new com.mongodb.client.model.IndexOptions().unique(true).name(name));
  }

  private long count(Class<?> type) {
    var kind = DomainCollectionManifest.forType(type);
    return mongo.getCollection(kind.collection())
        .countDocuments(new Document("_kind", kind.kind()));
  }

  private static Account account(
      String id, String email, String username, AccountStatus status, boolean federated) {
    return Account.builder()
        .id(id)
        .email(email)
        .username(username)
        .status(status)
        .federationEnabled(federated)
        .build();
  }

  private static AccountTrustRelationship trust(
      String id, String owner, String target, AccountTrustType type) {
    return AccountTrustRelationship.builder()
        .id(id).ownerAccountId(owner).targetAccountId(target).type(type).build();
  }

  private static BrowserSession session(String id, String accountId) {
    return BrowserSession.builder().id(id).accountId(accountId).tokenHash("hash-" + id).build();
  }

  private static Message message(
      String id, String conversationKey, String participantId, Instant createdOn) {
    return Message.builder()
        .id(id)
        .conversationKey(conversationKey)
        .participantIds(Set.of(participantId, "shared"))
        .createdOn(createdOn)
        .build();
  }

  private static Notification notification(
      String id, String accountId, boolean read, Instant createdOn) {
    return Notification.builder()
        .id(id)
        .accountId(accountId)
        .notificationType(NotificationType.MENTION)
        .read(read)
        .createdOn(createdOn)
        .build();
  }

  private static Post post(
      String id,
      String accountId,
      String rootId,
      String parentId,
      Instant createdOn,
      Instant expiresOn) {
    return Post.builder()
        .id(id)
        .accountId(accountId)
        .rootId(rootId)
        .parentId(parentId)
        .level(parentId == null ? 0 : 1)
        .createdOn(createdOn)
        .expiresOn(expiresOn)
        .build();
  }

  private static PostReport report(
      String id,
      String reporterId,
      String postId,
      String reportedId,
      String dedupeKey,
      ReportStatus status,
      Instant createdOn) {
    return PostReport.builder()
        .id(id)
        .reporterAccountId(reporterId)
        .postId(postId)
        .reportedAccountId(reportedId)
        .openDedupeKey(dedupeKey)
        .reportType(ReportType.SPAM)
        .targetType(ReportTargetType.POST)
        .status(status)
        .createdOn(createdOn)
        .build();
  }

  private static Instant second(int value) {
    return Instant.parse("2026-08-10T20:00:00Z").plusSeconds(value);
  }
}
