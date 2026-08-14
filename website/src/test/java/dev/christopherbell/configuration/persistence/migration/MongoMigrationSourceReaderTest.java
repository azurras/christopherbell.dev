package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
class MongoMigrationSourceReaderTest {
  private com.mongodb.client.MongoClient client;

  @BeforeEach
  void connectToDisposableTestDatabaseOnly() {
    var uri = System.getenv("MONGODB_MIGRATION_TEST_URI");
    assertThat(uri).matches("mongodb://127\\.0\\.0\\.1:(?!27017)[0-9]+/test");
    client = MongoClients.create(uri);
    assertThat(client.getDatabase("test").runCommand(new Document("ping", 1)).getDouble("ok"))
        .isEqualTo(1.0);
  }

  @AfterEach
  void close() {
    if (client != null) {
      client.getDatabase("test").getCollection("application_runtime")
          .deleteMany(new Document("_kind", new Document("$in", List.of(
              "application_lease", "not_catalogued"))));
      client.close();
    }
  }

  @Test
  void pagesInLegacyIdOrderWithAnOpaqueCursorWithoutMutatingMongo() throws IOException {
    var collection = client.getDatabase("test").getCollection("application_runtime");
    collection.insertMany(List.of(envelope("lease-b", 2), envelope("lease-a", 1)));
    var before = collection.find(new Document("_kind", "application_lease"))
        .sort(new Document("_id.legacyId", 1)).map(Document::toJson).into(new java.util.ArrayList<>());
    var context = context();
    var kind = kind();
    var reader = new MongoMigrationSourceReader(client);

    var first = reader.readAfter(context, kind, null, 1);
    var second = reader.readAfter(context, kind, first.lastCursor(), 1);
    var empty = reader.readAfter(context, kind, second.lastCursor(), 1);

    assertThat(first.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly("lease-a");
    assertThat(second.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly("lease-b");
    assertThat(empty.isEmpty()).isTrue();
    assertThat(collection.find(new Document("_kind", "application_lease"))
        .sort(new Document("_id.legacyId", 1)).map(Document::toJson)
        .into(new java.util.ArrayList<>())).containsExactlyElementsOf(before);
  }

  @Test
  void rejectsUnknownEnvelopeFieldsWithoutReturningPayloadData() throws IOException {
    var malformed = envelope("lease-a", 1).append("unexpected", "secret-payload");
    client.getDatabase("test").getCollection("application_runtime").insertOne(malformed);

    assertThatThrownBy(() -> new MongoMigrationSourceReader(client)
        .readAfter(context(), kind(), null, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PostgreSQL migration Mongo source envelope is invalid.")
        .hasMessageNotContaining("secret-payload");
  }

  @Test
  void rejectsAnUndeclaredKindAnywhereInACatalogCollection() throws IOException {
    client.getDatabase("test").getCollection("application_runtime").insertOne(
        new Document("_id", new Document("kind", "not_catalogued").append("legacyId", "x"))
            .append("_kind", "not_catalogued")
            .append("schemaVersion", 1)
            .append("payload", new Document("secret", "must-not-leak")));
    var reader = new MongoMigrationSourceReader(client);

    assertThatThrownBy(() -> reader.requireOnlyCatalogKinds(context(), catalog()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PostgreSQL migration Mongo source envelope is invalid.")
        .hasMessageNotContaining("must-not-leak");
  }

  @Test
  void rejectsOriginalNonStringBsonIdentifierBeforeTextConversion() throws IOException {
    var malformed = envelope("lease-a", 1);
    malformed.put("_id", new Document("kind", "application_lease")
        .append("legacyId", new ObjectId("000000000000000000000006")));
    client.getDatabase("test").getCollection("application_runtime").insertOne(malformed);

    assertThatThrownBy(() -> new MongoMigrationSourceReader(client)
        .readAfter(context(), kind(), null, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PostgreSQL migration Mongo source envelope is invalid.");
  }

  private static Document envelope(String id, long fence) {
    return new Document("_id", new Document("kind", "application_lease").append("legacyId", id))
        .append("_kind", "application_lease")
        .append("schemaVersion", 1)
        .append("payload", new Document("ownerToken", "owner-" + id)
            .append("fenceToken", fence)
            .append("acquiredAt", java.util.Date.from(Instant.parse("2026-08-14T00:00:00Z")))
            .append("expiresAt", java.util.Date.from(Instant.parse("2026-08-15T00:00:00Z"))));
  }

  private static ValidatedMigrationContext context() {
    var request = new MigrationRequest(
        PostgresqlMigrationCommand.SHADOW,
        System.getenv("MONGODB_MIGRATION_TEST_URI"),
        "test",
        "jdbc:postgresql://127.0.0.1:55432/test",
        "test",
        "christopherbell_test",
        "cbtest_task6_",
        "a".repeat(64),
        "release-6",
        UUID.randomUUID(),
        null,
        1);
    return new ValidatedMigrationContext(
        request,
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"),
        false);
  }

  private static PostgresqlMigrationCatalog.Kind kind() throws IOException {
    return catalog().kinds().stream()
        .filter(candidate -> candidate.sourceKind().equals("application_lease"))
        .findFirst()
        .orElseThrow();
  }

  private static PostgresqlMigrationCatalog catalog() throws IOException {
    try (var input = MongoMigrationSourceReaderTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}
