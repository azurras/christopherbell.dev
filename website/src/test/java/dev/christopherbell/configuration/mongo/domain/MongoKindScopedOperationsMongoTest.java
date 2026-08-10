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
import java.util.UUID;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

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
