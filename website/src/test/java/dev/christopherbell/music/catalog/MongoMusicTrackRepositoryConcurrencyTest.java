package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises preference updates completed between a catalog read and its delayed write. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class MongoMusicTrackRepositoryConcurrencyTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static MongoClient client;

  private MongoTemplate mongo;
  private String ownedPath;
  private DomainMongoOperationsFactory factory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1 || !"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("Music concurrency tests require one disposable MongoDB with database test.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Music concurrency tests require a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createOwnedFixture() {
    ownedPath = "preference-race-" + UUID.randomUUID() + "/song.mp3";
    mongo = new MongoTemplate(client, "test");
    factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
  }

  @AfterEach
  void deleteOwnedFixture() {
    mongo.getCollection("music").deleteMany(
        new Document("_kind", "music_track").append("payload.path", ownedPath));
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  private static final Instant NOW = Instant.parse("2026-11-03T17:00:00Z");

  @Test
  void indexedMetadataPreservesPreferencesChangedAfterTheScannerRead() {
    var tracks = new MongoMusicTrackRepository(factory);
    var stale = tracks.save(MusicTrack.ready(ownedPath, "old", metadata("Old"), null, NOW));
    changePreferences(tracks, stale);

    var saved = tracks.save(MusicTrack.indexed(
        stale, stale.path(), "new", metadata("New"), "art", NOW.plusSeconds(1)));

    assertThat(saved.title()).isEqualTo("New");
    assertThat(saved.observedToken()).isEqualTo("new");
    assertCurrentPreferences(tracks, saved);
  }

  @Test
  void failedProbePreservesPreferencesChangedAfterTheScannerRead() {
    var tracks = new MongoMusicTrackRepository(factory);
    var stale = tracks.save(MusicTrack.ready(ownedPath, "old", metadata("Old"), null, NOW));
    changePreferences(tracks, stale);

    var saved = tracks.save(MusicTrack.probeFailed(
        stale, stale.path(), "new", "probe failed", NOW.plusSeconds(1)));

    assertThat(saved.indexStatus()).isEqualTo(MusicIndexStatus.PROBE_FAILED);
    assertThat(saved.title()).isEqualTo("Old");
    assertCurrentPreferences(tracks, saved);
  }

  @Test
  void missingMarkPreservesPreferencesChangedAfterTheScannerRead() {
    var tracks = new MongoMusicTrackRepository(factory);
    var stale = tracks.save(MusicTrack.ready(ownedPath, "old", metadata("Old"), null, NOW));
    changePreferences(tracks, stale);

    var saved = tracks.save(stale.markMissing(NOW.plusSeconds(1)));

    assertThat(saved.missingSince()).isEqualTo(NOW.plusSeconds(1));
    assertCurrentPreferences(tracks, saved);
  }

  private void changePreferences(MongoMusicTrackRepository tracks, MusicTrack stale) {
    assertThat(tracks.updatePreferences(stale.id(),
        new MusicTrackPreferences(false, false), new MusicTrackPreferences(true, true))).isTrue();
  }

  private void assertCurrentPreferences(MongoMusicTrackRepository tracks, MusicTrack saved) {
    assertThat(saved.favorite()).isTrue();
    assertThat(saved.excludedFromRadio()).isTrue();
    var persisted = tracks.findById(saved.id()).orElseThrow();
    assertThat(persisted.favorite()).isTrue();
    assertThat(persisted.excludedFromRadio()).isTrue();
  }

  private MusicProbeResult metadata(String title) {
    return new MusicProbeResult(title, "Artist", "Artist", "Album", 1, 1, "Genre", 2026,
        180, "mp3", "mp3", false);
  }
}
