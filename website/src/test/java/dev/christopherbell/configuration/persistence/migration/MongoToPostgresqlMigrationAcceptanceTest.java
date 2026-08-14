package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
@EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
class MongoToPostgresqlMigrationAcceptanceTest {
  @Test
  void shadowsProductionShapedDocumentsForAll52KindsWithExactPgReadbackAndReadOnlyMongo()
      throws Exception {
    var mongoUri = System.getenv("MONGODB_MIGRATION_TEST_URI");
    assertThat(mongoUri).matches("mongodb://127\\.0\\.0\\.1:(?!27017)[0-9]+/test");
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var mongo = MongoClients.create(mongoUri)) {
      var catalog = loadCatalog();
      var inserted = new ArrayList<Document>();
      for (var kind : catalog.kinds()) {
        var id = "task6-all52-" + kind.sourceKind();
        var envelopeId = new Document("kind", kind.sourceKind()).append("legacyId", id);
        var collection = mongo.getDatabase("test").getCollection(kind.sourceCollection());
        collection.deleteMany(new Document("_kind", kind.sourceKind()));
        collection.insertOne(new Document("_id", envelopeId)
            .append("_kind", kind.sourceKind())
            .append("schemaVersion", kind.sourceSchemaVersion())
            .append("payload", bsonDocument(
                MigrationTransformerAllKindsTest.representativePayload(kind))));
        inserted.add(new Document("collection", kind.sourceCollection()).append("_id", envelopeId));
      }
      try {
        var mongoBefore = inserted.stream().map(item -> mongo.getDatabase("test")
          .getCollection(item.getString("collection"))
          .find(new Document("_id", item.get("_id"))).first().toJson()).toList();
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
          assertThat(status.checkpoint().sourceCount()).isEqualTo(1);
          assertThat(status.published()).isFalse();
        });
        assertThat(second.statusDigest()).isEqualTo(first.statusDigest());
        assertThat(sourceCounts(database)).containsExactly(52L, 52L);
        var stagedTables = stagedTables(database);
        assertThat(stagedTables).hasSize(52);
        assertThat(catalog.kinds()).allSatisfy(kind -> {
          assertThat(stagedTables).containsKey(kind.sourceKind());
          assertThat(stagedTables.get(kind.sourceKind()))
              .contains(kind.targetTables().getFirst())
              .isSubsetOf(kind.targetTables().toArray(String[]::new));
        });
        assertThat(stagedAccountRows(database)).contains(
            "account_federation_identity", "account_moderation_audit_value");
        var mongoAfter = inserted.stream().map(item -> mongo.getDatabase("test")
          .getCollection(item.getString("collection"))
          .find(new Document("_id", item.get("_id"))).first().toJson()).toList();
        assertThat(mongoAfter).isEqualTo(mongoBefore);
      } finally {
        for (var item : inserted) {
          mongo.getDatabase("test").getCollection(item.getString("collection"))
              .deleteOne(new Document("_id", item.get("_id")));
        }
      }
    }
  }

  private static Document bsonDocument(Map<String, Object> values) {
    var result = new Document();
    values.forEach((key, value) -> result.put(key, bson(value)));
    return result;
  }

  private static Object bson(Object value) {
    if (value instanceof Map<?, ?> map) {
      var normalized = new LinkedHashMap<String, Object>();
      map.forEach((key, nested) -> normalized.put(key.toString(), bson(nested)));
      return new Document(normalized);
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(MongoToPostgresqlMigrationAcceptanceTest::bson).toList();
    }
    if (value instanceof Instant instant) {
      return java.util.Date.from(instant);
    }
    if (value instanceof LocalDate date) {
      return date.toString();
    }
    if (value instanceof BigDecimal decimal) {
      return new Decimal128(decimal);
    }
    if (value instanceof UUID uuid) {
      return uuid.toString();
    }
    return value;
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

  private static java.util.List<Long> sourceCounts(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws java.sql.SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
               "select count(*), count(distinct source_kind) from \"" + database.prefix()
                   + "platform\".persistence_migration_source")) {
      rows.next();
      return java.util.List.of(rows.getLong(1), rows.getLong(2));
    }
  }

  private static java.util.List<String> stagedAccountRows(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws java.sql.SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select distinct target_table from \"" + database.prefix()
                 + "platform\".persistence_migration_staged_row where source_kind='account' "
                 + "order by target_table")) {
      var result = new ArrayList<String>();
      while (rows.next()) {
        result.add(rows.getString(1));
      }
      return result;
    }
  }

  private static Map<String, java.util.Set<String>> stagedTables(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws java.sql.SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select source_kind, target_table from \"" + database.prefix()
                 + "platform\".persistence_migration_staged_row "
                 + "group by source_kind, target_table order by source_kind, target_table")) {
      var result = new LinkedHashMap<String, java.util.Set<String>>();
      while (rows.next()) {
        result.computeIfAbsent(rows.getString(1), ignored -> new java.util.LinkedHashSet<>())
            .add(rows.getString(2));
      }
      return result;
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
