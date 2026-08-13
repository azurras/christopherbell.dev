package dev.christopherbell.configuration.persistence;

/** Redacted startup failure for a PostgreSQL database identity check. */
public final class PostgresqlDatabaseIdentityCheckException extends IllegalStateException {
  public PostgresqlDatabaseIdentityCheckException(PostgresqlDatabaseIdentityCheckCause cause) {
    super("PostgreSQL database identity check failed (" + cause.category() + ").", cause);
  }
}
