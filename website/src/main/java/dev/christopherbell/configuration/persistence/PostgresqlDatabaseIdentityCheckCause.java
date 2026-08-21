package dev.christopherbell.configuration.persistence;

/** Safe category retained as the cause of a redacted database identity failure. */
public final class PostgresqlDatabaseIdentityCheckCause extends RuntimeException {
  private final String category;

  public PostgresqlDatabaseIdentityCheckCause(String category) {
    super("PostgreSQL database identity check " + category + ".");
    this.category = category;
  }

  public String category() {
    return category;
  }
}
