package dev.christopherbell.configuration.persistence;

import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Narrow support for module-owned PostgreSQL adapter parity probes. */
@PostgresPersistenceSupport
public final class PostgresqlMigrationVerificationSupport {
  private PostgresqlMigrationVerificationSupport() {}

  public static boolean verifyOptionalLookup(
      List<Map<String, Object>> rows,
      String key,
      Function<String, Optional<?>> lookup) {
    var ids = rows.stream().map(row -> text(row.get(key))).filter(java.util.Objects::nonNull)
        .distinct().toList();
    return ids.stream().allMatch(id -> lookup.apply(id).isPresent())
        && lookup.apply("migration-verifier-missing-id").isEmpty();
  }

  public static boolean verifyExistence(
      List<Map<String, Object>> rows,
      String key,
      Function<String, Boolean> exists) {
    var ids = rows.stream().map(row -> text(row.get(key))).filter(java.util.Objects::nonNull)
        .distinct().toList();
    return ids.stream().allMatch(exists::apply)
        && !exists.apply("migration-verifier-missing-id");
  }

  public static boolean rollback(Connection connection, CheckedBoolean probe) throws SQLException {
    if (connection.getAutoCommit()) {
      return false;
    }
    var savepoint = connection.setSavepoint("verify_adapter_operation");
    try {
      return probe.getAsBoolean();
    } finally {
      connection.rollback(savepoint);
      connection.releaseSavepoint(savepoint);
    }
  }

  public static String text(Object value) {
    return value == null ? null : value.toString();
  }

  public static Instant instant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime offset) {
      return offset.toInstant();
    }
    return null;
  }

  @FunctionalInterface
  public interface CheckedBoolean {
    boolean getAsBoolean() throws SQLException;
  }

}
