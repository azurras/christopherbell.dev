package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DatabaseHealthConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(DatabaseHealthConfiguration.class, ProbeConfiguration.class);

  @Test
  void exposesTheSelectedDatabaseAsTheNeutralReadinessContributor() {
    contextRunner.run(context -> {
      assertThat(context.getStartupFailure()).isNull();
      assertThat(context).hasBean("databaseHealthIndicator");
      assertThat(context.getBean("databaseHealthIndicator", HealthIndicator.class)
          .health().getStatus()).isEqualTo(Status.UP);
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class ProbeConfiguration {
    @Bean
    DatabaseConnectivityProbe databaseConnectivityProbe() {
      return new DatabaseConnectivityProbe() {
        @Override
        public boolean ping(Duration timeout) {
          return timeout.equals(Duration.ofSeconds(2));
        }
      };
    }
  }
}
