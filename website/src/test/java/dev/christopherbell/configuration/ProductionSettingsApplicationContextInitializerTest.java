package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ProductionSettingsApplicationContextInitializerTest {
  private static final String VALID_JWT =
      "production-test-jwt-secret-that-is-long-enough-for-hs256";

  private final ProductionSettingsApplicationContextInitializer initializer =
      new ProductionSettingsApplicationContextInitializer();

  @Test
  void productionAggregatesMissingSettingsWithoutLeakingValues() {
    var context = context("prod", Map.of(
        "APP_PERSISTENCE_BACKEND", "mongodb",
        "APP_JWT_SECRET", "short-secret-value",
        "APP_MAIL_ENABLED", "true"));

    assertThatThrownBy(() -> initializer.initialize(context))
        .hasMessageContaining("Invalid production configuration")
        .hasMessageContaining(
            "SPRING_MONGODB_URI", "APP_JWT_SECRET", "APP_MAIL_FROM", "RESEND_API_KEY")
        .hasMessageNotContaining("short-secret-value");
  }

  @Test
  void malformedCredentialBearingMongoUriIsReportedWithoutLeakingIt() {
    String unsafeUri = "mongodb://user:super-secret@[invalid";
    var context = validProductionContext(Map.of("SPRING_MONGODB_URI", unsafeUri));

    assertThatThrownBy(() -> initializer.initialize(context))
        .hasMessageContaining("SPRING_MONGODB_URI")
        .hasMessageNotContaining(unsafeUri)
        .hasMessageNotContaining("super-secret");
  }

  @Test
  void explicitMailDisableMakesMailCredentialsOptional() {
    var context = validProductionContext(Map.of("APP_MAIL_ENABLED", "false"));

    assertThatCode(() -> initializer.initialize(context)).doesNotThrowAnyException();
  }

  @Test
  void enabledMailAcceptsConfiguredSenderAndProviderKey() {
    var context = validProductionContext(Map.of(
        "APP_MAIL_ENABLED", "true",
        "APP_MAIL_FROM", "noreply@christopherbell.dev",
        "RESEND_API_KEY", "re_test"));

    assertThatCode(() -> initializer.initialize(context)).doesNotThrowAnyException();
  }

  @Test
  void invalidSenderAndMailSwitchJoinTheSameRedactedReport() {
    var context = validProductionContext(Map.of(
        "APP_MAIL_ENABLED", "sometimes",
        "APP_MAIL_FROM", "not-an-email",
        "RESEND_API_KEY", "re_test"));

    assertThatThrownBy(() -> initializer.initialize(context))
        .hasMessageContaining("APP_MAIL_ENABLED", "APP_MAIL_FROM")
        .hasMessageNotContaining("not-an-email")
        .hasMessageNotContaining("re_test");
  }

  @Test
  void enabledMailRequiresAValidSenderAndNonPlaceholderKey() {
    var context = validProductionContext(Map.of(
        "APP_MAIL_ENABLED", "true",
        "APP_MAIL_FROM", "not-an-email",
        "RESEND_API_KEY", "re_your_resend_api_key"));

    assertThatThrownBy(() -> initializer.initialize(context))
        .hasMessageContaining("APP_MAIL_FROM", "RESEND_API_KEY")
        .hasMessageNotContaining("not-an-email")
        .hasMessageNotContaining("re_your_resend_api_key");
  }

  @Test
  void nonProductionProfilesAreNotSubjectToProductionRequirements() {
    assertThatCode(() -> initializer.initialize(context("local", Map.of())))
        .doesNotThrowAnyException();
  }

  @Test
  void productionRequiresAnExplicitPostgresqlBackendAndJdbcCredentialsWithoutLeakingThem() {
    String unsafeJdbcUrl = "jdbc:postgresql://db.example/test?password=do-not-echo";
    var context = validProductionContext(Map.of(
        "APP_PERSISTENCE_BACKEND", "postgresql",
        "SPRING_DATASOURCE_URL", unsafeJdbcUrl,
        "SPRING_DATASOURCE_USERNAME", "database-user",
        "SPRING_DATASOURCE_PASSWORD", "database-secret"));

    assertThatCode(() -> initializer.initialize(context)).doesNotThrowAnyException();

    var incompleteContext = validProductionContext(Map.of("APP_PERSISTENCE_BACKEND", "postgresql"));
    assertThatThrownBy(() -> initializer.initialize(incompleteContext))
        .hasMessageContaining("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")
        .hasMessageNotContaining("database-secret")
        .hasMessageNotContaining(unsafeJdbcUrl);
  }

  @Test
  void productionRejectsMissingAndUnsupportedPersistenceBackends() {
    var missing = context("prod", Map.of(
        "APP_JWT_SECRET", VALID_JWT,
        "APP_MAIL_ENABLED", "false"));
    assertThatThrownBy(() -> initializer.initialize(missing))
        .hasMessageContaining("APP_PERSISTENCE_BACKEND");

    var unsupported = context("prod", Map.of(
        "APP_PERSISTENCE_BACKEND", "unsupported",
        "APP_JWT_SECRET", VALID_JWT,
        "APP_MAIL_ENABLED", "false"));
    assertThatThrownBy(() -> initializer.initialize(unsupported))
        .hasMessageContaining("APP_PERSISTENCE_BACKEND")
        .hasMessageNotContaining("unsupported");
  }

  private GenericApplicationContext validProductionContext(Map<String, String> overrides) {
    var values = new LinkedHashMap<String, String>();
    values.put("APP_PERSISTENCE_BACKEND", "mongodb");
    values.put("SPRING_MONGODB_URI", "mongodb://127.0.0.1:27017");
    values.put("APP_JWT_SECRET", VALID_JWT);
    values.put("APP_MAIL_ENABLED", "false");
    values.putAll(overrides);
    return context("prod", values);
  }

  private GenericApplicationContext context(String profile, Map<String, String> values) {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    values.forEach(environment::setProperty);
    var context = new GenericApplicationContext();
    context.setEnvironment(environment);
    return context;
  }
}
