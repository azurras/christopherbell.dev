package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.deletion.AccountDeletionJobRepository;
import dev.christopherbell.account.trust.AccountTrustRepository;
import dev.christopherbell.admin.activity.AdminActivityRepository;
import dev.christopherbell.configuration.security.browser.BrowserSessionRepository;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.preference.NotificationPreferenceRepository;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.hide.HiddenPostThreadRepository;
import dev.christopherbell.post.preview.PostLinkPreviewCacheRepository;
import dev.christopherbell.report.ReportRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Freezes the service-facing repository ports while their Mongo implementation changes. */
class SocialDomainRepositoryPortContractTest {
  @Test
  void repositoriesAreExplicitPortsWithEverySupportedMethodDeclared() {
    assertPort(AccountRepository.class, Set.of(
        "countByStatus(AccountStatus)",
        "deleteById(String)",
        "existsById(String)",
        "findAll(Pageable)",
        "findAllById(Iterable)",
        "findByEmail(String)",
        "findByEmailIgnoreCase(String)",
        "findById(String)",
        "findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(Collection,AccountStatus,Pageable)",
        "findByPasswordResetTokenHash(String)",
        "findByStatus(AccountStatus,Pageable)",
        "findByUsername(String)",
        "findByUsernameAndStatus(String,AccountStatus)",
        "findByUsernameIgnoreCase(String)",
        "findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(String,AccountStatus)",
        "findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(String,AccountStatus,Pageable)",
        "save(Account)"));
    assertPort(AccountDeletionJobRepository.class, Set.of(
        "findById(String)", "save(AccountDeletionJob)"));
    assertPort(AccountTrustRepository.class, Set.of(
        "deleteByOwnerAccountIdAndTargetAccountIdAndType(String,String,AccountTrustType)",
        "existsByOwnerAccountIdAndTargetAccountIdAndType(String,String,AccountTrustType)",
        "findByOwnerAccountIdAndTargetAccountIdAndType(String,String,AccountTrustType)",
        "findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(String,Collection,Collection)",
        "findByOwnerAccountIdAndTypeIn(String,Collection)",
        "findByTargetAccountIdAndOwnerAccountIdInAndType(String,Collection,AccountTrustType)",
        "save(AccountTrustRelationship)"));
    assertPort(BrowserSessionRepository.class, Set.of(
        "delete(BrowserSession)", "deleteByAccountId(String)", "deleteById(String)",
        "save(BrowserSession)"));
    assertPort(MessageRepository.class, Set.of(
        "findByConversationKeyOrderByCreatedOnAsc(String,Pageable)",
        "findByParticipantIdsContainingOrderByCreatedOnDesc(String,Pageable)",
        "save(Message)", "saveAll(Iterable)"));
    assertPort(NotificationRepository.class, Set.of(
        "countByAccountIdAndReadFalse(String)",
        "findByAccountIdOrderByCreatedOnDesc(String,Pageable)",
        "findById(String)", "save(Notification)"));
    assertPort(NotificationPreferenceRepository.class, Set.of(
        "findByAccountId(String)", "save(NotificationPreference)"));
    assertPort(PostRepository.class, Set.of(
        "countByAccountIdAndParentIdIsNotNull(String)",
        "countByAccountIdAndParentIdIsNull(String)",
        "countByExpiresOnAfter(Instant)",
        "count()",
        "delete(Post)", "deleteAll(Iterable)", "deleteById(String)",
        "findAll(Pageable)",
        "findByAccountIdOrderByCreatedOnDesc(String)",
        "findByAccountIdOrderByCreatedOnDesc(String,Pageable)",
        "findByExpiresOnAfter(Instant,Pageable)",
        "findByExpiresOnIsNull(Pageable)",
        "findByExpiresOnLessThanEqual(Instant,Pageable)",
        "findById(String)",
        "findByRootIdOrderByCreatedOnAsc(String)",
        "save(Post)"));
    assertPort(HiddenPostThreadRepository.class, Set.of(
        "deleteByAccountIdAndRootPostId(String,String)",
        "findByAccountId(String)",
        "findByAccountIdAndRootPostId(String,String)",
        "save(HiddenPostThread)"));
    assertPort(PostLinkPreviewCacheRepository.class, Set.of(
        "findById(String)", "save(PostLinkPreviewCacheEntry)"));
    assertPort(ReportRepository.class, Set.of(
        "countByReportedAccountIdAndStatus(String,ReportStatus)",
        "findAllByOrderByCreatedOnDesc()",
        "findAllByOrderByCreatedOnDesc(Pageable)",
        "findById(String)",
        "findByOpenDedupeKey(String)",
        "findByStatusOrderByCreatedOnDesc(ReportStatus)",
        "findFirstByReporterAccountIdAndPostIdAndStatus(String,String,ReportStatus)",
        "save(PostReport)"));
    assertPort(AdminActivityRepository.class, Set.of(
        "findById(String)", "findTop25ByOrderByCreatedOnDesc()",
        "insert(AdminActivity)", "save(AdminActivity)"));
  }

  private static void assertPort(Class<?> port, Set<String> expectedSignatures) {
    assertThat(MongoRepository.class.isAssignableFrom(port)).isFalse();
    assertThat(Arrays.stream(port.getDeclaredMethods())
        .map(SocialDomainRepositoryPortContractTest::signature)
        .collect(Collectors.toSet()))
        .containsExactlyInAnyOrderElementsOf(expectedSignatures);
  }

  private static String signature(Method method) {
    return method.getName() + Arrays.stream(method.getParameterTypes())
        .map(Class::getSimpleName)
        .collect(Collectors.joining(",", "(", ")"));
  }
}
