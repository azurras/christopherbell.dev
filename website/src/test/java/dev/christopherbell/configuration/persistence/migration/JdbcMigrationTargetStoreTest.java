package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class JdbcMigrationTargetStoreTest {
  @Test
  void typedPublisherSuppliesTheImplicitSameKindChildForeignKey() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var kind = loadCatalog().kinds().stream()
          .filter(candidate -> candidate.sourceKind().equals("vin_decode_cache"))
          .findFirst().orElseThrow();
      var rows = List.of(
          new StagedMigrationRow(
              "JM1BN1L30K1234567", "mobility", "vin_decode_cache", 0,
              Map.of(
                  "vin", "JM1BN1L30K1234567",
                  "response_present", true,
                  "raw_decoded_values_present", true)),
          new StagedMigrationRow(
              "JM1BN1L30K1234567", "mobility", "vin_decode_raw_value", 0,
              Map.of("field_name", "Make", "field_value", "Mazda")));

      new JdbcRelationalRowPublisher().publish(connection, database.prefix(), kind, rows);

      try (var statement = connection.createStatement();
           var result = statement.executeQuery(
               "select vin, field_name, field_value from \"" + database.prefix()
                   + "mobility\".vin_decode_raw_value")) {
        assertThat(result.next()).isTrue();
        assertThat(List.of(result.getString(1), result.getString(2), result.getString(3)))
            .containsExactly("JM1BN1L30K1234567", "Make", "Mazda");
      }
    }
  }

  @Test
  void failedBatchAndFailedPublicationRollbackThenResumeToExactTypedRows() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var context = context(database);
      var kind = applicationLeaseKind();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var first = transformer.transform(source("lease-a", 1));
      var second = transformer.transform(source("lease-b", 2));
      var dataSource = dataSource(database);
      var target = new JdbcMigrationTargetStore(dataSource, new JdbcRelationalRowPublisher());
      var initial = target.checkpoint(context, kind);

      assertThatThrownBy(() -> target.commitBatch(
          context, kind, initial, List.of(first, first), "duplicate"))
          .isInstanceOf(MigrationStorageException.class)
          .hasMessage("PostgreSQL migration target operation failed.");
      assertThat(target.checkpoint(context, kind)).isEqualTo(initial);
      assertThat(count(database, "persistence_migration_source")).isZero();

      var staged = target.commitBatch(context, kind, initial, List.of(first, second), "lease-b");
      var complete = target.completeStaging(context, kind, staged);
      var reconciliation = target.reconcile(context, kind);
      assertThat(reconciliation.equivalent()).isTrue();
      assertThat(complete.sourceCount()).isEqualTo(2);

      var failingTarget = new JdbcMigrationTargetStore(dataSource, (connection, prefix, item, rows) -> {
        new JdbcRelationalRowPublisher().publish(connection, prefix, item, rows);
        throw new SQLException("injected after typed inserts");
      });
      assertThatThrownBy(() -> failingTarget.publish(context, kind, reconciliation))
          .isInstanceOf(MigrationStorageException.class);
      assertThat(count(database, "application_lease")).isZero();
      assertThat(target.statuses(context).getFirst().published()).isFalse();

      target.publish(context, kind, reconciliation);
      target.publish(context, kind, reconciliation);

      assertThat(count(database, "application_lease")).isEqualTo(2);
      assertThat(target.statuses(context).getFirst().published()).isTrue();
      assertThat(target.statuses(context).getFirst().publishedCount()).isEqualTo(2);
    }
  }

  private static MigrationSourceDocument source(String id, long fence) {
    return new MigrationSourceDocument(
        "application_lease",
        1,
        id,
        Map.of(
            "ownerToken", "owner-" + id,
            "fenceToken", fence,
            "acquiredAt", Instant.parse("2026-08-14T00:00:00.123456Z"),
            "expiresAt", Instant.parse("2026-08-15T00:00:00.123456Z")));
  }

  private static ValidatedMigrationContext context(
      PostgresqlSchemaTestSupport.MigratedDatabase database) {
    var request = new MigrationRequest(
        PostgresqlMigrationCommand.SHADOW,
        "mongodb://127.0.0.1:57018/test",
        "test",
        database.jdbcConfiguration().url(),
        "test",
        "christopherbell_test",
        database.prefix(),
        "a".repeat(64),
        "release-6",
        UUID.randomUUID(),
        null,
        2);
    return new ValidatedMigrationContext(
        request,
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"),
        false);
  }

  private static DriverManagerDataSource dataSource(
      PostgresqlSchemaTestSupport.MigratedDatabase database) {
    var jdbc = database.jdbcConfiguration();
    return new DriverManagerDataSource(jdbc.url(), jdbc.username(), jdbc.password());
  }

  private static long count(
      PostgresqlSchemaTestSupport.MigratedDatabase database, String table) throws SQLException {
    var schema = table.equals("application_lease") ? "platform" : "platform";
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select count(*) from \"" + database.prefix() + schema + "\".\"" + table + "\"")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static PostgresqlMigrationCatalog.Kind applicationLeaseKind() throws IOException {
    return loadCatalog().kinds().stream()
        .filter(kind -> kind.sourceKind().equals("application_lease"))
        .findFirst()
        .orElseThrow();
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = JdbcMigrationTargetStoreTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}
