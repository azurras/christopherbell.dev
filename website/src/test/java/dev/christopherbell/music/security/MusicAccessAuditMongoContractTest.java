package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.mongo.domain.MalformedDomainDocumentException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Real-Mongo concurrency and malformed-storage contracts for Music access auditing. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class MusicAccessAuditMongoContractTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static final Instant NOW = Instant.parse("2026-08-10T20:00:00Z");
  private static MongoClient client;

  private MongoTemplate mongo;
  private DomainMongoOperationsFactory factory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Music audit contracts require one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Music audit contracts require a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createDatabase() {
    var database = "music_audit_contract_"
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
  void existingAttemptAggregatesAndReturnsTheCanonicalStoredRecord() {
    var recorder = recorder();

    recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");
    var aggregated = recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");

    assertThat(aggregated.count()).isEqualTo(2);
    assertThat(new MusicAccessAuditQueryService(
        new MongoMusicAccessAttemptRepository(factory)).recent(100))
        .containsExactly(aggregated);
    assertCanonicalEnvelope(aggregated.id());
  }

  @Test
  void concurrentInitialWritersConvergeOnOneCanonicalAggregatedRecord() throws Exception {
    int writerCount = 8;
    var barrier = new CyclicBarrier(writerCount);
    var executor = Executors.newFixedThreadPool(writerCount);
    try {
      var tasks = java.util.stream.IntStream.range(0, writerCount)
          .<java.util.concurrent.Callable<MusicAccessAttempt>>mapToObj(index -> () -> {
            barrier.await();
            return recorder().deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");
          })
          .toList();
      var futures = executor.invokeAll(tasks);
      for (var future : futures) {
        assertThat(future.get().id()).isNotBlank();
      }
    } finally {
      executor.shutdownNow();
    }

    var stored = new MusicAccessAuditQueryService(
        new MongoMusicAccessAttemptRepository(factory)).recent(100);
    assertThat(stored).singleElement().satisfies(attempt -> {
      assertThat(attempt.count()).isEqualTo(writerCount);
      assertCanonicalEnvelope(attempt.id());
    });
  }

  @Test
  void malformedExistingAttemptIsByteForByteUnchangedWhenAggregationIsRejected() {
    var recorder = recorder();
    var attempt = recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");
    var id = namespacedId(attempt.id());
    var collection = mongo.getCollection("music");
    var malformed = collection.find(new Document("_id", id)).first();
    if (malformed == null) {
      throw new AssertionError("Expected Music audit fixture is absent.");
    }
    malformed.put("schemaVersion", 1L);
    collection.replaceOne(new Document("_id", id), malformed);
    var before = rawBytes(attempt.id());

    assertThatThrownBy(() -> recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED"))
        .isInstanceOf(MalformedDomainDocumentException.class);

    assertThat(rawBytes(attempt.id())).isEqualTo(before);
  }

  private MusicAccessAuditRecorder recorder() {
    return new MusicAccessAuditRecorder(
        new MongoMusicAccessAttemptRepository(factory), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void assertCanonicalEnvelope(String id) {
    var document = mongo.getCollection("music").find(new Document("_id", namespacedId(id))).first();
    assertThat(document).isNotNull();
    assertThat(document.keySet()).containsExactly("_id", "_kind", "schemaVersion", "payload");
    assertThat(document.get("_id", Document.class).keySet()).containsExactly("kind", "legacyId");
    assertThat(document.getString("_kind")).isEqualTo("music_access_attempt");
    assertThat(document.get("schemaVersion")).isEqualTo(1);
  }

  private byte[] rawBytes(String id) {
    RawBsonDocument raw = mongo.getDb().getCollection("music", RawBsonDocument.class)
        .find(new Document("_id", namespacedId(id)))
        .first();
    if (raw == null) {
      throw new AssertionError("Expected raw Music audit fixture is absent.");
    }
    return Arrays.copyOfRange(
        raw.getBackingArray(), raw.getByteOffset(), raw.getByteOffset() + raw.getByteLength());
  }

  private static Document namespacedId(String id) {
    return new Document("kind", "music_access_attempt").append("legacyId", id);
  }
}
