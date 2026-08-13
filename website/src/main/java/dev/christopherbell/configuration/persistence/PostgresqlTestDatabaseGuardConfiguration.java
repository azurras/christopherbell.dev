package dev.christopherbell.configuration.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Installs the database guard only for PostgreSQL-backed test application contexts. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
@ConditionalOnProperty(prefix = "app.persistence", name = "backend", havingValue = "postgresql")
@EnableConfigurationProperties(PostgresqlTestDatabaseGuardProperties.class)
public class PostgresqlTestDatabaseGuardConfiguration {
  @Bean
  public PostgresqlTestDatabaseGuardInitializer postgresqlTestDatabaseGuardInitializer(
      JdbcTemplate jdbc, PostgresqlTestDatabaseGuardProperties properties) {
    return new PostgresqlTestDatabaseGuardInitializer(jdbc, properties);
  }

  /** Performs the identity check during bean initialization, before readiness is published. */
  public static final class PostgresqlTestDatabaseGuardInitializer {
    public PostgresqlTestDatabaseGuardInitializer(
        JdbcTemplate jdbc, PostgresqlTestDatabaseGuardProperties properties) {
      PostgresqlTestDatabaseGuard.verify(jdbc, properties);
    }
  }
}
