package dev.christopherbell.configuration.persistence;

/** Safe SQLSTATE-only cause retained without leaking database identifiers or values. */
public final class PostgresqlConstraintViolationCause extends RuntimeException {
  private final String sqlState;

  public PostgresqlConstraintViolationCause(String sqlState) {
    super("PostgreSQL constraint category " + safeState(sqlState) + '.');
    this.sqlState = safeState(sqlState);
  }

  public String sqlState() {
    return sqlState;
  }

  private static String safeState(String value) {
    return value != null && value.matches("[0-9A-Z]{5}") ? value : "UNKNOWN";
  }
}
