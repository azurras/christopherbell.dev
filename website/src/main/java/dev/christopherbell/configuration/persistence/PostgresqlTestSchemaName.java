package dev.christopherbell.configuration.persistence;

import java.util.UUID;

/** Generates a bounded, disposable PostgreSQL schema name for one test fixture owner. */
public record PostgresqlTestSchemaName(String value) {
  public static PostgresqlTestSchemaName create(String schemaPrefix) {
    if (schemaPrefix == null || schemaPrefix.isBlank()) {
      throw new IllegalArgumentException("PostgreSQL test schema prefix must not be blank.");
    }
    return new PostgresqlTestSchemaName(schemaPrefix + UUID.randomUUID().toString().replace('-', '_'));
  }
}
