package dev.christopherbell.admin.commandcenter.metrics;

import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes one backend-neutral readiness contributor for the selected database. */
@Configuration(proxyBeanMethods = false)
public class DatabaseHealthConfiguration {
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

  @Bean
  public HealthIndicator databaseHealthIndicator(DatabaseConnectivityProbe database) {
    return () -> database.ping(PROBE_TIMEOUT)
        ? Health.up().build()
        : Health.down().build();
  }
}
