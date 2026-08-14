package dev.christopherbell.configuration.postgresql;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.persistence.PostgresApplicationLeaseStore;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.libs.lease.LeaseGrant;
import static dev.christopherbell.persistence.jooq.shared_folder.Tables.MAINTENANCE_LEASE;
import java.time.Instant;
import java.time.ZoneOffset;
import dev.christopherbell.music.catalog.MusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.music.catalog.PostgresMusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.PostgresMusicTrackRepository;
import dev.christopherbell.music.library.MusicPlaylistRepository;
import dev.christopherbell.music.library.PostgresMusicPlaylistRepository;
import dev.christopherbell.music.metadata.MusicMetadataEditRepository;
import dev.christopherbell.music.metadata.PostgresMusicMetadataEditRepository;
import dev.christopherbell.music.radio.MusicRadioHistoryRepository;
import dev.christopherbell.music.radio.MusicRuntimeStateRepository;
import dev.christopherbell.music.radio.PostgresMusicRadioHistoryRepository;
import dev.christopherbell.music.radio.PostgresMusicRuntimeStateRepository;
import dev.christopherbell.music.security.MusicAccessAttemptRepository;
import dev.christopherbell.music.security.PostgresMusicAccessAttemptRepository;
import dev.christopherbell.sharedfolder.audit.PostgresSharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.maintenance.PostgresSharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.media.MediaJobRepository;
import dev.christopherbell.sharedfolder.media.PostgresMediaJobRepository;
import dev.christopherbell.sharedfolder.radio.PostgresSharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.recycle.PostgresSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.service.PostgresSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.upload.PostgresSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** PostgreSQL runner for the identical Task 4 transition-backend contract. */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresTask4ParityContractTest implements Task4PersistenceParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static Task3PostgresqlTestSupport.Database contenderDatabase;
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
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    contenderDatabase = schemas.openDatabase();
    new PostgresAccountRepository(database.dsl()).save(Account.builder()
        .id(OWNER_ID).createdOn(FIXTURE_TIME).email("task4-parity@example.test")
        .passwordHash("hash").role(Role.USER).status(AccountStatus.ACTIVE)
        .username("task4-parity-owner").build());
    applicationLeases = new PostgresApplicationLeaseStore(database.dsl());
    tracks = new PostgresMusicTrackRepository(database.dsl());
    catalog = new PostgresMusicCatalogQueryRepository(database.dsl());
    playlists = new PostgresMusicPlaylistRepository(database.dsl());
    metadataEdits = new PostgresMusicMetadataEditRepository(database.dsl());
    radioHistory = new PostgresMusicRadioHistoryRepository(database.dsl());
    runtimeState = new PostgresMusicRuntimeStateRepository(database.dsl());
    accessAttempts = new PostgresMusicAccessAttemptRepository(database.dsl());
    audits = new PostgresSharedFolderAuditRepository(database.dsl());
    maintenanceLeases = new PostgresSharedFolderMaintenanceLeaseStore(database.dsl());
    maintenanceLeaseContender =
        new PostgresSharedFolderMaintenanceLeaseStore(contenderDatabase.dsl());
    mediaJobs = new PostgresMediaJobRepository(database.dsl());
    recoveries = new PostgresSharedFolderMutationRecoveryRepository(database.dsl());
    recoveryContender =
        new PostgresSharedFolderMutationRecoveryRepository(contenderDatabase.dsl());
    sharedRadio = new PostgresSharedFolderRadioRepository(database.dsl());
    recycleItems = new PostgresSharedFolderRecycleRepository(database.dsl());
    uploadSessions = new PostgresSharedFolderUploadSessionRepository(database.dsl());
    uploadSessionContender =
        new PostgresSharedFolderUploadSessionRepository(contenderDatabase.dsl());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (contenderDatabase != null) contenderDatabase.close();
    if (database != null) database.close();
    if (schemas != null) schemas.close();
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
    database.dsl().update(MAINTENANCE_LEASE)
        .set(MAINTENANCE_LEASE.EXPIRES_AT, Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .where(MAINTENANCE_LEASE.LEASE_NAME.eq(grant.leaseName())
            .and(MAINTENANCE_LEASE.FENCE_TOKEN.eq(grant.fenceToken())))
        .execute();
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
