package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresqlSchemaContractTest {

  @Test
  void twoLivePrefixesKeepFlywayHistoryAndDomainSchemasIsolated() throws Exception {
    try (var first = PostgresqlSchemaTestSupport.migrate();
         var second = PostgresqlSchemaTestSupport.migrate();
         var connection = first.connect()) {
      assertThat(first.migrationsExecuted()).isEqualTo(4);
      assertThat(second.migrationsExecuted()).isEqualTo(4);
      assertThat(ownedSchemas(connection, first.prefix()))
          .hasSize(PostgresqlSchemaTestSupport.DOMAINS.size());
      assertThat(ownedSchemas(connection, second.prefix()))
          .hasSize(PostgresqlSchemaTestSupport.DOMAINS.size());
      assertThat(task2HistoryTableCount(connection)).isEqualTo(2);
    }
  }

  @Test
  void emptyFlywayMigrationCreatesExactlyTheTenOwnedCatalogSchemasAndTables() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(database.migrationsExecuted()).isEqualTo(4);
      assertThat(ownedSchemas(connection, database.prefix()))
          .containsExactlyInAnyOrderElementsOf(PostgresqlSchemaTestSupport.DOMAINS.stream()
              .map(database.prefix()::concat)
              .toList());
      assertThat(canonicalTables(connection, database.prefix()))
          .containsExactlyInAnyOrderElementsOf(catalogTables());
      assertThat(missingCatalogTargets(connection, database.prefix())).isEmpty();
      assertThat(scalar(connection,
          "select count(*) from information_schema.columns "
              + "where left(table_schema, ?) = ? and data_type in ('json', 'jsonb')",
          database.prefix().length(), database.prefix())).isZero();
      assertThat(scalar(connection,
          "select count(*) from information_schema.columns "
              + "where left(table_schema, ?) = ? and table_name <> 'flyway_schema_history' "
              + "and data_type = 'timestamp without time zone'",
          database.prefix().length(), database.prefix())).isZero();
      assertThat(tablesWithoutPrimaryKeys(connection, database.prefix())).isEmpty();
    }
  }

  @Test
  void schemaEnforcesKeysDeleteRulesPrecisionAndCursorIndexes() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(constraintDeleteRule(connection, database.prefix() + "social",
          "post_author_fk")).isEqualTo("RESTRICT");
      assertThat(constraintDeleteRule(connection, database.prefix() + "social",
          "post_like_post_fk")).isEqualTo("CASCADE");
      assertThat(constraintDeleteRule(connection, database.prefix() + "music",
          "playlist_track_playlist_fk")).isEqualTo("CASCADE");
      assertThat(columnPrecision(connection, database.prefix() + "lunch", "restaurant",
          "latitude")).containsExactly(9, 6);
      assertThat(columnPrecision(connection, database.prefix() + "canes", "price_snapshot",
          "average_price")).containsExactly(12, 2);
      assertThat(indexNames(connection, database.prefix()))
          .contains(
              "post__post_account_created_id_desc",
              "post__post_created_id_desc",
              "message__message_conversation_created_id_desc",
              "federation_delivery_job__federation_delivery_due",
              "restaurant__restaurant_inventory_location_name",
              "media_job__media_cleanup_due",
              "upload_session__upload_maintenance_due");
    }
  }

  @Test
  void coordinatePlaylistAndLeaseContractsFailOrTransitionAtTheDatabaseBoundary() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThatThrownBy(() -> execute(connection,
          "insert into \"" + database.prefix() + "lunch\".restaurant "
              + "(restaurant_id, display_name, dedupe_key, search_city, search_state, latitude) "
              + "values ('invalid-coordinate', 'Invalid', 'invalid', 'city', 'state', 32.1)"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23514");

      execute(connection, "insert into \"" + database.prefix()
          + "identity\".account (account_id, email, normalized_email, role, status, username) "
          + "values ('account-1', 'account@example.com', 'account@example.com', "
          + "'USER', 'ACTIVE', 'account')");

      execute(connection, "insert into \"" + database.prefix()
          + "mobility\".vehicle (vehicle_id, vin) "
          + "values ('vehicle-1', 'JM1BN1L30K1234567')");
      assertThatThrownBy(() -> execute(connection, "insert into \"" + database.prefix()
          + "mobility\".vehicle (vehicle_id, vin) "
          + "values ('vehicle-2', 'JM1BN1L30K1234567')"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23505");

      execute(connection, "insert into \"" + database.prefix()
          + "music\".access_attempt "
          + "(access_attempt_id, principal_type, principal, reason, first_attempt_at, "
          + "last_attempt_at, attempt_count, expires_at) values "
          + "('large-count', 'ACCOUNT', 'account-1', 'RATE_LIMIT', "
          + "transaction_timestamp(), transaction_timestamp(), 2147483648, "
          + "transaction_timestamp() + interval '1 hour')");
      execute(connection, "insert into \"" + database.prefix()
          + "lunch\".lunch_session "
          + "(lunch_session_id, active_until, created_by_account_id, created_by_username, "
          + "created_on, delete_on, last_updated_on, restaurant_reset_count, revision) values "
          + "('large-reset-count', transaction_timestamp() + interval '1 hour', 'account-1', "
          + "'account', transaction_timestamp(), transaction_timestamp() + interval '2 hours', "
          + "transaction_timestamp(), 2147483648, 0)");
      assertThat(longScalar(connection, "select attempt_count from \"" + database.prefix()
          + "music\".access_attempt where access_attempt_id = 'large-count'"))
          .isEqualTo(2_147_483_648L);
      assertThat(longScalar(connection, "select restaurant_reset_count from \""
          + database.prefix() + "lunch\".lunch_session "
          + "where lunch_session_id = 'large-reset-count'"))
          .isEqualTo(2_147_483_648L);

      execute(connection, "insert into \"" + database.prefix()
          + "music\".track (track_id, relative_path, title, duration_seconds, index_status) "
          + "values ('track-1', 'track-1.mp3', 'Track 1', 120, 'READY')");
      execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist "
          + "(playlist_id, normalized_name, name, updated_by_account_id, updated_at) "
          + "values ('playlist-1', 'playlist', 'Playlist', 'account-1', transaction_timestamp())");
      execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist_track (playlist_id, ordinal, track_id) "
          + "values ('playlist-1', 0, 'track-1')");
      assertThatThrownBy(() -> execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist_track (playlist_id, ordinal, track_id) "
          + "values ('playlist-1', 1, 'track-1')"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23505");

      var leaseSchema = database.prefix() + "shared_folder";
      execute(connection, "insert into \"" + leaseSchema
          + "\".maintenance_lease (lease_name, owner_token, fence_token, acquired_at, expires_at) "
          + "values ('maintenance', 'old-owner', 1, transaction_timestamp() - interval '2 minutes', "
          + "transaction_timestamp() - interval '1 minute')");
      assertThat(executeUpdate(connection, "update \"" + leaseSchema
          + "\".maintenance_lease set owner_token = 'new-owner', fence_token = fence_token + 1, "
          + "acquired_at = transaction_timestamp(), expires_at = transaction_timestamp() + interval '1 minute' "
          + "where lease_name = 'maintenance' and expires_at <= transaction_timestamp()"))
          .isEqualTo(1);
      assertThat(executeUpdate(connection, "update \"" + leaseSchema
          + "\".maintenance_lease set expires_at = transaction_timestamp() + interval '2 minutes' "
          + "where lease_name = 'maintenance' and owner_token = 'new-owner' and fence_token = 2"))
          .isEqualTo(1);
      assertThat(executeUpdate(connection, "delete from \"" + leaseSchema
          + "\".maintenance_lease where lease_name = 'maintenance' "
          + "and owner_token = 'new-owner' and fence_token = 2"))
          .isEqualTo(1);
    }
  }

  private static Set<String> catalogTables() {
    try (InputStream input = PostgresqlSchemaContractTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      var catalog = new PostgresqlMigrationCatalogLoader().load(input);
      var tables = catalog.kinds().stream()
          .flatMap(kind -> kind.targetTables().stream()
              .map(table -> kind.targetSchema() + '.' + table))
          .collect(Collectors.toCollection(HashSet::new));
      tables.add("platform.persistence_migration_run");
      tables.add("platform.persistence_migration_source");
      return Set.copyOf(tables);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static Set<String> missingCatalogTargets(Connection connection, String prefix)
      throws SQLException {
    var catalogTargets = new HashSet<String>();
    try (InputStream input = PostgresqlSchemaContractTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      var catalog = new PostgresqlMigrationCatalogLoader().load(input);
      catalog.kinds().forEach(kind -> {
        catalogTargets.add(kind.targetSchema() + '.' + kind.keyMapping().targetColumn());
        kind.fieldMappings().values().forEach(mapping -> mapping.targets().forEach(
            target -> catalogTargets.add(kind.targetSchema() + '.' + target)));
      });
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }

    try (var statement = connection.prepareStatement("""
        select substring(table_schema from ?), table_name, column_name
        from information_schema.columns
        where left(table_schema, ?) = ?
        """)) {
      statement.setInt(1, prefix.length() + 1);
      statement.setInt(2, prefix.length());
      statement.setString(3, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          catalogTargets.remove(rows.getString(1) + '.' + rows.getString(2) + '.'
              + rows.getString(3));
        }
      }
    }
    return Set.copyOf(catalogTargets);
  }

  private static Set<String> ownedSchemas(Connection connection, String prefix) throws SQLException {
    var result = new HashSet<String>();
    try (var statement = connection.prepareStatement(
        "select schema_name from information_schema.schemata "
            + "where left(schema_name, ?) = ? order by schema_name")) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) result.add(rows.getString(1));
      }
    }
    return result;
  }

  private static int task2HistoryTableCount(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select count(*) from information_schema.tables
        where table_schema = 'public'
          and table_name ~ '^flyway_cbtest_[0-9a-f]{24}_history$'
        """);
         var rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static Set<String> canonicalTables(Connection connection, String prefix)
      throws SQLException {
    var result = new HashSet<String>();
    try (var statement = connection.prepareStatement(
        "select table_schema, table_name from information_schema.tables "
            + "where left(table_schema, ?) = ? and table_type = 'BASE TABLE'")) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (!rows.getString(2).equals("flyway_schema_history")) {
            result.add(rows.getString(1).substring(prefix.length()) + '.' + rows.getString(2));
          }
        }
      }
    }
    return result;
  }

  private static Set<String> tablesWithoutPrimaryKeys(Connection connection, String prefix)
      throws SQLException {
    var result = new HashSet<String>();
    try (var statement = connection.prepareStatement("""
        select t.table_schema, t.table_name
        from information_schema.tables t
        where left(t.table_schema, ?) = ? and t.table_type = 'BASE TABLE'
          and t.table_name <> 'flyway_schema_history'
          and not exists (
            select 1 from information_schema.table_constraints c
            where c.table_schema = t.table_schema and c.table_name = t.table_name
              and c.constraint_type = 'PRIMARY KEY')
        """)) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) result.add(rows.getString(1) + '.' + rows.getString(2));
      }
    }
    return result;
  }

  private static String constraintDeleteRule(
      Connection connection, String schema, String constraint) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select delete_rule from information_schema.referential_constraints
        where constraint_schema = ? and constraint_name = ?
        """)) {
      statement.setString(1, schema);
      statement.setString(2, constraint);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static int[] columnPrecision(
      Connection connection, String schema, String table, String column) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select numeric_precision, numeric_scale from information_schema.columns
        where table_schema = ? and table_name = ? and column_name = ?
        """)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return new int[] {rows.getInt(1), rows.getInt(2)};
      }
    }
  }

  private static Set<String> indexNames(Connection connection, String prefix) throws SQLException {
    var names = new HashSet<String>();
    try (var statement = connection.prepareStatement(
        "select indexname from pg_indexes where left(schemaname, ?) = ?")) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) names.add(rows.getString(1));
      }
    }
    return names;
  }

  private static int scalar(Connection connection, String sql, int length, String prefix)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setInt(1, length);
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static int executeUpdate(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      return statement.executeUpdate(sql);
    }
  }

  private static long longScalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }
}
