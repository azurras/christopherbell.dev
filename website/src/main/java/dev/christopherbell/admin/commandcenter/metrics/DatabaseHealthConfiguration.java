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
  public HealthIndicator databaseHealthIndicator(
      DatabaseConnectivityProbe database,
      PersistenceIdentityProbe identityProbe) {
    return () -> {
      if (!database.ping(PROBE_TIMEOUT)) {
        return Health.down().build();
      }
      try {
        var identity = identityProbe.identity(PROBE_TIMEOUT);
        return Health.up()
            .withDetail("backend", identity.backend())
            .withDetail("database", identity.database())
            .withDetail("schemaVersion", identity.schemaVersion())
            .build();
      } catch (RuntimeException failure) {
        return Health.down().build();
      }
    };
  }
}
