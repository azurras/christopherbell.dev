package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
@EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
class MongoToPostgresqlMigrationAcceptanceTest {
  @Test
  void migratesAll52KindsTwiceWithExactDigestAndReadOnlyMongoSource() throws Exception {
    var mongoUri = System.getenv("MONGODB_MIGRATION_TEST_URI");
    assertThat(mongoUri).matches("mongodb://127\\.0\\.0\\.1:(?!27017)[0-9]+/test");
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var mongo = MongoClients.create(mongoUri)) {
      var collection = mongo.getDatabase("test").getCollection("application_runtime");
      collection.deleteMany(new Document("_kind", "application_lease"));
      collection.insertOne(applicationLeaseEnvelope());
      var mongoBefore = collection.find(new Document("_kind", "application_lease"))
          .first().toJson();
      var catalog = loadCatalog();
      var registry = MigrationTransformerRegistry.from(catalog);
      var dataSource = dataSource(database);
      var target = new JdbcMigrationTargetStore(dataSource, new JdbcRelationalRowPublisher());
      var source = new MongoMigrationSourceReader(mongo);
      var runner = new PostgresqlMigrationRunner(
          new MigrationPreflight(new DirectMigrationIdentityProbe(dataSource)),
          catalog,
          new KindMigrationEngine(source, target, registry::require),
          new MigrationReconciler(target),
          target);
      var request = request(database, mongoUri);

      var first = runner.run(request);
      var second = runner.run(request);

      assertThat(first.kinds()).hasSize(52).allSatisfy(status -> {
        assertThat(status.checkpoint().complete()).isTrue();
        assertThat(status.published()).isTrue();
      });
      assertThat(second.statusDigest()).isEqualTo(first.statusDigest());
      assertThat(first.kinds().stream().mapToLong(MigrationKindStatus::publishedCount).sum())
          .isEqualTo(1);
      assertThat(applicationLease(database)).containsExactly(
          "task6-lease", "task6-owner", 7L,
          Instant.parse("2026-08-14T00:00:00.123Z"),
          Instant.parse("2026-08-15T00:00:00.123Z"));
      assertThat(collection.find(new Document("_kind", "application_lease")).first().toJson())
          .isEqualTo(mongoBefore);
      collection.deleteMany(new Document("_kind", "application_lease"));
    }
  }

  private static Document applicationLeaseEnvelope() {
    return new Document(
        "_id",
        new Document("kind", "application_lease").append("legacyId", "task6-lease"))
        .append("_kind", "application_lease")
        .append("schemaVersion", 1)
        .append("payload", new Document("ownerToken", "task6-owner")
            .append("fenceToken", 7L)
            .append("acquiredAt", java.util.Date.from(
                Instant.parse("2026-08-14T00:00:00.123456Z")))
            .append("expiresAt", java.util.Date.from(
                Instant.parse("2026-08-15T00:00:00.123456Z"))));
  }

  private static MigrationRequest request(
      PostgresqlSchemaTestSupport.MigratedDatabase database, String mongoUri) {
    return new MigrationRequest(
        PostgresqlMigrationCommand.SHADOW,
        mongoUri,
        "test",
        database.jdbcConfiguration().url(),
        "test",
        "christopherbell_test",
        database.prefix(),
        "a".repeat(64),
        "task6-acceptance",
        UUID.fromString("00000000-0000-0000-0000-000000000606"),
        null,
        2);
  }

  private static DriverManagerDataSource dataSource(
      PostgresqlSchemaTestSupport.MigratedDatabase database) {
    var jdbc = database.jdbcConfiguration();
    return new DriverManagerDataSource(jdbc.url(), jdbc.username(), jdbc.password());
  }

  private static java.util.List<Object> applicationLease(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws java.sql.SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select lease_name, owner_token, fence_token, acquired_at, expires_at "
                 + "from \"" + database.prefix() + "platform\".application_lease")) {
      rows.next();
      return java.util.List.of(
          rows.getString(1), rows.getString(2), rows.getLong(3),
          rows.getObject(4, java.time.OffsetDateTime.class).toInstant(),
          rows.getObject(5, java.time.OffsetDateTime.class).toInstant());
    }
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MongoToPostgresqlMigrationAcceptanceTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}
