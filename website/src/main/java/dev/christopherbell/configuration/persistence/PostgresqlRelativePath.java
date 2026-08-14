package dev.christopherbell.configuration.persistence;

/** Validates the canonical slash-separated relative paths persisted by PostgreSQL adapters. */
@PostgresPersistenceSupport
public final class PostgresqlRelativePath {
  private PostgresqlRelativePath() {}

  public static String require(String value, String field) {
    return require(value, field, false);
  }

  public static String requireRootAllowed(String value, String field) {
    return require(value, field, true);
  }

  private static String require(String value, String field, boolean rootAllowed) {
    if (value == null || (!rootAllowed && value.isBlank())) {
      throw new IllegalArgumentException(field + " is required.");
    }
    if (value.isEmpty() && rootAllowed) {
      return value;
    }
    if (value.isBlank() || value.startsWith("/") || value.endsWith("/")
        || value.contains("\\") || value.contains("//")) {
      throw new IllegalArgumentException(field + " must be a normalized relative path.");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException(field + " must be a normalized relative path.");
      }
    }
    return value;
  }
}
