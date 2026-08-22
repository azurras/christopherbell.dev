package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostgresqlMigrationSourceSnapshotCliTest {
  @Test
  @EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
  void snapshotsEveryCatalogKindWithoutWritingEitherDatabase() throws Exception {
    var mongoUri = System.getenv("MONGODB_MIGRATION_TEST_URI");
    var jdbcUrl = System.getenv("SPRING_DATASOURCE_URL");
    assertThat(mongoUri).endsWith("/test");
    assertThat(jdbcUrl).endsWith("/test");
    var environment = new HashMap<String, String>();
    environment.put("POSTGRESQL_MIGRATION_SOURCE_URI", mongoUri);
    environment.put("POSTGRESQL_MIGRATION_SOURCE_DATABASE", "test");
    environment.put("POSTGRESQL_MIGRATION_TARGET_JDBC_URL", jdbcUrl);
    environment.put("POSTGRESQL_MIGRATION_TARGET_DATABASE", "test");
    environment.put("POSTGRESQL_MIGRATION_TARGET_ROLE", "christopherbell_test");
    environment.put("POSTGRESQL_MIGRATION_SCHEMA_PREFIX", "cbtest_task9_snapshot_");
    environment.put("POSTGRESQL_MIGRATION_RELEASE", "task9-snapshot");
    environment.put("POSTGRESQL_MIGRATION_BRIDGE_RELEASE", "1");
    environment.put("POSTGRESQL_MIGRATION_LOCK_TOKEN",
        "11111111-2222-4333-8444-555555555555");
    environment.put("POSTGRESQL_MIGRATION_BATCH_SIZE", "500");
    environment.put("POSTGRESQL_MIGRATION_TARGET_USERNAME", "christopherbell_test");
    environment.put("POSTGRESQL_MIGRATION_TARGET_PASSWORD",
        System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "unused"));
    clearMongoTestFixtures(mongoUri);
    var mongoBefore = mongoShape(mongoUri);
    Map<String, Long> postgresBefore;
    try (var connection = new DriverManagerDataSource(
        jdbcUrl, "christopherbell_test", environment.get("POSTGRESQL_MIGRATION_TARGET_PASSWORD"))
        .getConnection();
         var statement = connection.createStatement();
         var rows = statement.executeQuery("select current_database(), current_user")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("test");
      assertThat(rows.getString(2)).isEqualTo("christopherbell_test");
      postgresBefore = postgresShape(statement);
    }
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    assertThat(PostgresqlMigrationSourceSnapshotCli.snapshot(environment))
        .matches("catalogDigest=[0-9a-f]{64} sourceDigest=[0-9a-f]{64} kinds=52");

    var exit = PostgresqlMigrationSourceSnapshotCli.execute(
        new String[] {"snapshot"}, environment,
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertThat(exit).isZero();
    assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(output.toString(StandardCharsets.UTF_8).trim())
        .matches("catalogDigest=[0-9a-f]{64} sourceDigest=[0-9a-f]{64} kinds=52");
    assertThat(mongoShape(mongoUri)).isEqualTo(mongoBefore);
    try (var connection = new DriverManagerDataSource(
        jdbcUrl, "christopherbell_test", environment.get("POSTGRESQL_MIGRATION_TARGET_PASSWORD"))
        .getConnection();
         var statement = connection.createStatement()) {
      assertThat(postgresShape(statement)).isEqualTo(postgresBefore);
      assertThat(postgresBefore.keySet())
          .noneMatch(name -> name.startsWith("cbtest_task9_snapshot_"));
    }
  }

  @Test
  void powershellAuthorityFixtureMatchesJavaCanonicalHashes() {
    var values = new LinkedHashMap<String, Object>();
    values.put("release", "a".repeat(40));
    values.put("catalogDigest", "b".repeat(64));
    values.put("sourceDatabase", "christopherbell");
    values.put("targetDatabase", "christopherbell");
    values.put("sourceDigest", "c".repeat(64));
    values.put("backupDigest", "d".repeat(64));
    values.put("lockToken", "11111111-2222-4333-8444-555555555555");
    values.put("sourceUri", "mongodb://127.0.0.1:27017/christopherbell");
    values.put("targetJdbcUrl", "jdbc:postgresql://127.0.0.1:5432/christopherbell");
    values.put("targetRole", "christopherbell_bridge");
    values.put("writerLockPath",
        "C:\\ProgramData\\christopherbell.dev\\postgresql-migration-authority\\writer.lock");
    values.put("writerLockDigest", "e".repeat(64));
    assertThat(CanonicalMigrationHasher.sha256(values))
        .isEqualTo("6c98a5d8435a6cb53f29b4a3c70c6b55cd8ad7822cd226edd0c757eb75045d1f");

    var writerLock = "lockToken=11111111-2222-4333-8444-555555555555\n"
        + "release=" + "a".repeat(40) + "\nstate=frozen\n"
        + "leaseExpiresAt=2026-08-21T18:30:00.0000000+00:00";
    assertThat(CanonicalMigrationHasher.sha256(writerLock))
        .isEqualTo("608e59ad770228d7293259c3a8d73b7a281bba434b72d4bd7fe7f02ec62ec52b");
  }

  @Test
  void invalidRequestsFailClosedWithoutEchoingConfiguration() {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    var secret = "task9-sensitive-bridge-password";

    var exit = PostgresqlMigrationSourceSnapshotCli.execute(
        new String[] {"unexpected"},
        Map.of("POSTGRESQL_MIGRATION_TARGET_PASSWORD", secret),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertThat(exit).isEqualTo(2);
    assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(error.toString(StandardCharsets.UTF_8).lines())
        .containsExactly("PostgreSQL migration source snapshot command failed.");
    assertThat(error.toString(StandardCharsets.UTF_8))
        .doesNotContain(secret);
  }

  private static Map<String, Long> mongoShape(String uri) {
    var shape = new TreeMap<String, Long>();
    try (var mongo = MongoClients.create(uri)) {
      var database = mongo.getDatabase("test");
      for (var collection : database.listCollectionNames()) {
        shape.put(collection, database.getCollection(collection).countDocuments());
      }
    }
    return shape;
  }

  private static void clearMongoTestFixtures(String uri) {
    try (var mongo = MongoClients.create(uri)) {
      var database = mongo.getDatabase("test");
      for (var collection : database.listCollectionNames()) {
        database.getCollection(collection).deleteMany(new Document());
      }
    }
  }

  private static Map<String, Long> postgresShape(java.sql.Statement statement)
      throws java.sql.SQLException {
    var shape = new TreeMap<String, Long>();
    try (var rows = statement.executeQuery(
        "select nspname, count(*) over () from pg_namespace order by nspname")) {
      while (rows.next()) {
        shape.put(rows.getString(1), rows.getLong(2));
      }
    }
    return shape;
  }
}
