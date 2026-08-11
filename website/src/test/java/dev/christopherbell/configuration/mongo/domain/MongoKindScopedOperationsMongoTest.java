package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.auditing.IsNewAwareAuditingHandler;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.mapping.event.AuditingEntityCallback;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveCallback;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import dev.christopherbell.notification.preference.NotificationPreference;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.time.Instant;

@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class MongoKindScopedOperationsMongoTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static MongoClient client;

  private MongoTemplate mongo;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Domain boundary test requires one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Domain boundary test requires a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @AfterEach
  void dropDatabase() {
    if (mongo != null) {
      mongo.getDb().drop();
    }
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void roundTripsBsonTypesAndPerformsKindScopedIndexedCrud() {
    mongo = template("crud");
    var firstKind = operations("sample_first");
    var secondKind = operations("sample_second");
    var objectId = new ObjectId();
    var scalar = new SampleDocument(
        42L, "scalar", 4_294_967_296L, Decimal128.parse("123456789.0123456789"), null);
    var object = new SampleDocument(
        objectId, "object", 9L, Decimal128.parse("0.0000000000000001"), null);
    mongo.getCollection("content").createIndex(
        new Document("_kind", 1).append("payload.display_name", 1),
        new com.mongodb.client.model.IndexOptions().name("kind_display_name"));

    var insertedScalar = firstKind.insert(scalar);
    var insertedObject = firstKind.insert(object);
    secondKind.insert(new SampleDocument(
        42L, "other-kind", 1L, Decimal128.parse("1"), null));

    assertThat(insertedScalar.version()).isZero();
    assertThat(insertedObject.id()).isEqualTo(objectId);
    assertThat(insertedObject.amount()).isEqualTo(Decimal128.parse("0.0000000000000001"));
    assertThat(firstKind.findById(42L)).contains(insertedScalar);
    assertThat(firstKind.findById(objectId)).contains(insertedObject);
    assertThat(firstKind.find(
        Query.query(Criteria.where("displayName").in("scalar", "object")),
        PageRequest.of(0, 10, Sort.by("displayName"))))
        .extracting(SampleDocument::displayName)
        .containsExactly("object", "scalar");
    assertThat(firstKind.count(new Query())).isEqualTo(2);
    assertThat(secondKind.count(new Query())).isEqualTo(1);
    assertThat(firstKind.exists(Query.query(Criteria.where("displayName").is("other-kind"))))
        .isFalse();

    firstKind.updateFirst(
        Query.query(Criteria.where("displayName").is("scalar")),
        Update.update("displayName", "updated").inc("visits", 1));

    assertThat(firstKind.findOne(Query.query(Criteria.where("displayName").is("updated"))))
        .get()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(42L);
          assertThat(found.visits()).isEqualTo(4_294_967_297L);
          assertThat(found.amount()).isEqualTo(Decimal128.parse("123456789.0123456789"));
        });
    assertThat(indexNames(mongo.getCollection("content")
        .find(new Document("_kind", "sample_first")
            .append("payload.display_name", "updated"))
        .explain()))
        .contains("kind_display_name");

    assertThat(firstKind.remove(Query.query(Criteria.where("displayName").is("updated")))
        .getDeletedCount()).isEqualTo(1);
    assertThat(firstKind.findById(42L)).isEmpty();
    assertThat(secondKind.findById(42L)).isPresent();
  }

  @Test
  void versionedSaveInsertsWhenAbsentAndRejectsTheStaleWriter() {
    mongo = template("version");
    var operations = operations("sample_versioned");
    var initial = new SampleDocument(
        "same-id", "initial", 1L, Decimal128.parse("1.25"), null);

    var inserted = operations.save(initial);
    var winning = operations.save(new SampleDocument(
        inserted.id(), "winner", inserted.visits(), inserted.amount(), inserted.version()));

    assertThat(inserted.version()).isZero();
    assertThat(winning.version()).isEqualTo(1L);
    assertThatThrownBy(() -> operations.save(new SampleDocument(
        inserted.id(), "stale", inserted.visits(), inserted.amount(), inserted.version())))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(operations.findById("same-id"))
        .get()
        .satisfies(current -> {
          assertThat(current.displayName()).isEqualTo("winner");
          assertThat(current.version()).isEqualTo(1L);
        });
  }

  @Test
  void updateFirstAdvancesVersionSoAStaleSaveCannotOverwriteIt() {
    mongo = template("update-version");
    var operations = operations("sample_update_version");
    var stale = operations.insert(new SampleDocument(
        "same-id", "initial", 7L, Decimal128.parse("2.50"), null));

    operations.updateFirst(
        Query.query(Criteria.where("id").is("same-id")),
        Update.update("displayName", "updated"));

    assertThatThrownBy(() -> operations.save(new SampleDocument(
        stale.id(), "stale", stale.visits(), stale.amount(), stale.version())))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(operations.findById("same-id"))
        .get()
        .satisfies(current -> {
          assertThat(current.displayName()).isEqualTo("updated");
          assertThat(current.version()).isEqualTo(1L);
        });
  }

  @Test
  void staleVersionedSaveCannotRecreateAConcurrentlyDeletedDocument() {
    mongo = template("delete-version");
    var operations = operations("sample_delete_version");
    var stale = operations.insert(new SampleDocument(
        "same-id", "initial", 7L, Decimal128.parse("2.50"), null));

    operations.remove(Query.query(Criteria.where("id").is("same-id")));

    assertThatThrownBy(() -> operations.save(stale))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(operations.findById("same-id")).isEmpty();
  }

  @Test
  void nullIdInsertGeneratesIdsAndAppliesAuditingAcrossRepresentativeEntities() {
    mongo = template("generated-id-auditing");
    var now = new AtomicReference<>(Instant.parse("2026-08-10T20:00:00Z"));
    var callbacks = auditingCallbacks(mongo, now);
    mongo.setEntityCallbacks(callbacks);
    var preferences = new MongoKindScopedOperations<>(
        mongo, DomainCollectionManifest.forType(NotificationPreference.class), callbacks);
    var reports = new MongoKindScopedOperations<>(
        mongo, DomainCollectionManifest.forType(PostReport.class), callbacks);

    var insertedPreference = preferences.insert(NotificationPreference.builder()
        .accountId("account-1")
        .mentions(true)
        .build());
    var insertedReport = reports.insert(PostReport.builder()
        .postId("post-1")
        .reporterAccountId("reporter-1")
        .reportType(ReportType.SPAM)
        .targetType(ReportTargetType.POST)
        .status(ReportStatus.OPEN)
        .build());

    assertThat(insertedPreference.getId()).matches("[0-9a-f]{24}");
    assertThat(insertedReport.getId()).matches("[0-9a-f]{24}");
    assertThat(insertedPreference.getCreatedOn()).isEqualTo(now.get());
    assertThat(insertedPreference.getLastUpdatedOn()).isEqualTo(now.get());
    assertThat(insertedReport.getCreatedOn()).isEqualTo(now.get());
    assertThat(insertedReport.getLastUpdatedOn()).isEqualTo(now.get());

    var createdOn = insertedPreference.getCreatedOn();
    now.set(Instant.parse("2026-08-10T20:05:00Z"));
    insertedPreference.setLikes(true);
    var updatedPreference = preferences.save(insertedPreference);

    assertThat(updatedPreference.getCreatedOn()).isEqualTo(createdOn);
    assertThat(updatedPreference.getLastUpdatedOn()).isEqualTo(now.get());
    assertThat(preferences.findById(updatedPreference.getId())).contains(updatedPreference);
  }

  @Test
  void insertCarriesCallbackReturnedSourceAndPopulatesIdOnlyAfterPersistence() {
    mongo = template("callback-lifecycle");
    var calls = new ArrayList<String>();
    var beforeConvertCalls = new AtomicInteger();
    var beforeSaveCalls = new AtomicInteger();
    var afterSaveCalls = new AtomicInteger();
    var original = NotificationPreference.builder()
        .accountId("original-account")
        .build();
    var convertedSource = NotificationPreference.builder()
        .accountId("converted-account")
        .mentions(true)
        .build();
    var beforeSaveSource = NotificationPreference.builder()
        .accountId("callback-returned-account")
        .messages(true)
        .build();
    var afterSaveSource = new AtomicReference<NotificationPreference>();

    BeforeConvertCallback<NotificationPreference> beforeConvert = (source, collection) -> {
      calls.add("before-convert");
      beforeConvertCalls.incrementAndGet();
      assertThat(source).isSameAs(original);
      assertThat(source.getId()).isNull();
      return convertedSource;
    };
    BeforeSaveCallback<NotificationPreference> beforeSave = (source, document, collection) -> {
      calls.add("before-save");
      beforeSaveCalls.incrementAndGet();
      assertThat(source).isSameAs(convertedSource);
      assertThat(source.getId()).isNull();
      assertThat(document).doesNotContainKey("_id");
      document.put("accountId", "document-mutated-account");
      document.put("likes", true);
      return beforeSaveSource;
    };
    AfterSaveCallback<NotificationPreference> afterSave = (source, document, collection) -> {
      calls.add("after-save");
      afterSaveCalls.incrementAndGet();
      assertThat(source).isSameAs(beforeSaveSource);
      assertThat(source.getId()).matches("[0-9a-f]{24}");
      assertThat(document.get("_id")).isInstanceOf(ObjectId.class);
      assertThat(((ObjectId) document.get("_id")).toHexString()).isEqualTo(source.getId());
      assertThat(document.get("accountId")).isEqualTo("document-mutated-account");
      var returned = NotificationPreference.builder()
          .id(source.getId())
          .accountId("after-save-returned-account")
          .wflSessions(true)
          .build();
      afterSaveSource.set(returned);
      return returned;
    };
    var callbacks = EntityCallbacks.create(beforeConvert, beforeSave, afterSave);
    var preferences = new MongoKindScopedOperations<>(
        mongo, DomainCollectionManifest.forType(NotificationPreference.class), callbacks);

    var inserted = preferences.insert(original);

    assertThat(calls).containsExactly("before-convert", "before-save", "after-save");
    assertThat(beforeConvertCalls).hasValue(1);
    assertThat(beforeSaveCalls).hasValue(1);
    assertThat(afterSaveCalls).hasValue(1);
    assertThat(inserted).isSameAs(afterSaveSource.get());
    assertThat(preferences.findById(inserted.getId())).get().satisfies(stored -> {
      assertThat(stored.getAccountId()).isEqualTo("document-mutated-account");
      assertThat(stored.isMentions()).isTrue();
      assertThat(stored.isLikes()).isTrue();
    });
  }

  @Test
  void aggregationRejectsAMalformedEnvelopeBeforeReturningPayloadData() {
    mongo = template("aggregate-malformed");
    var operations = operations("sample_malformed");
    mongo.getCollection("content").insertOne(new Document(
        "_id", NamespacedMongoId.of("sample_malformed", "legacy-id").toBson())
        .append("_kind", "sample_malformed")
        .append("schemaVersion", 99)
        .append("payload", new Document("display_name", "Ada")
            .append("visits", 1L)
            .append("amount", Decimal128.parse("1.0"))));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.local(Aggregation.newAggregation(
            Aggregation.match(new Criteria()))),
        SampleDocument.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  @Test
  void aggregationRejectsUnexpectedEnvelopeAndIdentityFieldsBeforeUnwrapping() {
    mongo = template("aggregate-unexpected-fields");
    var operations = operations("sample_unexpected_fields");
    var identity = NamespacedMongoId.of("sample_unexpected_fields", "legacy-id").toBson()
        .append("unexpected", "value");
    mongo.getCollection("content").insertOne(new Document("_id", identity)
        .append("_kind", "sample_unexpected_fields")
        .append("schemaVersion", 1)
        .append("payload", new Document("display_name", "Ada")
            .append("visits", 1L)
            .append("amount", Decimal128.parse("1.0")))
        .append("unexpected", true));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.local(Aggregation.newAggregation(
            Aggregation.match(new Criteria()))),
        SampleDocument.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  @Test
  void aggregationRejectsMalformedForeignKindsInsideTheLookupCommand() {
    mongo = template("aggregate-malformed-foreign");
    var operations = operations("sample_local");
    operations.insert(new SampleDocument(
        "local-id", "Local", 1L, Decimal128.parse("1.0"), null));
    mongo.getCollection("content").insertOne(new Document(
        "_id", NamespacedMongoId.of("post", "foreign-id").toBson())
        .append("_kind", "post")
        .append("schemaVersion", 1)
        .append("payload", new Document("id", "foreign-id"))
        .append("unexpected", true));
    var lookup = new Document("from", "content")
        .append("pipeline", List.of(
            new Document("$match", new Document("_kind", "post")),
            new Document("$match", new Document("payload.id", "not-foreign-id"))))
        .append("as", "foreign");
    var aggregation = Aggregation.newAggregation(
        context -> new Document("$lookup", lookup));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.withForeignKinds(
            aggregation, KindScopedAggregation.ForeignKind.POST),
        Document.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  @Test
  void aggregationValidatesBeforeProjectionWithoutTreatingProjectedRowsAsEnvelopes() {
    mongo = template("aggregate-valid-projection");
    var operations = operations("sample_valid_projection");
    operations.insert(new SampleDocument(
        "legacy-id", "Ada", 1L, Decimal128.parse("1.0"), null));
    var aggregation = Aggregation.newAggregation(context -> new Document(
        "$project", new Document("_id", 0).append("visitCount", "$visits")));

    assertThat(operations.aggregate(
        KindScopedAggregation.local(aggregation), Document.class))
        .containsExactly(new Document("visitCount", 1L));
  }

  @Test
  void aggregationRejectsANullLegacyIdentityBeforeCallerProjection() {
    mongo = template("aggregate-null-identity");
    var operations = operations("sample_null_identity");
    mongo.getCollection("content").insertOne(new Document(
        "_id", new Document("kind", "sample_null_identity").append("legacyId", null))
        .append("_kind", "sample_null_identity")
        .append("schemaVersion", 1)
        .append("payload", new Document("display_name", "Ada")
            .append("visits", 1L)
            .append("amount", Decimal128.parse("1.0"))));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.local(Aggregation.newAggregation(
            Aggregation.match(Criteria.where("displayName").is("nobody")))),
        Document.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  @Test
  void aggregationRejectsANonDocumentIdentityWithTheRedactedTypedFailure() {
    mongo = template("aggregate-scalar-identity");
    var operations = operations("sample_scalar_identity");
    mongo.getCollection("content").insertOne(new Document("_id", "scalar-id")
        .append("_kind", "sample_scalar_identity")
        .append("schemaVersion", 1)
        .append("payload", new Document("display_name", "Ada")
            .append("visits", 1L)
            .append("amount", Decimal128.parse("1.0"))));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.local(Aggregation.newAggregation(
            Aggregation.match(Criteria.where("displayName").is("nobody")))),
        Document.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  @Test
  void aggregationRejectsANonCanonicalNamespacedIdentityFieldOrder() {
    mongo = template("aggregate-identity-order");
    var operations = operations("sample_identity_order");
    mongo.getCollection("content").insertOne(new Document(
        "_id", new Document("legacyId", "legacy-id").append("kind", "sample_identity_order"))
        .append("_kind", "sample_identity_order")
        .append("schemaVersion", 1)
        .append("payload", new Document("display_name", "Ada")
            .append("visits", 1L)
            .append("amount", Decimal128.parse("1.0"))));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.local(Aggregation.newAggregation(
            Aggregation.match(Criteria.where("displayName").is("nobody")))),
        Document.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"int64", "double"})
  void aggregationRejectsNonInt32SchemaVersionsBeforeCallerProjection(String representation) {
    mongo = template("aggregate-schema-" + representation);
    var operations = operations("sample_schema_type");
    Object schemaVersion = "int64".equals(representation) ? 1L : 1.0d;
    mongo.getCollection("content").insertOne(new Document(
        "_id", NamespacedMongoId.of("sample_schema_type", representation).toBson())
        .append("_kind", "sample_schema_type")
        .append("schemaVersion", schemaVersion)
        .append("payload", new Document("display_name", "Ada")
            .append("visits", 1L)
            .append("amount", Decimal128.parse("1.0"))));

    assertThatThrownBy(() -> operations.aggregate(
        KindScopedAggregation.local(Aggregation.newAggregation(
            Aggregation.match(Criteria.where("displayName").is("nobody")))),
        Document.class))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause();
  }

  private MongoKindScopedOperations<SampleDocument> operations(String kind) {
    var registry = DomainDocumentKindRegistry.of(Map.of(kind, "content"));
    return new MongoKindScopedOperations<>(
        mongo, registry.require(kind, 1, SampleDocument.class));
  }

  private static MongoTemplate template(String purpose) {
    String database = "domain_boundary_" + purpose + "_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    return new MongoTemplate(client, database);
  }

  private static EntityCallbacks auditingCallbacks(
      MongoTemplate mongo, AtomicReference<Instant> now) {
    var handler = IsNewAwareAuditingHandler.from(
        mongo.getConverter().getMappingContext());
    handler.setDateTimeProvider(() -> Optional.of(now.get()));
    return EntityCallbacks.create(new AuditingEntityCallback(() -> handler));
  }

  private static List<String> indexNames(Object value) {
    var names = new ArrayList<String>();
    collectIndexNames(value, names);
    return names;
  }

  private static void collectIndexNames(Object value, List<String> names) {
    if (value instanceof Map<?, ?> map) {
      map.forEach((key, nested) -> {
        if ("indexName".equals(key) && nested instanceof String name) {
          names.add(name);
        }
        collectIndexNames(nested, names);
      });
    } else if (value instanceof Iterable<?> values) {
      values.forEach(nested -> collectIndexNames(nested, names));
    }
  }

  record SampleDocument(
      @Id Object id,
      @Field("display_name") String displayName,
      long visits,
      Decimal128 amount,
      @Version Long version) {}
}
