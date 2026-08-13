package dev.christopherbell.configuration.persistence.migration;

/** Safe failure raised before malformed migration-catalog data enters trusted code. */
public final class PostgresqlMigrationCatalogException extends IllegalArgumentException {
  PostgresqlMigrationCatalogException(String message, Throwable cause) {
    super(message, cause);
  }
}
