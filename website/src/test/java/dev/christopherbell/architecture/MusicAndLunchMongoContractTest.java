package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.music.catalog.MongoMusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.MongoMusicTrackRepository;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicIndexStatus;
import dev.christopherbell.music.catalog.MusicQuery;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.library.MongoMusicPlaylistRepository;
import dev.christopherbell.music.library.MusicPlaylist;
import dev.christopherbell.music.metadata.MongoMusicMetadataEditRepository;
import dev.christopherbell.music.metadata.MusicMetadataEdit;
import dev.christopherbell.music.radio.MongoMusicRadioHistoryRepository;
import dev.christopherbell.music.radio.MongoMusicRuntimeStateRepository;
import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioHistoryEvent;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.MusicRuntimeStateStore;
import dev.christopherbell.music.security.MusicAccessAuditQueryService;
import dev.christopherbell.music.security.MusicAccessAuditRecorder;
import dev.christopherbell.music.security.MongoMusicAccessAttemptRepository;
import dev.christopherbell.whatsforlunch.restaurant.MongoRestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.MongoDailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.MongoRestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.favorite.MongoRestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.preference.MongoWhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.MongoWhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore;
import dev.christopherbell.whatsforlunch.restaurant.vote.MongoRestaurantVoteRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewCounts;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.auditing.IsNewAwareAuditingHandler;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.event.AuditingEntityCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Real-Mongo behavioral proof for the highest-risk Task 4 adapter invariants. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class MusicAndLunchMongoContractTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static MongoClient client;

  private MongoTemplate mongo;
  private DomainMongoOperationsFactory factory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Task 4 contracts require one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Task 4 contracts require a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createDatabase() {
    var database = "music_lunch_contract_"
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
  void queueAndRadioHaveIndependentCasWinnersAndStaleLosers() {
    var states = new MusicRuntimeStateStore(new MongoMusicRuntimeStateRepository(factory));
    var queue = states.saveQueue(queue(null, "entry-1"));
    var radio = states.saveRadio(radio(null, 1));
    var staleQueue = states.findQueue().orElseThrow();
    var staleRadio = states.findRadio().orElseThrow();

    var queueWinner = states.saveQueue(queue(queue.version(), "entry-2"));
    var radioWinner = states.saveRadio(radio(radio.version(), 2));

    assertThatThrownBy(() -> states.saveQueue(queue(staleQueue.version(), "stale")))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> states.saveRadio(radio(staleRadio.version(), 99)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(states.findQueue()).contains(queueWinner);
    assertThat(states.findRadio()).contains(radioWinner);
  }

  @Test
  void sameLegacyIdIsIsolatedAcrossMusicKinds() {
    var tracks = new MongoMusicTrackRepository(factory);
    var playlists = new MongoMusicPlaylistRepository(factory);
    var sharedId = "shared-id";
    var track = new MusicTrack(
        sharedId, "folder/track.mp3", "token", null, "Track", "Artist", "Artist", "Album",
        1, 1, "Genre", 2026, 90, "mp3", "mp3", null, false, false,
        MusicIndexStatus.READY, null, Instant.EPOCH, Instant.EPOCH, null);
    var playlist = new MusicPlaylist(
        sharedId, "playlist", "Playlist", List.of(sharedId), null, "account-1", Instant.EPOCH);

    tracks.save(track);
    var savedPlaylist = playlists.save(playlist);

    assertThat(tracks.findById(sharedId)).contains(track);
    assertThat(playlists.findById(sharedId)).contains(savedPlaylist);
    assertThat(mongo.getCollection("music").countDocuments()).isEqualTo(2);
  }

  @Test
  void restaurantSparseUniqueTranslationExcludesAbsentButIncludesNullAndIsKindScoped() {
    createIndex(index("restaurant", "restaurant__normalizedName_asc"));
    var collection = mongo.getCollection("whatsforlunch");
    collection.insertOne(envelope("restaurant", "absent-1", new Document("name", "Absent 1")));
    collection.insertOne(envelope("restaurant", "absent-2", new Document("name", "Absent 2")));
    collection.insertOne(envelope(
        "restaurant", "null-1", new Document("name", "Null 1").append("normalizedName", null)));
    assertThatThrownBy(() -> collection.insertOne(envelope(
        "restaurant", "null-2", new Document("name", "Null 2").append("normalizedName", null))))
        .isInstanceOf(com.mongodb.MongoWriteException.class);
    collection.insertOne(envelope(
        "vote", "foreign", new Document("normalizedName", null)
            .append("restaurantId", "restaurant-1").append("accountId", "account-1")));

    var restaurants = new MongoRestaurantRepository(factory);
    var named = Restaurant.builder().id("named-1").name("Alpha").normalizedName("alpha").build();
    restaurants.save(named);
    assertThatThrownBy(() -> restaurants.save(
        Restaurant.builder().id("named-2").name("Alpha 2").normalizedName("alpha").build()))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void previewClaimIsSingleUseAndManifestRetainsExpiryTtl() {
    var store = new RestaurantImportPreviewStore(factory);
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var preview = RestaurantImportPreviewDocument.builder()
        .id("token-1")
        .actorAccountId("account-1")
        .checksum("checksum")
        .createdOn(now)
        .expiresOn(now.plusSeconds(600))
        .counts(new RestaurantImportPreviewCounts(1, 0, 0, 0, 0, 0))
        .build();
    store.save(preview);

    assertThat(store.claim("token-1", "account-1", now.plusSeconds(1))).isPresent();
    assertThat(store.claim("token-1", "account-1", now.plusSeconds(2))).isEmpty();

    var ttl = index("import_preview", "import_preview__restaurant_import_preview_expiry");
    assertThat(ttl.expireAfterSeconds()).contains(0L);
    assertThat(ttl.keys()).extracting(DomainCollectionManifest.IndexKey::path)
        .containsExactly("payload.expiresOn");
  }

  @Test
  void voteIdentityIsUniqueWithinItsKindAndDoesNotCollideWithAnotherKind() {
    createIndex(index("vote", "vote__restaurant_account_unique"));
    var votes = new MongoRestaurantVoteRepository(factory);
    votes.save(vote("vote-1", "restaurant-1", "account-1"));

    assertThatThrownBy(() -> votes.save(vote("vote-2", "restaurant-1", "account-1")))
        .isInstanceOf(DuplicateKeyException.class);
    mongo.getCollection("whatsforlunch").insertOne(envelope(
        "restaurant", "foreign-vote-fields",
        new Document("restaurantId", "restaurant-1").append("accountId", "account-1")));
  }

  @Test
  void sessionMutationAdvancesRevisionAndRejectsAStaleReset() {
    var repository = new MongoWhatsForLunchSessionRepository(factory);
    var mutations = new WhatsForLunchSessionMutationStore(factory, repository);
    var now = Instant.parse("2026-08-10T20:00:00Z");
    repository.save(WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-1")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-1"))
        .participantUsernamesByAccountId(Map.of("owner-1", "owner"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .votesByAccountId(Map.of())
        .revision(7)
        .activeUntil(now.plusSeconds(600))
        .deleteOn(now.plusSeconds(1_200))
        .restaurantResetAudit(List.of())
        .build());

    var joined = mutations.join("session-1", "friend-1", "friend", now, 20);
    assertThat(joined.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    assertThat(joined.session().getRevision()).isEqualTo(8);

    var voted = mutations.vote("session-1", "friend-1", "restaurant-1", now.plusSeconds(1));
    assertThat(voted.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    assertThat(voted.session().getRevision()).isEqualTo(9);
    assertThat(voted.session().getVotesByAccountId())
        .containsEntry("friend-1", "restaurant-1");

    var reset = mutations.resetRestaurants(
        "session-1", "owner-1", "owner",
        new WhatsForLunchSessionRestaurantsRequest(
            List.of("restaurant-4", "restaurant-5", "restaurant-6"), 9),
        now.plusSeconds(2));
    assertThat(reset.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    assertThat(reset.session().getRevision()).isEqualTo(10);
    assertThat(reset.session().getRestaurantIds())
        .containsExactly("restaurant-4", "restaurant-5", "restaurant-6");
    assertThat(reset.session().getVotesByAccountId()).isEmpty();

    var stale = mutations.resetRestaurants(
        "session-1", "owner-1", "owner",
        new WhatsForLunchSessionRestaurantsRequest(
            List.of("restaurant-7", "restaurant-8", "restaurant-9"), 9),
        now.plusSeconds(3));
    assertThat(stale.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.CHANGED);
    assertThat(stale.session().getRevision()).isEqualTo(10);
  }

  @Test
  void allMusicRepositoryPortsPreserveQueriesOrderingUpdatesAndDeletes() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var tracks = new MongoMusicTrackRepository(factory);
    var firstTrack = track("track-1", "b.mp3", "Beta", now);
    var secondTrack = track("track-2", "a.mp3", "Alpha", now);
    tracks.save(firstTrack);
    tracks.save(secondTrack);
    assertThat(tracks.findByPath("a.mp3")).contains(secondTrack);
    assertThat(tracks.findAllByMissingSinceIsNull()).hasSize(2);
    assertThat(tracks.updatePreferences("track-1", false, false, true, true)).isTrue();
    assertThat(tracks.findById("track-1")).get().satisfies(updated -> {
      assertThat(updated.favorite()).isTrue();
      assertThat(updated.excludedFromRadio()).isTrue();
    });

    var playlists = new MongoMusicPlaylistRepository(factory);
    var beta = playlists.save(new MusicPlaylist(
        "playlist-b", "beta", "Beta", List.of(), null, "account-1", now));
    playlists.save(new MusicPlaylist(
        "playlist-a", "alpha", "Alpha", List.of(), null, "account-1", now));
    assertThat(playlists.count()).isEqualTo(2);
    assertThat(playlists.findTop100ByOrderByNormalizedNameAsc())
        .extracting(MusicPlaylist::normalizedName).containsExactly("alpha", "beta");
    playlists.delete(beta);
    assertThat(playlists.findById(beta.id())).isEmpty();

    var edits = new MongoMusicMetadataEditRepository(factory);
    var expired = edits.save(new MusicMetadataEdit(
        "edit-1", "track-1", "source", "backup", "hash", "old", "new", "mp3", 90,
        "account-1", now.minusSeconds(100), now.minusSeconds(1),
        MusicMetadataEdit.Status.APPLIED, null, null));
    assertThat(edits.findById(expired.id())).contains(expired);
    assertThat(edits.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(now)).containsExactly(expired);
    edits.delete(expired);
    assertThat(edits.findById(expired.id())).isEmpty();
    var deleteById = edits.save(new MusicMetadataEdit(
        "edit-2", "track-2", "source", "backup", "hash-2", "old", "new", "mp3", 90,
        "account-1", now.minusSeconds(100), now.minusSeconds(1),
        MusicMetadataEdit.Status.APPLIED, null, null));
    edits.deleteById(deleteById.id());
    assertThat(edits.findById(deleteById.id())).isEmpty();

    var history = new MongoMusicRadioHistoryRepository(factory);
    var older = new MusicRadioHistoryEvent(
        "history-1", 1, "track-1", "token", "Alpha", MusicRadioState.Source.RADIO,
        MusicRadioHistoryEvent.Outcome.PLAYED, now.minusSeconds(10));
    var newer = new MusicRadioHistoryEvent(
        "history-2", 2, "track-2", "token", "Beta", MusicRadioState.Source.RADIO,
        MusicRadioHistoryEvent.Outcome.PLAYED, now);
    history.save(older);
    history.save(newer);
    assertThat(history.existsById("history-1")).isTrue();
    assertThat(history.findTop100ByOrderByStationSequenceDesc())
        .extracting(MusicRadioHistoryEvent::id).containsExactly("history-2", "history-1");
  }

  @Test
  void musicManualReadModelsUseOnlyTheMusicKinds() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var tracks = new MongoMusicTrackRepository(factory);
    tracks.save(track("track-1", "a.mp3", "Alpha", now));
    tracks.save(track("track-2", "b.mp3", "Beta", now));
    var catalog = new MusicCatalog(new MongoMusicCatalogQueryRepository(factory), tracks);

    var result = catalog.search(new MusicQuery(null, null, null, null, null, null, 0, 50));
    assertThat(result.tracks()).extracting(MusicTrack::id).containsExactly("track-1", "track-2");
    assertThat(result.facets().artists()).containsExactly("Alpha", "Beta");
    assertThat(catalog.radioCandidates(1)).extracting(MusicTrack::id)
        .containsExactly("track-1");

    var attempts = new MongoMusicAccessAttemptRepository(factory);
    var recorder = new MusicAccessAuditRecorder(attempts);
    var attempt = recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");
    assertThat(attempt.count()).isEqualTo(1);
    assertThat(new MusicAccessAuditQueryService(attempts).recent(100)).contains(attempt);
  }

  @Test
  void allLunchRepositoryPortsAndBoundedReadModelsPreserveTheirContracts() {
    var now = Instant.parse("2026-08-10T20:00:00Z");
    var restaurants = new MongoRestaurantRepository(factory);
    var alpha1 = restaurant("restaurant-a1", "Alpha", "alpha");
    var alpha2 = restaurant("restaurant-a2", "Alpha Two", "alpha");
    var beta = restaurant("restaurant-b", "Beta", "beta");
    beta.setAddress(Address.builder().latitude(30.25).longitude(-97.75).build());
    restaurants.save(alpha1);
    restaurants.save(alpha2);
    restaurants.save(beta);
    assertThat(restaurants.findById(alpha1.getId())).contains(alpha1);
    assertThat(restaurants.findAll()).hasSize(3);
    assertThat(restaurants.count()).isEqualTo(3);
    assertThat(restaurants.findByNormalizedName(alpha1.getNormalizedName())).contains(alpha1);
    assertThat(restaurants.findByDedupeKeyIn(List.of("alpha"))).hasSize(2);
    assertThat(restaurants.findAll(PageRequest.of(0, 2))).hasSize(2);
    assertThat(restaurants.findAllById(List.of(alpha1.getId(), beta.getId()))).hasSize(2);
    assertThat(restaurants.findByCoordinateBounds(30.0, 30.5, -98.0, -97.5))
        .containsExactly(beta);

    var inventory = new RestaurantInventoryQueryRepository(factory);
    assertThat(inventory.find("alpha", null, null, null, 10).items()).hasSize(2);
    var firstPage = inventory.find(null, null, null, null, 1);
    var secondPage = inventory.find(null, null, null, firstPage.nextCursor(), 1);
    assertThat(firstPage.nextCursor()).isNotBlank();
    assertThat(secondPage.items()).extracting(Restaurant::getId)
        .doesNotContain(firstPage.items().getFirst().getId());
    var duplicates = new RestaurantDuplicateQueryRepository(factory);
    assertThat(duplicates.find(null, 10).keys()).containsExactly("alpha");

    var votes = new MongoRestaurantVoteRepository(factory);
    var vote1 = votes.save(vote("vote-1", alpha1.getId(), "account-1"));
    votes.save(vote("vote-2", alpha1.getId(), "account-2"));
    assertThat(votes.findByRestaurantIdIn(List.of(alpha1.getId()))).hasSize(2);
    assertThat(votes.findByRestaurantIdAndAccountId(alpha1.getId(), "account-1"))
        .contains(vote1);
    assertThat(new RestaurantVoteQueryRepository(factory).topLiked(10))
        .singleElement().extracting(summary -> summary.restaurantId()).isEqualTo(alpha1.getId());
    assertThat(new RestaurantVoteQueryRepository(factory)
        .summariesForRestaurants(List.of(alpha1.getId(), beta.getId())))
        .singleElement().satisfies(summary -> {
          assertThat(summary.restaurantId()).isEqualTo(alpha1.getId());
          assertThat(summary.upVotes()).isEqualTo(2);
          assertThat(summary.voteCount()).isEqualTo(2);
        });
    votes.deleteById(vote1.getId());
    assertThat(votes.findById(vote1.getId())).isEmpty();

    var favorites = new MongoRestaurantFavoriteRepository(factory);
    var favorite = favorites.save(RestaurantFavorite.builder()
        .id("favorite-1").restaurantId(beta.getId()).accountId("account-1").createdOn(now).build());
    assertThat(favorites.findByAccountIdOrderByCreatedOnDesc("account-1")).contains(favorite);
    assertThat(favorites.findByRestaurantIdInAndAccountId(List.of(beta.getId()), "account-1"))
        .contains(favorite);
    favorites.deleteByRestaurantIdAndAccountId(beta.getId(), "account-1");
    assertThat(favorites.findByRestaurantIdAndAccountId(beta.getId(), "account-1")).isEmpty();

    var preferences = new MongoWhatsForLunchPreferenceRepository(factory);
    var preference = preferences.save(WhatsForLunchPreference.builder()
        .accountId("account-1").cuisines(List.of("thai")).radiusMiles(10).build());
    assertThat(preferences.findById("account-1")).contains(preference);

    var sessions = new MongoWhatsForLunchSessionRepository(factory);
    sessions.save(session("session-port", now));
    assertThat(sessions.findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
        "owner-1", now, PageRequest.of(0, 10))).extracting(WhatsForLunchSession::getId)
        .containsExactly("session-port");

    var daily = new MongoDailyLunchPicksRepository(factory);
    var picks = daily.save(DailyLunchPicks.builder()
        .id("2026-08-10").pickDate("2026-08-10").restaurantIds(List.of(beta.getId()))
        .generatedOn(now).build());
    assertThat(daily.findById(picks.getId())).contains(picks);

    var importStates = new MongoRestaurantImportStateRepository(factory);
    assertThat(importStates.findById("osm")).isEmpty();
    var importState = importStates.save(RestaurantImportState.builder().id("osm").build());
    assertThat(importStates.findById("osm")).contains(importState);

    restaurants.delete(alpha2);
    restaurants.deleteAll(List.of(alpha1, beta));
    assertThat(restaurants.findAll()).isEmpty();
  }

  @Test
  void generatedAuditedRestaurantIdsRemainStableAcrossCursorContinuation() {
    var now = new AtomicReference<>(Instant.parse("2026-08-10T20:00:00Z"));
    var handler = IsNewAwareAuditingHandler.from(mongo.getConverter().getMappingContext());
    handler.setDateTimeProvider(() -> Optional.of(now.get()));
    var beans = new StaticListableBeanFactory();
    beans.addBean("auditingEntityCallback", new AuditingEntityCallback(() -> handler));
    var auditedFactory = new DomainMongoOperationsFactory(mongo, beans);
    var restaurants = new MongoRestaurantRepository(auditedFactory);
    var inventory = new RestaurantInventoryQueryRepository(auditedFactory);

    var alpha = restaurants.save(Restaurant.builder()
        .name("Alpha").normalizedName("generated-alpha").dedupeKey("alpha").build());
    now.set(now.get().plusSeconds(1));
    var beta = restaurants.save(Restaurant.builder()
        .name("Beta").normalizedName("generated-beta").dedupeKey("beta").build());

    assertThat(alpha.getId()).matches("[0-9a-f]{24}");
    assertThat(beta.getId()).matches("[0-9a-f]{24}");
    assertThat(alpha.getCreatedOn()).isEqualTo(Instant.parse("2026-08-10T20:00:00Z"));
    assertThat(beta.getCreatedOn()).isEqualTo(Instant.parse("2026-08-10T20:00:01Z"));
    var first = inventory.find(null, null, null, null, 1);
    var second = inventory.find(null, null, null, first.nextCursor(), 1);
    assertThat(first.items()).extracting(Restaurant::getId).containsExactly(alpha.getId());
    assertThat(second.items()).extracting(Restaurant::getId).containsExactly(beta.getId());
  }

  @Test
  void remainingMusicAndLunchIndexesEnforceUniquenessAndPreviewTtlMetadata() {
    var trackIndex = indexByKey("music_track", "payload.path");
    var playlistIndex = indexByKey("music_playlist", "payload.normalizedName");
    var favoriteIndex = indexByKey("favorite", "payload.accountId");
    var previewTtl = index("import_preview", "import_preview__restaurant_import_preview_expiry");
    createIndex(trackIndex);
    createIndex(playlistIndex);
    createIndex(favoriteIndex);
    createIndex(previewTtl);
    var now = Instant.parse("2026-08-10T20:00:00Z");

    var tracks = new MongoMusicTrackRepository(factory);
    tracks.save(track("track-1", "same.mp3", "Alpha", now));
    assertThatThrownBy(() -> tracks.save(track("track-2", "same.mp3", "Beta", now)))
        .isInstanceOf(DuplicateKeyException.class);

    var playlists = new MongoMusicPlaylistRepository(factory);
    playlists.save(new MusicPlaylist(
        "playlist-1", "same", "Same", List.of(), null, "account-1", now));
    assertThatThrownBy(() -> playlists.save(new MusicPlaylist(
        "playlist-2", "same", "Same 2", List.of(), null, "account-1", now)))
        .isInstanceOf(DuplicateKeyException.class);

    var favorites = new MongoRestaurantFavoriteRepository(factory);
    favorites.save(RestaurantFavorite.builder().id("favorite-1")
        .restaurantId("restaurant-1").accountId("account-1").createdOn(now).build());
    assertThatThrownBy(() -> favorites.save(RestaurantFavorite.builder().id("favorite-2")
        .restaurantId("restaurant-1").accountId("account-1").createdOn(now).build()))
        .isInstanceOf(DuplicateKeyException.class);

    var actualTtl = mongo.getCollection("whatsforlunch").listIndexes().into(new java.util.ArrayList<>())
        .stream().filter(document -> previewTtl.name().equals(document.getString("name")))
        .findFirst().orElseThrow();
    assertThat(actualTtl.get("expireAfterSeconds", Number.class).longValue()).isZero();
    assertThat(actualTtl.get("key", Document.class))
        .isEqualTo(new Document("payload.expiresOn", 1));
  }

  private void createIndex(DomainCollectionManifest.IndexDefinition definition) {
    var keys = new Document();
    definition.keys().forEach(key -> keys.append(key.path(), key.direction()));
    var options = new IndexOptions().name(definition.name()).unique(definition.unique());
    if (!definition.partialFilterExpression().isEmpty()) {
      options.partialFilterExpression(new Document(definition.partialFilterExpression()));
    }
    definition.expireAfterSeconds().ifPresent(seconds ->
        options.expireAfter(seconds, java.util.concurrent.TimeUnit.SECONDS));
    mongo.getCollection(definition.collection()).createIndex(keys, options);
  }

  private static DomainCollectionManifest.IndexDefinition index(String kind, String name) {
    return DomainCollectionManifest.forKind(kind).orElseThrow().indexes().stream()
        .filter(index -> index.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static DomainCollectionManifest.IndexDefinition indexByKey(String kind, String path) {
    return DomainCollectionManifest.forKind(kind).orElseThrow().indexes().stream()
        .filter(index -> index.keys().stream().anyMatch(key -> key.path().equals(path)))
        .findFirst()
        .orElseThrow();
  }

  private static Document envelope(String kind, String id, Document payload) {
    return new Document("_id", new Document("kind", kind).append("legacyId", id))
        .append("_kind", kind)
        .append("schemaVersion", 1)
        .append("payload", payload);
  }

  private static RestaurantVote vote(String id, String restaurantId, String accountId) {
    return RestaurantVote.builder()
        .id(id)
        .restaurantId(restaurantId)
        .accountId(accountId)
        .vote(RestaurantVoteValue.UP)
        .createdOn(Instant.EPOCH)
        .lastUpdatedOn(Instant.EPOCH)
        .build();
  }

  private static MusicTrack track(String id, String path, String artist, Instant now) {
    return new MusicTrack(
        id, path, "token-" + id, null, id, artist, artist, "Album", 1, 1, "Genre", 2026,
        90, "mp3", "mp3", null, false, false, MusicIndexStatus.READY, null, now, now, null);
  }

  private static Restaurant restaurant(String id, String name, String dedupeKey) {
    return Restaurant.builder()
        .id(id).name(name).normalizedName(id).dedupeKey(dedupeKey)
        .searchCity("austin").searchState("tx").build();
  }

  private static WhatsForLunchSession session(String id, Instant now) {
    return WhatsForLunchSession.builder()
        .id(id)
        .createdByAccountId("owner-1")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-1"))
        .participantUsernamesByAccountId(Map.of("owner-1", "owner"))
        .restaurantIds(List.of("restaurant-a1"))
        .votesByAccountId(Map.of())
        .revision(0)
        .activeUntil(now.plusSeconds(600))
        .deleteOn(now.plusSeconds(1_200))
        .restaurantResetAudit(List.of())
        .createdOn(now)
        .lastUpdatedOn(now)
        .build();
  }

  private static MusicQueueState queue(Long version, String entryId) {
    return new MusicQueueState(
        MusicQueueState.ID,
        List.of(new MusicQueueState.Entry(
            entryId, "track-1", "token-1", "account-1", Instant.EPOCH)),
        version);
  }

  private static MusicRadioState radio(Long version, long sequence) {
    return new MusicRadioState(
        MusicRadioState.ID, sequence, "track-1", "token-1", Instant.EPOCH, 90,
        MusicRadioState.Source.RADIO, null, version);
  }
}
