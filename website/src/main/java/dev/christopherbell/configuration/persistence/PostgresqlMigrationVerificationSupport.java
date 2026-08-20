package dev.christopherbell.configuration.persistence;

import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.TransactionContext;
import org.jooq.TransactionProvider;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

/** Narrow support for module-owned PostgreSQL adapter parity probes. */
@PostgresPersistenceSupport
public final class PostgresqlMigrationVerificationSupport {
  private static final List<String> SCHEMAS = List.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");

  private PostgresqlMigrationVerificationSupport() {}

  public static DSLContext database(Connection connection, String schema) {
    var logical = SCHEMAS.stream().filter(schema::endsWith).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unexpected PostgreSQL schema."));
    var prefix = schema.substring(0, schema.length() - logical.length());
    var mapping = new RenderMapping();
    SCHEMAS.forEach(name -> mapping.withSchemata(
        append(mapping.getSchemata(), new MappedSchema().withInput(name).withOutput(prefix + name))));
    var configuration = new DefaultConfiguration();
    configuration.set(connection);
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(new Settings().withRenderMapping(mapping));
    configuration.set(new SavepointTransactionProvider(connection));
    return DSL.using(configuration);
  }

  private static List<MappedSchema> append(List<MappedSchema> current, MappedSchema value) {
    var result = new java.util.ArrayList<>(current == null ? List.<MappedSchema>of() : current);
    result.add(value);
    return result;
  }

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

  private static final class SavepointTransactionProvider implements TransactionProvider {
    private final Connection connection;
    private final Deque<Savepoint> savepoints = new ArrayDeque<>();

    private SavepointTransactionProvider(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void begin(TransactionContext context) {
      try {
        savepoints.push(connection.setSavepoint());
      } catch (SQLException failure) {
        throw new DataAccessException("Could not begin verification transaction.", failure);
      }
    }

    @Override
    public void commit(TransactionContext context) {
      release("Could not commit verification transaction.");
    }

    @Override
    public void rollback(TransactionContext context) {
      var savepoint = requireSavepoint();
      try {
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
      } catch (SQLException failure) {
        throw new DataAccessException("Could not roll back verification transaction.", failure);
      }
    }

    private void release(String message) {
      try {
        connection.releaseSavepoint(requireSavepoint());
      } catch (SQLException failure) {
        throw new DataAccessException(message, failure);
      }
    }

    private Savepoint requireSavepoint() {
      if (savepoints.isEmpty()) {
        throw new IllegalStateException("Verification transaction is not active.");
      }
      return savepoints.pop();
    }
  }
}
