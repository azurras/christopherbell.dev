package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
@EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
class MongoToPostgresqlMigrationAcceptanceTest {
  @Test
  void shadowsProductionShapedDocumentsForAll52KindsWithExactPgReadbackAndReadOnlyMongo()
      throws Exception {
    runAllKindsAcceptance(null);
  }

  private void runAllKindsAcceptance(@TempDir Path directory) throws Exception {
    var mongoUri = System.getenv("MONGODB_MIGRATION_TEST_URI");
    assertThat(mongoUri).matches("mongodb://127\\.0\\.0\\.1:(?!27017)[0-9]+/test");
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var mongo = MongoClients.create(mongoUri)) {
      var catalog = loadCatalog();
      var inserted = new ArrayList<Document>();
      for (var kind : catalog.kinds()) {
        var id = switch (kind.sourceKind()) {
          case "preference" -> "task6-all52-account";
          case "vin_decode_cache" -> "JM1TEST0000000000";
          case "zip_coordinate" -> "78701";
          default -> "task6-all52-" + kind.sourceKind();
        };
        var envelopeId = new Document("kind", kind.sourceKind()).append("legacyId", id);
        var collection = mongo.getDatabase("test").getCollection(kind.sourceCollection());
        collection.deleteMany(new Document("_kind", kind.sourceKind()));
        collection.insertOne(new Document("_id", envelopeId)
            .append("_kind", kind.sourceKind())
            .append("schemaVersion", kind.sourceSchemaVersion())
            .append("payload", bsonDocument(
                relationallyConsistentPayload(kind, catalog))));
        inserted.add(new Document("collection", kind.sourceCollection()).append("_id", envelopeId));
      }
      var accountKind = catalog.kinds().stream()
          .filter(kind -> kind.sourceKind().equals("account")).findFirst().orElseThrow();
      var peerId = new Document("kind", "account").append(
          "legacyId", "task6-all52-account-peer");
      mongo.getDatabase("test").getCollection(accountKind.sourceCollection()).insertOne(
          new Document("_id", peerId).append("_kind", "account").append("schemaVersion", 1)
              .append("payload", new Document("email", "peer@example.test")
                  .append("role", "USER").append("status", "ACTIVE")
                  .append("username", "task6-peer")));
      inserted.add(new Document("collection", accountKind.sourceCollection()).append("_id", peerId));
      try {
        var mongoBefore = inserted.stream().map(item -> mongo.getDatabase("test")
          .getCollection(item.getString("collection"))
          .find(new Document("_id", item.get("_id"))).first().toJson()).toList();
        var registry = MigrationTransformerRegistry.from(catalog);
        var dataSource = dataSource(database);
        var target = new JdbcMigrationTargetStore(
            dataSource, new JdbcRelationalRowPublisher(), catalog);
        var source = new MongoMigrationSourceReader(mongo);
        var preflight = new MigrationPreflight(new DirectMigrationIdentityProbe(dataSource));
        var engine = new KindMigrationEngine(source, target, registry::require);
        var runner = new PostgresqlMigrationRunner(
          preflight,
          catalog,
          engine,
          new MigrationReconciler(target),
          target,
          expected -> directory == null ? expected : FinalizeEvidenceLoader.loadForTest(
              directory, directory.resolve("finalize.properties"),
              directory.resolve("authority.key")),
          (context, evidence) -> MongoFinalizationFreezeGuard.acquire(mongo));
        var request = request(database, mongoUri);

        var first = runner.run(request);
        var second = directory == null ? runner.run(request) : first;

        assertThat(first.kinds()).hasSize(52).allSatisfy(status -> {
          assertThat(status.checkpoint().complete()).isTrue();
          assertThat(status.checkpoint().sourceCount())
              .isEqualTo(status.sourceKind().equals("account") ? 2 : 1);
          assertThat(status.published()).isFalse();
        });
        assertThat(second.statusDigest()).isEqualTo(first.statusDigest());
        assertThat(sourceCounts(database)).containsExactly(53L, 52L);
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
        assertThat(rootCounts(database, catalog)).containsOnly(1L, 2L);
        assertThat(accountBinary(database)).containsExactly(new byte[12], new byte[16]);
        if (directory != null) {
          var context = preflight.validate(request);
          var snapshots = new ArrayList<MigrationSourceSnapshot>();
          for (var kind : catalog.kinds().stream()
              .sorted(java.util.Comparator.comparingInt(
                  PostgresqlMigrationCatalog.Kind::loadOrder)).toList()) {
            snapshots.add(engine.requireSourceSnapshot(
                context, kind, target.checkpoint(context, kind)));
          }
          var lockToken = UUID.fromString("00000000-0000-0000-0000-000000000607");
          var evidence = authenticatedEvidence(
              directory, database, mongoUri, MigrationSourceSnapshot.runDigest(snapshots), lockToken);
          var finalizeRequest = request(database, mongoUri, lockToken, evidence);
          var published = runner.run(finalizeRequest);
          var replayed = runner.run(finalizeRequest);
          assertThat(published.kinds()).hasSize(52).allSatisfy(status -> {
            assertThat(status.checkpoint().sourceCount())
                .isEqualTo(status.sourceKind().equals("account") ? 2 : 1);
            assertThat(status.published()).isTrue();
            assertThat(status.publishedCount())
                .isEqualTo(status.sourceKind().equals("account") ? 2 : 1);
          });
          assertThat(replayed.statusDigest()).isEqualTo(published.statusDigest());
          assertThat(rootCounts(database, catalog)).containsOnly(1L, 2L);
          assertThat(accountBinary(database)).containsExactly(new byte[12], new byte[16]);
        }
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

  @Test
  void authenticatedFinalizePublishesProductionOwnersForAll52Kinds(@TempDir Path directory)
      throws Exception {
    runAllKindsAcceptance(directory);
  }

  private static Document bsonDocument(Map<String, Object> values) {
    var result = new Document();
    values.forEach((key, value) -> result.put(key, bson(value)));
    return result;
  }

  private static LinkedHashMap<String, Object> relationallyConsistentPayload(
      PostgresqlMigrationCatalog.Kind kind, PostgresqlMigrationCatalog catalog) {
    var ids = catalog.kinds().stream().collect(java.util.stream.Collectors.toMap(
        PostgresqlMigrationCatalog.Kind::sourceKind,
        candidate -> "task6-all52-" + candidate.sourceKind()));
    var placeholders = Map.of(
        "account", ids.get("account"),
        "message", ids.get("message"),
        "post", ids.get("post"),
        "restaurant", ids.get("restaurant"),
        "track", ids.get("music_track"));
    var result = MigrationTransformerAllKindsTest.representativePayload(kind);
    result.replaceAll((field, value) -> replaceReferences(value, placeholders));
    var kinds = catalog.kinds().stream().collect(java.util.stream.Collectors.toMap(
        PostgresqlMigrationCatalog.Kind::sourceKind, candidate -> candidate));
    for (var mappingEntry : kind.fieldMappings().entrySet()) {
      var mapping = mappingEntry.getValue();
      for (var dependency : kind.dependsOnKinds()) {
        var dependencyKind = kinds.get(dependency);
        var targetKey = dependencyKind.keyMapping().targetColumn();
        var dependencyKey = targetKey.substring(targetKey.indexOf('.') + 1);
        if (mapping.targets().stream().map(target -> target.substring(target.indexOf('.') + 1))
            .noneMatch(target -> target.endsWith(dependencyKey))) {
          continue;
        }
        switch (mapping.conversion()) {
          case "string", "uuid-string", "enum-name" ->
              result.put(mappingEntry.getKey(), ids.get(dependency));
          case "string-list-child", "string-set-child" ->
              result.put(mappingEntry.getKey(), java.util.List.of(ids.get(dependency)));
          default -> {
            // Complex production owners were replaced recursively above.
          }
        }
      }
    }
    switch (kind.sourceKind()) {
      case "account" -> result.put("permissions", java.util.List.of("MUSIC_READ"));
      case "account_follow" -> result.put("followedAccountId", "task6-all52-account-peer");
      case "account_trust_relationship" ->
          result.put("targetAccountId", "task6-all52-account-peer");
      case "message" -> result.put("recipientAccountId", "task6-all52-account-peer");
      case "post_link_preview_cache" -> {
        result.put("status", "SUCCESS");
        result.remove("failureCategory");
      }
      case "federation_delivery_job" -> result.put("lastStatus", 200);
      case "audit_event" -> result.put("clientIp", "192.0.2.1");
      case "price_snapshot" -> result.put("currency", "USD");
      case "music_metadata_edit" -> result.put("backupSha256", "a".repeat(64));
      case "upload_session" -> {
        result.put("ownerId", ids.get("account"));
        result.put("expectedSha256", "a".repeat(64));
        result.put("chunkDigests", Map.of("a", "a".repeat(64), "b", "b".repeat(64)));
      }
      case "media_job", "mutation_recovery" -> result.put("ownerId", ids.get("account"));
      case "post" -> {
        result.put("rootId", ids.get("post"));
        result.remove("parentId");
        result.put("level", 0);
        result.put("topics", java.util.List.of(
            Map.of("canonical", "java", "display", "Java")));
        result.put("linkPreviews", java.util.List.of(Map.of(
            "url", "https://example.test",
            "domain", "example.test",
            "title", "Title",
            "description", "Description",
            "imageUrl", "https://example.test/image.png")));
      }
      case "session" -> {
        result.put("participantAccountIds", java.util.List.of(ids.get("account")));
        result.put("participantUsernamesByAccountId", Map.of(ids.get("account"), "owner"));
        result.put("restaurantIds", java.util.List.of(ids.get("restaurant")));
        result.put("votesByAccountId", Map.of(ids.get("account"), ids.get("restaurant")));
      }
      case "restaurant" -> {
        result.put("createdBy", ids.get("account"));
        result.put("lastModifiedBy", ids.get("account"));
      }
      case "zip_coordinate" -> {
        result.put("latitude", 30.2672d);
        result.put("longitude", -97.7431d);
      }
      case "vehicle" -> {
        result.put("createdBy", ids.get("account"));
        result.put("lastModifiedBy", ids.get("account"));
        result.put("year", 2019);
      }
      default -> {
      }
    }
    return result;
  }

  private static Object replaceReferences(Object value, Map<String, String> replacements) {
    if (value instanceof String text) {
      return replacements.getOrDefault(text, text);
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, nested) ->
          result.put(key.toString(), replaceReferences(nested, replacements)));
      return result;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream()
          .map(item -> replaceReferences(item, replacements))
          .distinct()
          .toList();
    }
    return value;
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

  private static MigrationRequest request(
      PostgresqlSchemaTestSupport.MigratedDatabase database,
      String mongoUri,
      UUID lockToken,
      FrozenSourceEvidence evidence) {
    return new MigrationRequest(
        PostgresqlMigrationCommand.FINALIZE, mongoUri, "test", database.jdbcConfiguration().url(),
        "test", "christopherbell_test", database.prefix(), "a".repeat(64),
        "task6-acceptance", lockToken, evidence, 2);
  }

  private static FrozenSourceEvidence authenticatedEvidence(
      Path directory,
      PostgresqlSchemaTestSupport.MigratedDatabase database,
      String mongoUri,
      String sourceDigest,
      UUID lockToken) throws Exception {
    protect(directory);
    var key = "task6-independent-acceptance-authority-key";
    var keyPath = directory.resolve("authority.key");
    Files.writeString(keyPath, key, StandardCharsets.UTF_8);
    protect(keyPath);
    var lockPath = directory.resolve("writer.lock").toAbsolutePath().normalize();
    var lockText = "lockToken=" + lockToken + "\nrelease=task6-acceptance\n"
        + "state=frozen\nleaseExpiresAt=2999-01-01T00:00:00Z\n";
    Files.writeString(lockPath, lockText, StandardCharsets.UTF_8);
    protect(lockPath);
    var unsigned = new FrozenSourceEvidence(
        "task6-acceptance", "a".repeat(64), "test", "test", sourceDigest,
        "b".repeat(64), lockToken, mongoUri, database.jdbcConfiguration().url(),
        "christopherbell_test", lockPath.toString(),
        CanonicalMigrationHasher.sha256(lockText), "0".repeat(64));
    var evidence = new FrozenSourceEvidence(
        unsigned.release(), unsigned.catalogDigest(), unsigned.sourceDatabase(),
        unsigned.targetDatabase(), unsigned.sourceDigest(), unsigned.backupDigest(),
        unsigned.lockToken(), unsigned.sourceUri(), unsigned.targetJdbcUrl(), unsigned.targetRole(),
        unsigned.writerLockPath(), unsigned.writerLockDigest(), unsigned.reconstructedDigest());
    var properties = new Properties();
    properties.setProperty("release", evidence.release());
    properties.setProperty("catalogDigest", evidence.catalogDigest());
    properties.setProperty("sourceDatabase", evidence.sourceDatabase());
    properties.setProperty("targetDatabase", evidence.targetDatabase());
    properties.setProperty("sourceDigest", evidence.sourceDigest());
    properties.setProperty("backupDigest", evidence.backupDigest());
    properties.setProperty("lockToken", evidence.lockToken().toString());
    properties.setProperty("sourceUri", evidence.sourceUri());
    properties.setProperty("targetJdbcUrl", evidence.targetJdbcUrl());
    properties.setProperty("targetRole", evidence.targetRole());
    properties.setProperty("writerLockPath", evidence.writerLockPath());
    properties.setProperty("writerLockDigest", evidence.writerLockDigest());
    properties.setProperty("evidenceDigest", evidence.evidenceDigest());
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    properties.setProperty("signature", HexFormat.of().formatHex(
        mac.doFinal(evidence.evidenceDigest().getBytes(StandardCharsets.US_ASCII))));
    var evidencePath = directory.resolve("finalize.properties");
    try (var output = Files.newOutputStream(evidencePath)) {
      properties.store(output, null);
    }
    protect(evidencePath);
    return FinalizeEvidenceLoader.loadForTest(directory, evidencePath, keyPath);
  }

  private static void protect(Path path) throws Exception {
    var posix = Files.getFileAttributeView(
        path, java.nio.file.attribute.PosixFileAttributeView.class);
    if (posix != null) {
      posix.setPermissions(java.util.Set.of(
          java.nio.file.attribute.PosixFilePermission.OWNER_READ,
          java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
      return;
    }
    var owner = Files.getOwner(path);
    var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
    acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(java.util.EnumSet.allOf(
            java.nio.file.attribute.AclEntryPermission.class))
        .build()));
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

  private static List<Long> rootCounts(
      PostgresqlSchemaTestSupport.MigratedDatabase database,
      PostgresqlMigrationCatalog catalog) throws java.sql.SQLException {
    try (var connection = database.connect(); var statement = connection.createStatement()) {
      var result = new ArrayList<Long>();
      for (var kind : catalog.kinds()) {
        try (var rows = statement.executeQuery("select count(*) from \"" + database.prefix()
            + kind.targetSchema() + "\".\"" + kind.targetTables().getFirst() + "\"")) {
          rows.next();
          result.add(rows.getLong(1));
        }
      }
      return result;
    }
  }

  private static List<byte[]> accountBinary(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws java.sql.SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery("select private_key_nonce, private_key_ciphertext from \""
             + database.prefix() + "identity\".account_federation_identity")) {
      assertThat(rows.next()).isTrue();
      return List.of(rows.getBytes(1), rows.getBytes(2));
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
