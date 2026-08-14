package dev.christopherbell.configuration.postgresql;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.mongo.runtime.MongoApplicationLeaseStore;
import dev.christopherbell.libs.lease.LeaseStore;
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
  private static MediaJobRepository mediaJobs;
  private static SharedFolderMutationRecoveryRepository recoveries;
  private static SharedFolderRadioRepository sharedRadio;
  private static SharedFolderRecycleRepository recycleItems;
  private static SharedFolderUploadSessionRepository uploadSessions;

  @BeforeAll
  static void connectToDisposableMongo() {
    var uri = System.getenv("SPRING_MONGODB_URI");
    var connection = new ConnectionString(uri);
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var mongo = new MongoTemplate(client, "test");
    for (String collection : List.of("music", "shared_folder", "application_runtime")) {
      mongo.getCollection(collection).deleteMany(new org.bson.Document());
    }
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
    mediaJobs = new MongoMediaJobRepository(factory);
    recoveries = new MongoSharedFolderMutationRecoveryRepository(factory);
    sharedRadio = new MongoSharedFolderRadioRepository(factory);
    recycleItems = new MongoSharedFolderRecycleRepository(factory);
    uploadSessions = new MongoSharedFolderUploadSessionRepository(factory);
  }

  @AfterAll
  static void disconnect() {
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
  @Override public MediaJobRepository mediaJobs() { return mediaJobs; }
  @Override public SharedFolderMutationRecoveryRepository recoveries() { return recoveries; }
  @Override public SharedFolderRadioRepository sharedRadio() { return sharedRadio; }
  @Override public SharedFolderRecycleRepository recycleItems() { return recycleItems; }
  @Override public SharedFolderUploadSessionRepository uploadSessions() { return uploadSessions; }
}
