package dev.christopherbell.configuration.mongo;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Spring configuration that enables and wires Spring Data MongoDB auditing.
 *
 * <p>This configuration:</p>
 * - Activates auditing via {@link EnableMongoAuditing}.
 * - Provides an {@link AuditorAware} that resolves the current auditor from the
 *   Spring Security {@link SecurityContextHolder} (using the authenticated principal's name).
 * - Supplies a {@link DateTimeProvider} that sources timestamps from a UTC {@link Clock}.
 *
 * <p>With this in place, entities annotated with auditing annotations such as
 * {@code @CreatedDate}, {@code @LastModifiedDate}, {@code @CreatedBy}, and
 * {@code @LastModifiedBy} will be automatically populated.</p>
 *
 * @see EnableMongoAuditing
 * @see AuditorAware
 * @see DateTimeProvider
 */
@Configuration
@EnableMongoAuditing(
    auditorAwareRef = "auditorAware",
    dateTimeProviderRef = "auditingDateTimeProvider"
)
public class MongoAuditingConfig {

  /**
   * Auditor provider backed by Spring Security.
   *
   * <p>Resolves the current auditor (username) from the
   * {@link SecurityContextHolder}. If no authentication is present or the
   * request is unauthenticated, the returned {@code Optional} will be empty.</p>
   *
   * @return an {@link AuditorAware} that returns the current user's username
   */
  @Bean
  public AuditorAware<String> auditorAware() {
    return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
        .map(Authentication::getName);
  }

  /**
   * UTC clock for deterministic auditing timestamps.
   *
   * @return a {@link Clock} set to UTC
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Date/time provider used by Spring Data auditing.
   *
   * <p>Uses the injected {@link Clock} (UTC) to provide the current
   * {@link Instant} for auditing annotations.</p>
   *
   * @param clock the clock to source the current time from (UTC)
   * @return a {@link DateTimeProvider} that supplies the current {@link Instant}
   */
  @Bean
  public DateTimeProvider auditingDateTimeProvider(Clock clock) {
    return () -> Optional.of(Instant.now(clock));
  }
}
