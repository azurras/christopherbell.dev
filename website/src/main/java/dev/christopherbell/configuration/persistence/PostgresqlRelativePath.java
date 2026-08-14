package dev.christopherbell.configuration.persistence;

import dev.christopherbell.libs.path.WindowsSafeRelativePath;

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
    try {
      WindowsSafeRelativePath.segments(value, rootAllowed);
      return value;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a Windows-safe relative path.",
          exception);
    }
  }
}
