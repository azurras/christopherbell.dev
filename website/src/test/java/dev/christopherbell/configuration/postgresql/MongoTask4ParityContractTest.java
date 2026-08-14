package dev.christopherbell.configuration.postgresql;

import com.mongodb.ConnectionString;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.mongo.runtime.MongoApplicationLeaseStore;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.libs.lease.LeaseGrant;
import java.time.Instant;
import dev.christopherbell.music.catalog.MongoMusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.MongoMusicTrackRepository;
import dev.christopherbell.music.catalog.MusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.music.library.MongoMusicPlaylistRepository;
import dev.christopherbell.music.library.MusicPlaylistRepository;
import dev.christopherbell.music.metadata.MongoMusicMetadataEditRepository;
import dev.christopherbell.music.metadata.MusicMetadataEditRepository;
import dev.christopherbell.music.radio.MongoMusicRadioHistoryRepository;
import dev.christopherbell.music.radio.MongoMusicRuntimeStateRepository;
import dev.christopherbell.music.radio.MusicRadioHistoryRepository;
import dev.christopherbell.music.radio.MusicRuntimeStateRepository;
import dev.christopherbell.music.security.MongoMusicAccessAttemptRepository;
import dev.christopherbell.music.security.MusicAccessAttemptRepository;
import dev.christopherbell.sharedfolder.audit.MongoSharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.maintenance.MongoSharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.media.MediaJobRepository;
import dev.christopherbell.sharedfolder.media.MongoMediaJobRepository;
import dev.christopherbell.sharedfolder.radio.MongoSharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.recycle.MongoSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.service.MongoSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** MongoDB runner for the identical Task 4 transition-backend contract. */
@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoTask4ParityContractTest implements Task4PersistenceParityContract {
  private static MongoClient client;
  private static MongoClient contenderClient;
  private static MongoTemplate mongo;
  private static LeaseStore applicationLeases;
  private static MusicTrackRepository tracks;
  private static MusicCatalogQueryRepository catalog;
  private static MusicPlaylistRepository playlists;
  private static MusicMetadataEditRepository metadataEdits;
  private static MusicRadioHistoryRepository radioHistory;
  private static MusicRuntimeStateRepository runtimeState;
  private static MusicAccessAttemptRepository accessAttempts;
  private static SharedFolderAuditRepository audits;
  private static SharedFolderMaintenanceLeaseStore maintenanceLeases;
  private static SharedFolderMaintenanceLeaseStore maintenanceLeaseContender;
  private static MediaJobRepository mediaJobs;
  private static SharedFolderMutationRecoveryRepository recoveries;
  private static SharedFolderMutationRecoveryRepository recoveryContender;
  private static SharedFolderRadioRepository sharedRadio;
  private static SharedFolderRecycleRepository recycleItems;
  private static SharedFolderUploadSessionRepository uploadSessions;
  private static SharedFolderUploadSessionRepository uploadSessionContender;

  @BeforeAll
  static void connectToDisposableMongo() {
    var uri = System.getenv("SPRING_MONGODB_URI");
    var connection = new ConnectionString(uri);
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    mongo = new MongoTemplate(client, "test");
    for (String collection : List.of("music", "shared_folder", "application_runtime")) {
      mongo.getCollection(collection).deleteMany(new org.bson.Document());
    }
    var playlistIndex = DomainCollectionManifest.ALL_INDEXES.stream()
        .filter(index -> index.kind().orElse("").equals("music_playlist"))
        .filter(DomainCollectionManifest.IndexDefinition::unique)
        .findFirst().orElseThrow();
    var playlistKeys = new org.bson.Document();
    playlistIndex.keys().forEach(key -> playlistKeys.append(key.path(), key.direction()));
    mongo.getCollection(playlistIndex.collection()).createIndex(
        playlistKeys,
        new IndexOptions().name(playlistIndex.name()).unique(true)
            .partialFilterExpression(new org.bson.Document("_kind", "music_playlist")));
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
    applicationLeases = new MongoApplicationLeaseStore(factory);
    tracks = new MongoMusicTrackRepository(factory);
    catalog = new MongoMusicCatalogQueryRepository(factory);
    playlists = new MongoMusicPlaylistRepository(factory);
    metadataEdits = new MongoMusicMetadataEditRepository(factory);
    radioHistory = new MongoMusicRadioHistoryRepository(factory);
    runtimeState = new MongoMusicRuntimeStateRepository(factory);
    accessAttempts = new MongoMusicAccessAttemptRepository(factory);
    audits = new MongoSharedFolderAuditRepository(factory);
    maintenanceLeases = new MongoSharedFolderMaintenanceLeaseStore(factory);
    contenderClient = MongoClients.create(connection);
    var contenderFactory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(contenderClient, "test"));
    maintenanceLeaseContender = new MongoSharedFolderMaintenanceLeaseStore(contenderFactory);
    recoveryContender = new MongoSharedFolderMutationRecoveryRepository(contenderFactory);
    uploadSessionContender = new MongoSharedFolderUploadSessionRepository(contenderFactory);
    mediaJobs = new MongoMediaJobRepository(factory);
    recoveries = new MongoSharedFolderMutationRecoveryRepository(factory);
    sharedRadio = new MongoSharedFolderRadioRepository(factory);
    recycleItems = new MongoSharedFolderRecycleRepository(factory);
    uploadSessions = new MongoSharedFolderUploadSessionRepository(factory);
  }

  @AfterAll
  static void disconnect() {
    if (contenderClient != null) contenderClient.close();
    if (client != null) client.close();
  }

  @Override public LeaseStore applicationLeases() { return applicationLeases; }
  @Override public MusicTrackRepository tracks() { return tracks; }
  @Override public MusicCatalogQueryRepository catalog() { return catalog; }
  @Override public MusicPlaylistRepository playlists() { return playlists; }
  @Override public MusicMetadataEditRepository metadataEdits() { return metadataEdits; }
  @Override public MusicRadioHistoryRepository radioHistory() { return radioHistory; }
  @Override public MusicRuntimeStateRepository runtimeState() { return runtimeState; }
  @Override public MusicAccessAttemptRepository accessAttempts() { return accessAttempts; }
  @Override public SharedFolderAuditRepository audits() { return audits; }
  @Override public SharedFolderMaintenanceLeaseStore maintenanceLeases() { return maintenanceLeases; }
  @Override public SharedFolderMaintenanceLeaseStore maintenanceLeaseContender() {
    return maintenanceLeaseContender;
  }
  @Override public void expireMaintenanceLease(LeaseGrant grant) {
    var result = mongo.getCollection("shared_folder").updateOne(
        new org.bson.Document("_kind", "maintenance_lease")
            .append("_id.legacyId", grant.leaseName())
            .append("payload.fenceToken", grant.fenceToken()),
        new org.bson.Document("$set", new org.bson.Document(
            "payload.expiresAt", java.util.Date.from(Instant.EPOCH))));
    if (result.getModifiedCount() != 1) {
      throw new IllegalStateException("Mongo maintenance lease expiry fixture did not match.");
    }
  }
  @Override public MediaJobRepository mediaJobs() { return mediaJobs; }
  @Override public SharedFolderMutationRecoveryRepository recoveries() { return recoveries; }
  @Override public SharedFolderMutationRecoveryRepository recoveryContender() {
    return recoveryContender;
  }
  @Override public SharedFolderRadioRepository sharedRadio() { return sharedRadio; }
  @Override public SharedFolderRecycleRepository recycleItems() { return recycleItems; }
  @Override public SharedFolderUploadSessionRepository uploadSessions() { return uploadSessions; }
  @Override public SharedFolderUploadSessionRepository uploadSessionContender() {
    return uploadSessionContender;
  }
}
