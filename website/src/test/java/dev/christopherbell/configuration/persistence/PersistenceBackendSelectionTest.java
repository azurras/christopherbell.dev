package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PersistenceBackendSelectionTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          PersistenceBackendConfiguration.class,
          MongoAdapterConfiguration.class,
          PostgresAdapterConfiguration.class);

  @Test
  void mongodbBackendActivatesOnlyMongoAdapters() {
    contextRunner.withPropertyValues("app.persistence.backend=mongodb")
        .run(context -> {
          assertThat(context).hasSingleBean(MongoAdapter.class);
          assertThat(context).doesNotHaveBean(PostgresAdapter.class);
        });
  }

  @Test
  void postgresqlBackendActivatesOnlyPostgresqlAdapters() {
    contextRunner.withPropertyValues("app.persistence.backend=postgresql")
        .run(context -> {
          assertThat(context).hasSingleBean(PostgresAdapter.class);
          assertThat(context).doesNotHaveBean(MongoAdapter.class);
        });
  }

  @Test
  void rejectsAnyBackendOutsideTheTransitionSetDuringConfigurationBinding() {
    contextRunner.withPropertyValues("app.persistence.backend=unsupported")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  @Configuration(proxyBeanMethods = false)
  @MongoPersistence
  static class MongoAdapterConfiguration {
    @Bean
    MongoAdapter mongoAdapter() {
      return new MongoAdapter();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @PostgresPersistence
  static class PostgresAdapterConfiguration {
    @Bean
    PostgresAdapter postgresAdapter() {
      return new PostgresAdapter();
    }
  }

  static final class MongoAdapter {}

  static final class PostgresAdapter {}
}
