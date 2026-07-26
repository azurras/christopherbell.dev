package dev.christopherbell.configuration.mongo.migration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for the application migration runner. */
@ConfigurationProperties("app.migrations")
public record MigrationProperties(Duration leaseDuration) {
  public static final String LEASE_NAME = "application-migrations";

  public MigrationProperties {
    if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(30)) < 0) {
      throw new IllegalArgumentException("Migration lease duration must be at least 30 seconds.");
    }
  }
}
