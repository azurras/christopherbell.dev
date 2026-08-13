package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PostgresqlPersistenceBoundaryRulesTest {
  private static final Map<String, String> CONTRACT_GROUP_BY_ADAPTER = Map.ofEntries(
      entry("dev.christopherbell.account.PostgresAccountRepository", "account-parity"),
      entry("dev.christopherbell.account.PostgresAdminAccountQueryService", "identity-query"),
      entry("dev.christopherbell.account.auth.PostgresAccountLoginStore", "identity-lifecycle"),
      entry("dev.christopherbell.account.deletion.PostgresAccountDeletionJobRepository", "identity-lifecycle"),
      entry("dev.christopherbell.account.deletion.PostgresAccountDeletionOperations", "identity-lifecycle"),
      entry("dev.christopherbell.account.follow.PostgresAccountFollowStore", "identity-relationship"),
      entry("dev.christopherbell.account.trust.PostgresAccountTrustRepository", "identity-relationship"),
      entry("dev.christopherbell.configuration.security.browser.PostgresBrowserSessionRepository", "browser-session"),
      entry("dev.christopherbell.configuration.security.browser.PostgresBrowserSessionAuthenticationStore", "browser-session"),
      entry("dev.christopherbell.configuration.security.browser.PostgresBrowserSessionActivityStore", "browser-session"),
      entry("dev.christopherbell.federation.discovery.PostgresFederationOutboxQueryRepository", "federation"),
      entry("dev.christopherbell.federation.outbound.PostgresFederationDeliveryJobRepository", "federation"),
      entry("dev.christopherbell.message.PostgresMessageRepository", "message-parity"),
      entry("dev.christopherbell.message.conversation.PostgresConversationArchiveService", "conversation"),
      entry("dev.christopherbell.message.conversation.PostgresConversationQueryRepository", "conversation"),
      entry("dev.christopherbell.notification.PostgresNotificationRepository", "notification-parity"),
      entry("dev.christopherbell.notification.delivery.PostgresNotificationFanoutGuard", "notification-cleanup-parity"),
      entry("dev.christopherbell.notification.inbox.PostgresNotificationQueryRepository", "notification-query"),
      entry("dev.christopherbell.notification.preference.PostgresNotificationPreferenceRepository", "notification-preference"),
      entry("dev.christopherbell.post.PostgresPostRepository", "post-parity"),
      entry("dev.christopherbell.post.discovery.PostgresVoidDiscoveryQueryRepository", "post-discovery"),
      entry("dev.christopherbell.post.discovery.PostgresVoidPeopleDiscoveryQueryRepository", "post-discovery"),
      entry("dev.christopherbell.post.expiration.PostgresPostExpirationStore", "post-expiration"),
      entry("dev.christopherbell.post.feed.PostgresPostEngagementQueryRepository", "post-feed"),
      entry("dev.christopherbell.post.feed.PostgresPostFeedQueryRepository", "post-feed"),
      entry("dev.christopherbell.post.hide.PostgresHiddenPostThreadRepository", "post-interaction"),
      entry("dev.christopherbell.post.like.PostgresPostLikeStore", "post-interaction"),
      entry("dev.christopherbell.post.preview.PostgresPostLinkPreviewCacheRepository", "preview-cleanup-parity"),
      entry("dev.christopherbell.report.PostgresReportRepository", "report"),
      entry("dev.christopherbell.report.query.PostgresReportQueryService", "report"));
  private static final Set<String> SHARED_REAL_ENGINE_GROUPS = Set.of(
      "account-parity",
      "browser-session",
      "conversation",
      "federation",
      "identity-lifecycle",
      "identity-query",
      "identity-relationship",
      "message-parity",
      "notification-cleanup-parity",
      "notification-parity",
      "notification-preference",
      "notification-query",
      "post-discovery",
      "post-expiration",
      "post-feed",
      "post-interaction",
      "post-parity",
      "preview-cleanup-parity",
      "report");

  @Test
  void jooqDependenciesStayInsidePostgresqlConfigurationGeneratedCodeAndAdapters() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var violations = classes.stream()
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.persistence.jooq"))
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.configuration.postgresql"))
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.configuration.persistence"))
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.codegen"))
        .filter(javaClass -> !javaClass.getName().contains("Test"))
        .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
            .map(dependency -> dependency.getTargetClass().getPackageName())
            .anyMatch(packageName -> packageName.startsWith("org.jooq")
                || packageName.startsWith("org.postgresql")
                || packageName.equals("java.sql")
                || packageName.startsWith("dev.christopherbell.persistence.jooq")))
        .filter(javaClass -> !javaClass.isAnnotatedWith(PostgresPersistence.class))
        .filter(javaClass -> !javaClass.isAnnotatedWith(PostgresPersistenceSupport.class))
        .map(javaClass -> javaClass.getName())
        .sorted()
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  void taskThreePostgresqlAdaptersAreSelectedAndImplementPorts() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var adapters = classes.stream()
        .filter(javaClass -> javaClass.getSimpleName().startsWith("Postgres"))
        .filter(javaClass -> javaClass.isAnnotatedWith(PostgresPersistence.class))
        .filter(javaClass -> !javaClass.getName().contains("Test"))
        .toList();

    assertThat(adapters).isNotEmpty();
    assertThat(adapters).allSatisfy(adapter ->
        assertThat(adapter.getRawInterfaces()).as(adapter.getName()).isNotEmpty());
  }

  @Test
  void everyPostgresqlAdapterHasAnExplicitContractGroup() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var adapters = classes.stream()
        .filter(javaClass -> javaClass.isAnnotatedWith(PostgresPersistence.class))
        .filter(javaClass -> !javaClass.getName().contains("Test"))
        .map(javaClass -> javaClass.getName())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    assertThat(CONTRACT_GROUP_BY_ADAPTER.keySet()).containsExactlyInAnyOrderElementsOf(adapters);
    assertThat(Set.copyOf(CONTRACT_GROUP_BY_ADAPTER.values()))
        .containsExactlyInAnyOrderElementsOf(SHARED_REAL_ENGINE_GROUPS);
  }

  @Test
  void postgresNamedPersistenceTypesCannotEscapeSemanticBoundaryMarkers() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var violations = classes.stream()
        .filter(javaClass -> javaClass.getSimpleName().startsWith("Postgres"))
        .filter(javaClass -> !javaClass.getName().contains("Test"))
        .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
            .anyMatch(dependency -> dependency.getTargetClass().getPackageName()
                .startsWith("dev.christopherbell.persistence.jooq")))
        .filter(javaClass -> !javaClass.isAnnotatedWith(PostgresPersistence.class))
        .filter(javaClass -> !javaClass.isAnnotatedWith(PostgresPersistenceSupport.class))
        .map(javaClass -> javaClass.getName()).sorted().toList();

    assertThat(violations).isEmpty();
  }

  @Test
  void postgresqlAdaptersDoNotDependOnAnotherContextsPostgresqlAdapter() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var violations = classes.stream()
        .filter(javaClass -> javaClass.isAnnotatedWith(PostgresPersistence.class)
            || javaClass.isAnnotatedWith(PostgresPersistenceSupport.class))
        .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
        .filter(dependency -> dependency.getTargetClass()
            .isAnnotatedWith(PostgresPersistence.class)
             || dependency.getTargetClass().isAnnotatedWith(PostgresPersistenceSupport.class)
             || dependency.getTargetClass().getSimpleName().startsWith("Postgres"))
        .filter(dependency -> !dependency.getTargetClass().getPackageName()
            .equals("dev.christopherbell.configuration.persistence"))
        .filter(dependency -> !topLevelArea(dependency.getOriginClass().getPackageName())
            .equals(topLevelArea(dependency.getTargetClass().getPackageName())))
        .map(dependency -> "%s -> %s".formatted(
            dependency.getOriginClass().getName(), dependency.getTargetClass().getName()))
        .distinct()
        .sorted()
        .toList();

    assertThat(violations).isEmpty();
  }

  private static String topLevelArea(String packageName) {
    var prefix = "dev.christopherbell.";
    var remainder = packageName.substring(prefix.length());
    var separator = remainder.indexOf('.');
    return separator < 0 ? remainder : remainder.substring(0, separator);
  }

  private static Map.Entry<String, String> entry(String adapter, String group) {
    return Map.entry(adapter, group);
  }
}
