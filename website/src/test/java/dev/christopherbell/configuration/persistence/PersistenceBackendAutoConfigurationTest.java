package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class PersistenceBackendAutoConfigurationTest {
  private static final String AUTO_CONFIGURATION_IMPORTS =
      "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(PersistenceBackendConfiguration.class, AutoConfigurationApplication.class);

  @Test
  void mongodbBackendStartsMongoWithoutRelationalInfrastructure() {
    contextRunner.withPropertyValues(
            "app.persistence.backend=mongodb",
            "spring.mongodb.uri=mongodb://127.0.0.1:27017/test")
        .run(context -> {
          assertThat(context.getStartupFailure()).isNull();
          assertThat(context).hasSingleBean(MongoClient.class);
          assertThat(context).doesNotHaveBean(DataSource.class);
          assertThat(context).doesNotHaveBean(DSLContext.class);
        });
  }

  @Test
  void postgresqlBackendStartsRelationalInfrastructureWithoutMongo() {
    contextRunner.withPropertyValues(
            "app.persistence.backend=postgresql",
            "spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource",
            "spring.datasource.driver-class-name=org.postgresql.Driver",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/test",
            "spring.flyway.enabled=false")
        .run(context -> {
          assertThat(context.getStartupFailure()).isNull();
          assertThat(context).hasSingleBean(DataSource.class);
          assertThat(context).hasSingleBean(DSLContext.class);
          assertThat(context).doesNotHaveBean(MongoClient.class);
        });
  }

  @Test
  void filterAllowsOnlyTheSelectedPersistenceAutoConfigurations() {
    var environment = new MockEnvironment().withProperty("app.persistence.backend", "postgresql");
    var filter = new PersistenceBackendAutoConfigurationImportFilter();
    filter.setEnvironment(environment);

    assertThat(filter.match(new String[] {
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration"
    }, null)).containsExactly(false, true, true, true);
  }

  @Test
  void selectorGatesEveryResolvedPersistenceAutoConfiguration() throws IOException {
    var candidates = resolvedPersistenceAutoConfigurations();

    assertThat(candidates).isNotEmpty();
    assertThat(candidates).allSatisfy(candidate ->
        assertThat(PersistenceBackendAutoConfigurationImportFilter.requiredBackend(candidate))
            .as("resolved persistence auto-configuration %s must have a reviewed backend", candidate)
            .isNotNull());
    assertSelectorMatches(candidates, "mongodb");
    assertSelectorMatches(candidates, "postgresql");
    assertSelectorMatches(candidates, null);
    assertSelectorMatches(candidates, "unsupported");
  }

  @Test
  void missingOrInvalidBackendFailsBeforeAnyPersistenceAutoConfigurationStarts() {
    contextRunner.run(context -> assertThat(context.getStartupFailure()).isNotNull());
    contextRunner.withPropertyValues("app.persistence.backend=unsupported")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  private static List<String> resolvedPersistenceAutoConfigurations() throws IOException {
    var resources = PersistenceBackendAutoConfigurationTest.class.getClassLoader()
        .getResources(AUTO_CONFIGURATION_IMPORTS);
    var candidates = new java.util.ArrayList<String>();
    while (resources.hasMoreElements()) {
      try (var reader = new BufferedReader(new InputStreamReader(
          resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
        candidates.addAll(reader.lines()
            .map(String::strip)
            .filter(candidate -> candidate.startsWith("org.springframework.boot."))
            .filter(PersistenceBackendAutoConfigurationTest::isPersistenceAutoConfiguration)
            .toList());
      }
    }
    return candidates.stream().distinct().sorted().toList();
  }

  private static boolean isPersistenceAutoConfiguration(String candidate) {
    return candidate.contains(".mongodb.")
        || candidate.contains(".jdbc.")
        || candidate.contains(".jooq.")
        || candidate.contains(".flyway.");
  }

  private static void assertSelectorMatches(List<String> candidates, String backend) {
    var environment = new MockEnvironment();
    if (backend != null) {
      environment.setProperty("app.persistence.backend", backend);
    }
    var filter = new PersistenceBackendAutoConfigurationImportFilter();
    filter.setEnvironment(environment);

    assertThat(filter.match(candidates.toArray(String[]::new), null))
        .containsExactly(candidates.stream()
            .map(candidate -> expectedMatch(candidate, backend))
            .toArray(Boolean[]::new));
  }

  private static boolean expectedMatch(String candidate, String backend) {
    if (backend == null) {
      return false;
    }
    return switch (backend) {
      case "mongodb" -> candidate.contains(".mongodb.");
      case "postgresql" -> !candidate.contains(".mongodb.");
      default -> false;
    };
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class AutoConfigurationApplication {}
}
