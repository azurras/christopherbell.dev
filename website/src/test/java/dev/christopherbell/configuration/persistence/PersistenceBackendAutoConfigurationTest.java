package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class PersistenceBackendAutoConfigurationTest {
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
  void missingOrInvalidBackendFailsBeforeAnyPersistenceAutoConfigurationStarts() {
    contextRunner.run(context -> assertThat(context.getStartupFailure()).isNotNull());
    contextRunner.withPropertyValues("app.persistence.backend=unsupported")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class AutoConfigurationApplication {}
}
