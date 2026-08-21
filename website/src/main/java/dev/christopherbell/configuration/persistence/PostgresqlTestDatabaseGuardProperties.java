package dev.christopherbell.configuration.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Immutable test-only database constraints that prevent accidental non-test writes. */
@ConfigurationProperties("app.database-guard")
public record PostgresqlTestDatabaseGuardProperties(String requiredDatabase, String schemaPrefix) {
  public PostgresqlTestDatabaseGuardProperties {
    if (requiredDatabase == null || requiredDatabase.isBlank()) {
      throw new IllegalArgumentException("app.database-guard.required-database must not be blank.");
    }
    if (!requiredDatabase.equals("test")) {
      throw new IllegalArgumentException("app.database-guard.required-database must be test.");
    }
    if (schemaPrefix == null || schemaPrefix.isBlank()) {
      throw new IllegalArgumentException("app.database-guard.schema-prefix must not be blank.");
    }
    if (!schemaPrefix.equals("cbtest_")) {
      throw new IllegalArgumentException("app.database-guard.schema-prefix must be cbtest_.");
    }
  }
}
