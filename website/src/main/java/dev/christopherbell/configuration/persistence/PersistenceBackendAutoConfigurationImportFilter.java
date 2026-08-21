package dev.christopherbell.configuration.persistence;

import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/** Selects persistence framework auto-configurations from the sole backend setting. */
public final class PersistenceBackendAutoConfigurationImportFilter
    implements AutoConfigurationImportFilter, EnvironmentAware {
  private static final String MONGODB_AUTO_CONFIGURATION_PREFIX =
      "org.springframework.boot.mongodb.";
  private static final String DATA_MONGODB_AUTO_CONFIGURATION_PREFIX =
      "org.springframework.boot.data.mongodb.";
  private static final String JDBC_AUTO_CONFIGURATION_PREFIX =
      "org.springframework.boot.jdbc.";
  private static final String JOOQ_AUTO_CONFIGURATION_PREFIX =
      "org.springframework.boot.jooq.";
  private static final String FLYWAY_AUTO_CONFIGURATION_PREFIX =
      "org.springframework.boot.flyway.";
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
    var requiredBackend = requiredBackend(autoConfigurationClass);
    return requiredBackend == null
        ? autoConfigurationClass != null
        : requiredBackend == backend;
  }

  /**
   * Classifies every Boot 4.1 MongoDB, JDBC, jOOQ, and Flyway auto-configuration family
   * available to this application. The resolved-import contract test requires review when that
   * set grows.
   */
  static PersistenceBackend requiredBackend(String autoConfigurationClass) {
    if (autoConfigurationClass == null) {
      return null;
    }
    if (autoConfigurationClass.startsWith(MONGODB_AUTO_CONFIGURATION_PREFIX)
        || autoConfigurationClass.startsWith(DATA_MONGODB_AUTO_CONFIGURATION_PREFIX)) {
      return PersistenceBackend.MONGODB;
    }
    if (autoConfigurationClass.startsWith(JDBC_AUTO_CONFIGURATION_PREFIX)
        || autoConfigurationClass.startsWith(JOOQ_AUTO_CONFIGURATION_PREFIX)
        || autoConfigurationClass.startsWith(FLYWAY_AUTO_CONFIGURATION_PREFIX)) {
      return PersistenceBackend.POSTGRESQL;
    }
    return null;
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
