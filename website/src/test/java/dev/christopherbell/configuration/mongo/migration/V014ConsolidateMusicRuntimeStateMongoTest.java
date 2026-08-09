package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.MusicRuntimeStateStore;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MUSIC_MIGRATION_TEST_URI", matches = ".+")
class V014ConsolidateMusicRuntimeStateMongoTest {
  private static final String TEST_URI = System.getenv("MUSIC_MIGRATION_TEST_URI");
  private static MongoClient client;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Migration boundary test requires one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Migration boundary test requires a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void preservesNonzeroVersionsAcrossTheActualMongoTemplateBoundary() {
    var mongo = template("nonzero");
    insertSources(mongo, 4L, 9L);

    assertThatCode(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .doesNotThrowAnyException();

    assertThat(target(mongo, "queue").get("version")).isEqualTo(4L);
    assertThat(target(mongo, "radio").get("version")).isEqualTo(9L);
  }

  @Test
  void migrationPreservesAbsentVersionUntilFirstAtomicRuntimeSave() {
    var mongo = template("absent-first-save");
    insertSources(mongo, null, null);
    new V014ConsolidateMusicRuntimeState().apply(mongo);

    assertThat(target(mongo, "queue").containsKey("version")).isFalse();
    assertThat(target(mongo, "radio").containsKey("version")).isFalse();

    var store = new MusicRuntimeStateStore(mongo);
    var saved = store.saveQueue(store.findQueue().orElseThrow());

    assertThat(saved.version()).isZero();
    assertThat(target(mongo, "queue").get("version")).isEqualTo(0L);
    assertThat(target(mongo, "radio").containsKey("version")).isFalse();
  }

  @Test
  void competingVersionlessSaveCannotOverwriteTheAtomicWinner() {
    var mongo = template("absent-contention");
    insertSources(mongo, null, null);
    new V014ConsolidateMusicRuntimeState().apply(mongo);
    var store = new MusicRuntimeStateStore(mongo);
    var firstSnapshot = store.findQueue().orElseThrow();
    var staleSnapshot = store.findQueue().orElseThrow();
    var winningEntry = entry("winner");
    var staleEntry = entry("stale");

    var winner = store.saveQueue(new MusicQueueState(
        MusicQueueState.ID, List.of(winningEntry), firstSnapshot.version()));

    assertThat(winner.version()).isZero();
    assertThatThrownBy(() -> store.saveQueue(new MusicQueueState(
        MusicQueueState.ID, List.of(staleEntry), staleSnapshot.version())))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(store.findQueue().orElseThrow().entries()).containsExactly(winningEntry);
  }

  @Test
  void genuinelyAbsentRuntimeDocumentRetainsNormalInsertSemantics() {
    var mongo = template("normal-insert");
    var store = new MusicRuntimeStateStore(mongo);

    var saved = store.saveQueue(MusicQueueState.empty());

    assertThat(saved.version()).isZero();
    assertThat(store.findQueue()).contains(saved);
  }

  @Test
  void malformedSourceFailsBeforeTheTargetCollectionExists() {
    var mongo = template("malformed-prewrite");
    mongo.getCollection("music_queue_state").insertOne(queueSource(4.5));
    mongo.getCollection("music_radio_state").insertOne(radioSource(9L));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source");

    assertThat(mongo.collectionExists("music_runtime_state")).isFalse();
  }

  private static MongoTemplate template(String purpose) {
    String database = "v014_" + purpose + "_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    return new MongoTemplate(client, database);
  }

  private static void insertSources(MongoTemplate mongo, Long queueVersion, Long radioVersion) {
    mongo.getCollection("music_queue_state").insertOne(queueSource(queueVersion));
    mongo.getCollection("music_radio_state").insertOne(radioSource(radioVersion));
  }

  private static Document target(MongoTemplate mongo, String id) {
    return mongo.getCollection("music_runtime_state")
        .find(new Document("_id", id))
        .first();
  }

  private static Document queueSource(Object version) {
    var document = new Document("_id", "global").append("entries", List.of());
    if (version != null) {
      document.append("version", version);
    }
    return document;
  }

  private static Document radioSource(Long version) {
    var document = new Document("_id", "global")
        .append("stationSequence", 3L)
        .append("trackId", "track-radio")
        .append("observedToken", "token-radio")
        .append("startedAt", Date.from(Instant.EPOCH))
        .append("durationSeconds", 90.0)
        .append("source", "RADIO");
    if (version != null) {
      document.append("version", version);
    }
    return document;
  }

  private static MusicQueueState.Entry entry(String id) {
    return new MusicQueueState.Entry(
        id, "track-" + id, "token-" + id, "account-1", Instant.EPOCH);
  }
}
