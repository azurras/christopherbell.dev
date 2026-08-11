package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.mongo.domain.MalformedDomainDocumentException;
import dev.christopherbell.music.catalog.MongoMusicTrackRepository;
import dev.christopherbell.music.catalog.MusicIndexStatus;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.metadata.MongoMusicMetadataEditRepository;
import dev.christopherbell.music.metadata.MusicMetadataEdit;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewCounts;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.session.MongoWhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Proves malformed selected Task 4 envelopes fail before any Mongo mutation. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class MusicAndLunchMutationSafetyMongoTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static MongoClient client;

  private MongoTemplate mongo;
  private DomainMongoOperationsFactory factory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Task 4 mutation tests require one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Task 4 mutation tests require a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createDatabase() {
    var database = "music_lunch_mutation_"
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
  void malformedTrackIsUnchangedWhenPreferenceUpdateIsRejected() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var track = new MusicTrack(
        "track-1", "track.mp3", "token", null, "Track", "Artist", "Artist", "Album",
        1, 1, "Genre", 2026, 90, "mp3", "mp3", null, false, false,
        MusicIndexStatus.READY, null, now, now, null);
    insertMalformed("music", track);
    var before = rawBytes("music", "music_track", "track-1");

    assertThatThrownBy(() -> new MongoMusicTrackRepository(factory)
        .updatePreferences("track-1", false, false, true, true))
        .isInstanceOf(MalformedDomainDocumentException.class);

    assertThat(rawBytes("music", "music_track", "track-1")).isEqualTo(before);
  }

  @Test
  void malformedSessionIsUnchangedWhenJoinIsRejected() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var session = WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-1")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-1"))
        .participantUsernamesByAccountId(Map.of("owner-1", "owner"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .votesByAccountId(Map.of())
        .revision(0)
        .activeUntil(now.plusSeconds(600))
        .deleteOn(now.plusSeconds(1_200))
        .restaurantResetAudit(List.of())
        .createdOn(now)
        .lastUpdatedOn(now)
        .build();
    insertMalformed("whatsforlunch", session);
    var before = rawBytes("whatsforlunch", "session", "session-1");
    var repository = new MongoWhatsForLunchSessionRepository(factory);
    var mutations = new WhatsForLunchSessionMutationStore(factory, repository);

    assertThatThrownBy(() -> mutations.join("session-1", "friend-1", "friend", now, 20))
        .isInstanceOf(MalformedDomainDocumentException.class);

    assertThat(rawBytes("whatsforlunch", "session", "session-1")).isEqualTo(before);
  }

  @Test
  void malformedPreviewIsUnchangedWhenClaimIsRejected() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var preview = RestaurantImportPreviewDocument.builder()
        .id("token-1")
        .actorAccountId("account-1")
        .checksum("checksum")
        .createdOn(now)
        .expiresOn(now.plusSeconds(600))
        .counts(new RestaurantImportPreviewCounts(1, 0, 0, 0, 0, 0))
        .build();
    insertMalformed("whatsforlunch", preview);
    var before = rawBytes("whatsforlunch", "import_preview", "token-1");

    assertThatThrownBy(() -> new RestaurantImportPreviewStore(factory)
        .claim("token-1", "account-1", now.plusSeconds(1)))
        .isInstanceOf(MalformedDomainDocumentException.class);

    assertThat(rawBytes("whatsforlunch", "import_preview", "token-1")).isEqualTo(before);
  }

  @Test
  void malformedMetadataEditIsUnchangedWhenDeleteIsRejected() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var edit = new MusicMetadataEdit(
        "edit-1", "track-1", "source", "backup", "hash", "old", "new", "mp3", 90,
        "account-1", now.minusSeconds(100), now.plusSeconds(600),
        MusicMetadataEdit.Status.APPLIED, null, null);
    insertMalformed("music", edit);
    var before = rawBytes("music", "music_metadata_edit", "edit-1");

    assertThatThrownBy(() -> new MongoMusicMetadataEditRepository(factory).deleteById("edit-1"))
        .isInstanceOf(MalformedDomainDocumentException.class);

    assertThat(rawBytes("music", "music_metadata_edit", "edit-1")).isEqualTo(before);
  }

  private void insertMalformed(String collection, Object value) {
    var envelope = DomainMongoOperationsTestFactory.envelope(mongo, value);
    envelope.put("schemaVersion", 1L);
    mongo.getCollection(collection).insertOne(envelope);
  }

  private byte[] rawBytes(String collection, String kind, String id) {
    var namespacedId = new Document("kind", kind).append("legacyId", id);
    RawBsonDocument raw = mongo.getDb().getCollection(collection, RawBsonDocument.class)
        .find(new Document("_id", namespacedId))
        .first();
    if (raw == null) {
      throw new AssertionError("Expected raw Task 4 fixture is absent.");
    }
    return Arrays.copyOfRange(
        raw.getBackingArray(), raw.getByteOffset(), raw.getByteOffset() + raw.getByteLength());
  }
}
