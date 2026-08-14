package dev.christopherbell.configuration.persistence.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Publishes catalog-owned staged rows into typed tables within the caller's transaction. */
public final class JdbcRelationalRowPublisher implements MigrationRowPublisher {
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

  @Override
  public void publish(
      Connection connection,
      String schemaPrefix,
      PostgresqlMigrationCatalog.Kind kind,
      List<StagedMigrationRow> rows) throws SQLException {
    var allowedTables = Set.copyOf(kind.targetTables());
    for (var row : rows) {
      if (!kind.targetSchema().equals(row.targetSchema())
          || !allowedTables.contains(row.targetTable())) {
        throw new SQLException("Staged target is outside its catalog kind.");
      }
      insert(connection, schemaPrefix, allowedTables, row);
    }
  }

  private static void insert(
      Connection connection,
      String schemaPrefix,
      Set<String> sameKindTables,
      StagedMigrationRow row) throws SQLException {
    var metadata = columns(
        connection, schemaPrefix + row.targetSchema(), row.targetTable(), sameKindTables);
    var values = new LinkedHashMap<>(row.values());
    values.keySet().forEach(JdbcRelationalRowPublisher::requireIdentifier);
    for (var column : metadata.values()) {
      if (!values.containsKey(column.name()) && !column.nullable() && !column.generated()) {
        if (column.name().equals("ordinal")) {
          values.put(column.name(), row.targetOrdinal());
        } else if (column.implicitSourceKey()) {
          values.put(column.name(), row.sourceId());
        }
      }
    }
    if (!metadata.keySet().containsAll(values.keySet())) {
      throw new SQLException("Staged row contains an unknown target column.");
    }
    var columns = new ArrayList<>(values.keySet());
    var sql = "insert into " + quoted(schemaPrefix + row.targetSchema()) + "."
        + quoted(row.targetTable()) + " ("
        + columns.stream().map(JdbcRelationalRowPublisher::quoted)
            .collect(java.util.stream.Collectors.joining(", "))
        + ") values (" + String.join(", ", java.util.Collections.nCopies(columns.size(), "?"))
        + ")";
    try (var statement = connection.prepareStatement(sql)) {
      for (var index = 0; index < columns.size(); index++) {
        bind(statement, index + 1, values.get(columns.get(index)), metadata.get(columns.get(index)));
      }
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Staged row was not published.");
      }
    }
  }

  private static Map<String, Column> columns(
      Connection connection,
      String schema,
      String table,
      Set<String> sameKindTables) throws SQLException {
    requireIdentifier(schema);
    requireIdentifier(table);
    var implicitSourceKeys = new java.util.HashSet<String>();
    try (var keys = connection.getMetaData().getImportedKeys(null, schema, table)) {
      while (keys.next()) {
        if (schema.equals(keys.getString("PKTABLE_SCHEM"))
            && sameKindTables.contains(keys.getString("PKTABLE_NAME"))) {
          implicitSourceKeys.add(keys.getString("FKCOLUMN_NAME"));
        }
      }
    }
    var result = new LinkedHashMap<String, Column>();
    try (var rows = connection.getMetaData().getColumns(null, schema, table, null)) {
      while (rows.next()) {
        var name = rows.getString("COLUMN_NAME");
        result.put(name, new Column(
            name,
            rows.getInt("DATA_TYPE"),
            rows.getString("TYPE_NAME"),
            rows.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls,
            rows.getString("COLUMN_DEF") != null || "YES".equals(rows.getString("IS_GENERATEDCOLUMN")),
            implicitSourceKeys.contains(name)));
      }
    }
    if (result.isEmpty()) {
      throw new SQLException("Catalog target table is absent.");
    }
    return result;
  }

  private static void bind(
      PreparedStatement statement, int index, Object value, Column column) throws SQLException {
    if (value == null) {
      statement.setNull(index, column.jdbcType());
    } else if (value instanceof Instant instant) {
      statement.setObject(index, instant.atOffset(ZoneOffset.UTC));
    } else if (value instanceof String text && "uuid".equals(column.typeName())) {
      statement.setObject(index, UUID.fromString(text));
    } else if (value instanceof byte[] bytes) {
      statement.setBytes(index, bytes);
    } else if (column.jdbcType() == Types.VARCHAR || column.jdbcType() == Types.CHAR
        || column.jdbcType() == Types.LONGVARCHAR) {
      statement.setString(index, value.toString());
    } else {
      statement.setObject(index, value);
    }
  }

  private static String quoted(String identifier) {
    requireIdentifier(identifier);
    return '"' + identifier + '"';
  }

  private static void requireIdentifier(String identifier) {
    if (identifier == null || !IDENTIFIER.matcher(identifier.toLowerCase(Locale.ROOT)).matches()
        || !identifier.equals(identifier.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("PostgreSQL migration target identifier is invalid.");
    }
  }

  private record Column(
      String name,
      int jdbcType,
      String typeName,
      boolean nullable,
      boolean generated,
      boolean implicitSourceKey) {}
}
