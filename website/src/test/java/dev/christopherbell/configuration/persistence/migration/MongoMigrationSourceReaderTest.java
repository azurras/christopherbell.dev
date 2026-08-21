package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
  void excludesOnlyValidatedSpringTypeMetadataFromTheDomainPayload() throws IOException {
    var envelope = envelope("lease-metadata", 7);
    envelope.get("payload", Document.class).append(
        "_class", "dev.christopherbell.libs.mongo.lease.MongoApplicationLease");
    client.getDatabase("test").getCollection("application_runtime").insertOne(envelope);

    var batch = new MongoMigrationSourceReader(client)
        .readAfter(context(), kind(), null, 1);

    assertThat(batch.documents()).singleElement().satisfies(document -> {
      assertThat(document.payload()).doesNotContainKey("_class");
      assertThat(document.payload()).containsEntry("fenceToken", 7L);
    });
  }

  @Test
  void engineCrashResumeAdvancesAcrossLexicallyReversedOpaqueCursors() throws IOException {
    var collection = client.getDatabase("test").getCollection("application_runtime");
    collection.insertMany(List.of(envelope("lease-3", 1), envelope("lease-4", 2)));
    var catalog = catalog();
    var target = new CrashResumeTarget(2);
    var transformers = MigrationTransformerRegistry.from(catalog);
    var engine = new KindMigrationEngine(
        new MongoMigrationSourceReader(client), target, transformers::require);

    assertThatThrownBy(() -> engine.stageAndCheckpoint(context(), kind(catalog)))
        .isInstanceOf(InjectedFailure.class);
    assertThat(target.checkpoint.sourceCount()).isOne();
    assertThat(target.staged).containsExactly("lease-3");

    target.failCommit = -1;
    engine.stageAndCheckpoint(context(), kind(catalog));

    assertThat(target.checkpoint.complete()).isTrue();
    assertThat(target.checkpoint.sourceCount()).isEqualTo(2);
    assertThat(target.staged).containsExactly("lease-3", "lease-4");
    assertThat(target.attemptedCursors.get(1))
        .as("lease-4's opaque token sorts below lease-3's token")
        .isLessThan(target.attemptedCursors.getFirst());
  }

  @Test
  void usesMongoSimpleBinaryOrderingForBmpAndAstralIdentifiers() throws IOException {
    var firstInSimpleOrder = "\uE000";
    var secondInSimpleOrder = "\uD83D\uDE00";
    client.getDatabase("test").getCollection("application_runtime").insertMany(List.of(
        envelope(secondInSimpleOrder, 2), envelope(firstInSimpleOrder, 1)));

    var batch = new MongoMigrationSourceReader(client)
        .readAfter(context(2), kind(), null, 2);

    assertThat(batch.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly(firstInSimpleOrder, secondInSimpleOrder);
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

  @Test
  void explicitlyDeclaredObjectIdsPageAsBsonAndBecomeCanonicalHexSourceIds()
      throws IOException {
    var firstId = new ObjectId("000000000000000000000006");
    var secondId = new ObjectId("000000000000000000000007");
    var first = envelope("ignored-1", 1);
    first.put("_id", new Document("kind", "application_lease").append("legacyId", firstId));
    var second = envelope("ignored-2", 2);
    second.put("_id", new Document("kind", "application_lease").append("legacyId", secondId));
    client.getDatabase("test").getCollection("application_runtime").insertMany(List.of(second, first));
    var reader = new MongoMigrationSourceReader(client);
    var objectIdKind = withIdentifierType(kind(), "object-id");

    var firstPage = reader.readAfter(context(), objectIdKind, null, 1);
    var secondPage = reader.readAfter(context(), objectIdKind, firstPage.lastCursor(), 1);

    assertThat(firstPage.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly(firstId.toHexString());
    assertThat(secondPage.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly(secondId.toHexString());
  }

  @Test
  void explicitlyDeclaredMixedIdentifiersPageInCanonicalSourceIdOrder()
      throws IOException {
    var objectId = new ObjectId("000000000000000000000006");
    var objectEnvelope = envelope("ignored", 1);
    objectEnvelope.put(
        "_id", new Document("kind", "application_lease").append("legacyId", objectId));
    var stringEnvelope = envelope("lease-a", 2);
    client.getDatabase("test").getCollection("application_runtime")
        .insertMany(List.of(stringEnvelope, objectEnvelope));
    var reader = new MongoMigrationSourceReader(client);
    var mixedKind = withIdentifierType(kind(), "string-or-object-id");

    var firstPage = reader.readAfter(context(), mixedKind, null, 1);
    var secondPage = reader.readAfter(context(), mixedKind, firstPage.lastCursor(), 1);

    assertThat(firstPage.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly(objectId.toHexString());
    assertThat(secondPage.documents()).extracting(MigrationSourceDocument::sourceId)
        .containsExactly("lease-a");
  }

  @Test
  void mixedIdentifiersRejectCanonicalStringAndObjectIdCollisions() throws IOException {
    var shared = "000000000000000000000006";
    var objectEnvelope = envelope("ignored", 1);
    objectEnvelope.put("_id", new Document("kind", "application_lease")
        .append("legacyId", new ObjectId(shared)));
    client.getDatabase("test").getCollection("application_runtime")
        .insertMany(List.of(envelope(shared, 2), objectEnvelope));

    assertThatThrownBy(() -> new MongoMigrationSourceReader(client)
        .readAfter(context(), withIdentifierType(kind(), "string-or-object-id"), null, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PostgreSQL migration Mongo source envelope is invalid.");
  }

  @Test
  void rejectsACursorWhoseDecodedIdentifierHasTheWrongBsonType() throws IOException {
    client.getDatabase("test").getCollection("application_runtime")
        .insertOne(envelope("lease-a", 1));
    var numericCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
        new Document("value", 6).toJson().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> new MongoMigrationSourceReader(client)
        .readAfter(context(), kind(), numericCursor, 1))
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
    return context(1);
  }

  private static ValidatedMigrationContext context(int batchSize) {
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
        1,
        UUID.randomUUID(),
        null,
        batchSize);
    return new ValidatedMigrationContext(
        request,
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"),
        false);
  }

  private static PostgresqlMigrationCatalog.Kind kind() throws IOException {
    return kind(catalog());
  }

  private static PostgresqlMigrationCatalog.Kind kind(PostgresqlMigrationCatalog catalog) {
    return catalog.kinds().stream()
        .filter(candidate -> candidate.sourceKind().equals("application_lease"))
        .findFirst()
        .orElseThrow();
  }

  private static PostgresqlMigrationCatalog.Kind withIdentifierType(
      PostgresqlMigrationCatalog.Kind kind, String identifierType) {
    return new PostgresqlMigrationCatalog.Kind(
        kind.sourceCollection(), kind.sourceKind(), kind.sourceOwner(),
        kind.minimumBridgeRelease(), kind.sourceCanonicalization(),
        kind.targetCanonicalization(), kind.sourceSchemaVersion(), kind.transformerVersion(),
        identifierType, kind.targetSchema(), kind.targetTables(), kind.loadOrder(),
        kind.dependsOnKinds(), kind.keyMapping(), kind.fieldMappings(), kind.deleteBehavior(),
        kind.versionSemantics(), kind.expirySemantics(), kind.canonicalHash(),
        kind.reconciliation(), kind.portQueries(), kind.transformerClass());
  }

  private static PostgresqlMigrationCatalog catalog() throws IOException {
    try (var input = MongoMigrationSourceReaderTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }

  private static final class CrashResumeTarget implements MigrationTargetStore {
    private MigrationCheckpoint checkpoint = MigrationCheckpoint.initial();
    private final List<String> staged = new ArrayList<>();
    private final List<String> attemptedCursors = new ArrayList<>();
    private int failCommit;
    private int commitCalls;

    private CrashResumeTarget(int failCommit) {
      this.failCommit = failCommit;
    }

    @Override
    public void requireExistingRun(ValidatedMigrationContext context) {}

    @Override
    public void prepareExistingRunVerification(
        ValidatedMigrationContext context,
        List<PostgresqlMigrationCatalog.Kind> kinds) {}

    @Override
    public MigrationCheckpoint checkpoint(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      return checkpoint;
    }

    @Override
    public MigrationCheckpoint commitBatch(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected,
        List<TransformedMigrationDocument> documents,
        String nextCursor) {
      assertThat(expected).isEqualTo(checkpoint);
      attemptedCursors.add(nextCursor);
      commitCalls++;
      if (commitCalls == failCommit) {
        throw new InjectedFailure();
      }
      var advanced = checkpoint.advance(nextCursor, documents);
      documents.forEach(document -> staged.add(document.sourceId()));
      checkpoint = advanced;
      return checkpoint;
    }

    @Override
    public MigrationCheckpoint completeStaging(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected) {
      assertThat(expected).isEqualTo(checkpoint);
      checkpoint = checkpoint.markComplete();
      return checkpoint;
    }

    @Override
    public void requireStagedDocuments(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        List<TransformedMigrationDocument> documents) {}

    @Override
    public MigrationReconciliation reconcile(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void verifyExistingRun(
        ValidatedMigrationContext context,
        List<PostgresqlMigrationCatalog.Kind> kinds,
        List<MigrationReconciliation> reconciliations) {}

    @Override
    public void rehearseShadow(
        ValidatedMigrationContext context,
        List<PostgresqlMigrationCatalog.Kind> kinds,
        List<MigrationReconciliation> reconciliations) {}

    @Override
    public void finalizeRun(
        ValidatedMigrationContext context,
        List<PostgresqlMigrationCatalog.Kind> kinds,
        List<MigrationReconciliation> reconciliations,
        LockedFinalizationCheck finalizationCheck) {}

    @Override
    public List<MigrationKindStatus> statuses(ValidatedMigrationContext context) {
      return List.of();
    }
  }

  private static final class InjectedFailure extends RuntimeException {}
}
