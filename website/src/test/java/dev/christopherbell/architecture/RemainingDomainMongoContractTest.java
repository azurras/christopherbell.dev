package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.mongo.domain.MalformedDomainDocumentException;
import dev.christopherbell.configuration.mongo.migration.ApplicationMigration;
import dev.christopherbell.configuration.mongo.migration.MigrationStateStore;
import dev.christopherbell.configuration.mongo.migration.MigrationStatus;
import dev.christopherbell.configuration.mongo.runtime.MongoApplicationLeaseStore;
import dev.christopherbell.configuration.mongo.runtime.MongoScheduledCollectorRunStore;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorRunStatus;
import dev.christopherbell.sharedfolder.service.MongoSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState;
import dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadFinalizationState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Real-Mongo proof for Task 5 atomic runtime and index contracts. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class RemainingDomainMongoContractTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static MongoClient client;

  private MongoTemplate mongo;
  private DomainMongoOperationsFactory factory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Task 5 contracts require one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Task 5 contracts require a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createDatabase() {
    var database = "remaining_domain_contract_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    mongo = new MongoTemplate(client, database);
    factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
  }

  @AfterEach
  void dropDatabase() {
    mongo.getDb().drop();
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void applicationLeaseHasOneAtomicOwnerAndMalformedRenewWritesNothing() {
    var leases = new MongoApplicationLeaseStore(factory);
    var now = Instant.parse("2026-08-11T00:00:00Z");

    assertThat(leases.tryAcquire("global", "owner-a", now, now.plusSeconds(60))).isTrue();
    assertThat(leases.tryAcquire("global", "owner-b", now, now.plusSeconds(60))).isFalse();
    assertThat(leases.renew("global", "owner-b", now, now.plusSeconds(120))).isFalse();
    assertThat(leases.renew("global", "owner-a", now, now.plusSeconds(120))).isTrue();

    var collection = mongo.getCollection("application_runtime");
    var id = new Document("kind", "application_lease").append("legacyId", "global");
    var malformed = collection.find(new Document("_id", id)).first();
    malformed.remove("schemaVersion");
    collection.replaceOne(new Document("_id", id), malformed);
    var before = collection.find(new Document("_id", id)).first();

    assertThatThrownBy(() ->
        leases.renew("global", "owner-a", now, now.plusSeconds(180)))
        .isInstanceOf(MalformedDomainDocumentException.class);
    assertThat(collection.find(new Document("_id", id)).first()).isEqualTo(before);
  }

  @Test
  void migrationTransitionRequiresTheRunningOwner() {
    var state = new MigrationStateStore(factory);
    var migration = new TestMigration("V999_test", "checksum");
    var started = Instant.parse("2026-08-11T00:00:00Z");
    state.start(migration, "owner-a", started);

    assertThatThrownBy(() -> state.complete(migration.id(), "owner-b", started.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(state.find(migration.id()).orElseThrow().getStatus())
        .isEqualTo(MigrationStatus.RUNNING);

    state.complete(migration.id(), "owner-a", started.plusSeconds(2));
    assertThat(state.find(migration.id()).orElseThrow().getStatus())
        .isEqualTo(MigrationStatus.APPLIED);

    var failedMigration = new TestMigration("V998_failed", "checksum-failed");
    state.start(failedMigration, "owner-c", started.plusSeconds(3));
    assertThatThrownBy(() -> state.fail(
        failedMigration.id(), "stale-owner", started.plusSeconds(4), "IO"))
        .isInstanceOf(IllegalStateException.class);
    state.fail(failedMigration.id(), "owner-c", started.plusSeconds(5), "IO");
    var failed = state.find(failedMigration.id()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(MigrationStatus.FAILED);
    assertThat(failed.getFailureCategory()).isEqualTo("IO");
  }

  @Test
  void collectorHistoryAndLeaseIdentityAreIsolatedByKind() {
    var now = Instant.parse("2026-08-11T00:00:00Z");
    var leases = new MongoApplicationLeaseStore(factory);
    var runs = new MongoScheduledCollectorRunStore(factory);
    assertThat(leases.tryAcquire("shared-id", "owner-a", now, now.plusSeconds(60))).isTrue();
    runs.save(ScheduledCollectorRun.builder()
        .id("shared-id")
        .collectorName("collector-a")
        .ownerToken("owner-a")
        .status(ScheduledCollectorRunStatus.SUCCEEDED)
        .startedOn(now)
        .completedOn(now.plusSeconds(1))
        .build());

    assertThat(mongo.getCollection("application_runtime").countDocuments()).isEqualTo(2);
    assertThat(mongo.getCollection("application_runtime").countDocuments(
        new Document("_id.kind", "application_lease"))).isEqualTo(1);
    assertThat(mongo.getCollection("application_runtime").countDocuments(
        new Document("_id.kind", "scheduled_collector_run"))).isEqualTo(1);
  }

  @Test
  void uploadAndRecoveryLeaseClaimsHaveOneVersionWinnerAndMalformedNoWrite() {
    var now = Instant.parse("2026-08-11T00:00:00Z");
    var collection = mongo.getCollection("shared_folder");
    var uploads = new MongoSharedFolderUploadSessionRepository(factory);
    var recoveries = new MongoSharedFolderMutationRecoveryRepository(factory);

    collection.insertOne(envelope("upload_session", "upload-a", new Document("version", 0L)
        .append("state", "FINALIZING")
        .append("finalizationLeaseToken", "old")
        .append("finalizationState", "TARGET_QUARANTINED")
        .append("finalizationLeaseExpiresAt", now.minusSeconds(1))));
    assertThat(uploads.claimExpiredFinalizationLease(
        "upload-a", "old", SharedFolderUploadFinalizationState.TARGET_QUARANTINED, now,
        "winner", now.plusSeconds(60), now)).isEqualTo(1);
    assertThat(uploads.claimExpiredFinalizationLease(
        "upload-a", "old", SharedFolderUploadFinalizationState.TARGET_QUARANTINED, now,
        "stale", now.plusSeconds(60), now)).isZero();
    var upload = collection.find(new Document(
        "_id", new Document("kind", "upload_session").append("legacyId", "upload-a")))
        .first();
    assertThat(upload.get("payload", Document.class).getLong("version")).isEqualTo(1L);
    assertThat(upload.get("payload", Document.class).getString("finalizationLeaseToken"))
        .isEqualTo("winner");

    collection.insertOne(envelope("mutation_recovery", "recovery-a", new Document("version", 0L)
        .append("state", "TARGET_QUARANTINED")
        .append("operationLeaseToken", "old")
        .append("operationLeaseExpiresAt", now.minusSeconds(1))));
    assertThat(recoveries.claimExpiredOperationLease(
        "recovery-a", "old", SharedFolderMutationRecoveryState.TARGET_QUARANTINED, now,
        "winner", now.plusSeconds(60), now)).isEqualTo(1);
    assertThat(recoveries.claimExpiredOperationLease(
        "recovery-a", "old", SharedFolderMutationRecoveryState.TARGET_QUARANTINED, now,
        "stale", now.plusSeconds(60), now)).isZero();

    var malformedId = new Document("kind", "upload_session").append("legacyId", "bad");
    var malformed = envelope("upload_session", "bad", new Document("version", 0L)
        .append("state", "APPENDING").append("appendLeaseToken", "owner")
        .append("appendOffset", 0L));
    malformed.remove("schemaVersion");
    collection.insertOne(malformed);
    var before = collection.find(new Document("_id", malformedId)).first();
    assertThatThrownBy(() -> uploads.renewAppendLease(
        "bad", "owner", 0L, now.plusSeconds(60), now))
        .isInstanceOf(MalformedDomainDocumentException.class);
    assertThat(collection.find(new Document("_id", malformedId)).first()).isEqualTo(before);
  }

  @Test
  void ttlAndUniqueIndexesPreserveAuditMediaVinCacheAndZipContracts() {
    var ttl = indexByKey("audit_event", "payload.expiresAt");
    var vinTtl = indexByKey("vin_decode_cache", "payload.expiresOn");
    var unique = indexByKey("media_job", "payload.activeCacheKey");
    createIndex(ttl);
    createIndex(vinTtl);
    createIndex(unique);

    var collection = mongo.getCollection("shared_folder");
    collection.insertOne(envelope("media_job", "job-a",
        new Document("activeCacheKey", "cache-a")));
    assertThatThrownBy(() -> collection.insertOne(envelope("media_job", "job-b",
        new Document("activeCacheKey", "cache-a"))))
        .isInstanceOf(MongoWriteException.class);
    collection.insertOne(envelope("audit_event", "audit-a",
        new Document("activeCacheKey", "cache-a")
            .append("expiresAt", Instant.parse("2099-01-01T00:00:00Z"))));

    var actualTtl = collection.listIndexes().into(new ArrayList<Document>()).stream()
        .filter(index -> ttl.name().equals(index.getString("name")))
        .findFirst().orElseThrow();
    assertThat(actualTtl.get("expireAfterSeconds", Number.class).longValue()).isZero();
    assertThat(actualTtl.get("key", Document.class))
        .isEqualTo(new Document("payload.expiresAt", 1));

    var vehicleCollection = mongo.getCollection("vehicles");
    var actualVinTtl = vehicleCollection.listIndexes().into(new ArrayList<Document>()).stream()
        .filter(index -> vinTtl.name().equals(index.getString("name")))
        .findFirst().orElseThrow();
    assertThat(actualVinTtl.get("expireAfterSeconds", Number.class).longValue()).isZero();
    assertThat(actualVinTtl.get("key", Document.class))
        .isEqualTo(new Document("payload.expiresOn", 1));

    var locations = mongo.getCollection("location");
    locations.insertOne(envelope("zip_coordinate", "75001", new Document("source", "a")));
    assertThatThrownBy(() -> locations.insertOne(
        envelope("zip_coordinate", "75001", new Document("source", "b"))))
        .isInstanceOf(MongoWriteException.class);
    locations.insertOne(envelope("zip_import_state", "75001", new Document("source", "a")));
    assertThat(locations.countDocuments()).isEqualTo(2);
  }

  private void createIndex(DomainCollectionManifest.IndexDefinition definition) {
    var keys = new Document();
    definition.keys().forEach(key -> keys.append(key.path(), key.direction()));
    var options = new IndexOptions().name(definition.name()).unique(definition.unique());
    if (!definition.partialFilterExpression().isEmpty()) {
      options.partialFilterExpression(new Document(definition.partialFilterExpression()));
    }
    definition.expireAfterSeconds()
        .ifPresent(seconds -> options.expireAfter(seconds, TimeUnit.SECONDS));
    mongo.getCollection(definition.collection()).createIndex(keys, options);
  }

  private static DomainCollectionManifest.IndexDefinition indexByKey(String kind, String path) {
    return DomainCollectionManifest.forKind(kind).orElseThrow().indexes().stream()
        .filter(index -> index.keys().stream().anyMatch(key -> key.path().equals(path)))
        .findFirst().orElseThrow();
  }

  private static Document envelope(String kind, String id, Document payload) {
    return new Document("_id", new Document("kind", kind).append("legacyId", id))
        .append("_kind", kind)
        .append("schemaVersion", 1)
        .append("payload", payload);
  }

  private record TestMigration(String id, String checksum) implements ApplicationMigration {
    @Override public String description() { return "test"; }
    @Override public void apply(MongoTemplate mongo) {}
  }
}
