package dev.christopherbell.configuration.persistence;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/** Selects persistence framework auto-configurations from the sole backend setting. */
public final class PersistenceBackendAutoConfigurationImportFilter
    implements AutoConfigurationImportFilter, EnvironmentAware {
  private static final Set<String> MONGO_AUTO_CONFIGURATIONS = Set.of(
      "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
      "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration",
      "org.springframework.boot.mongodb.autoconfigure.health.MongoHealthContributorAutoConfiguration",
      "org.springframework.boot.mongodb.autoconfigure.health.MongoReactiveHealthContributorAutoConfiguration",
      "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration",
      "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration");
  private static final Set<String> RELATIONAL_AUTO_CONFIGURATIONS = Set.of(
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
      "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
  private PersistenceBackend backend;

  @Override
  public void setEnvironment(Environment environment) {
    backend = parse(environment.getProperty("app.persistence.backend"));
  }

  @Override
  public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
    boolean[] matches = new boolean[autoConfigurationClasses.length];
    for (int index = 0; index < autoConfigurationClasses.length; index++) {
      matches[index] = matches(autoConfigurationClasses[index]);
    }
    return matches;
  }

  private boolean matches(String autoConfigurationClass) {
    if (autoConfigurationClass == null) {
      return false;
    }
    if (MONGO_AUTO_CONFIGURATIONS.contains(autoConfigurationClass)) {
      return backend == PersistenceBackend.MONGODB;
    }
    if (RELATIONAL_AUTO_CONFIGURATIONS.contains(autoConfigurationClass)) {
      return backend == PersistenceBackend.POSTGRESQL;
    }
    return true;
  }

  private static PersistenceBackend parse(String value) {
    if (value == null) {
      return null;
    }
    try {
      return PersistenceBackend.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
