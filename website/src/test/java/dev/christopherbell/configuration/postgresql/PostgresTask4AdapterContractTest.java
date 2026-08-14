package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresApplicationLeaseStore;
import dev.christopherbell.libs.lease.LeaseStore;
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
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exact Task 4 PostgreSQL adapter inventory; behavior is exercised by the real-engine suite. */
class PostgresTask4AdapterContractTest {
  private static final Map<Class<?>, Class<?>> TASK4_ADAPTERS = Map.ofEntries(
      Map.entry(PostgresMusicTrackRepository.class, MusicTrackRepository.class),
      Map.entry(PostgresMusicPlaylistRepository.class, MusicPlaylistRepository.class),
      Map.entry(PostgresMusicMetadataEditRepository.class, MusicMetadataEditRepository.class),
      Map.entry(PostgresMusicRadioHistoryRepository.class, MusicRadioHistoryRepository.class),
      Map.entry(PostgresMusicRuntimeStateRepository.class, MusicRuntimeStateRepository.class),
      Map.entry(PostgresMusicAccessAttemptRepository.class, MusicAccessAttemptRepository.class),
      Map.entry(PostgresMusicCatalogQueryRepository.class, MusicCatalogQueryRepository.class),
      Map.entry(PostgresApplicationLeaseStore.class, LeaseStore.class),
      Map.entry(PostgresSharedFolderAuditRepository.class, SharedFolderAuditRepository.class),
      Map.entry(PostgresSharedFolderMaintenanceLeaseStore.class,
          SharedFolderMaintenanceLeaseStore.class),
      Map.entry(PostgresMediaJobRepository.class, MediaJobRepository.class),
      Map.entry(PostgresSharedFolderMutationRecoveryRepository.class,
          SharedFolderMutationRecoveryRepository.class),
      Map.entry(PostgresSharedFolderRadioRepository.class, SharedFolderRadioRepository.class),
      Map.entry(PostgresSharedFolderRecycleRepository.class, SharedFolderRecycleRepository.class),
      Map.entry(PostgresSharedFolderUploadSessionRepository.class,
          SharedFolderUploadSessionRepository.class));

  private static final Map<Class<?>, String> PARITY_ACCESSOR_BY_ADAPTER = Map.ofEntries(
      Map.entry(PostgresMusicTrackRepository.class, "tracks"),
      Map.entry(PostgresMusicPlaylistRepository.class, "playlists"),
      Map.entry(PostgresMusicMetadataEditRepository.class, "metadataEdits"),
      Map.entry(PostgresMusicRadioHistoryRepository.class, "radioHistory"),
      Map.entry(PostgresMusicRuntimeStateRepository.class, "runtimeState"),
      Map.entry(PostgresMusicAccessAttemptRepository.class, "accessAttempts"),
      Map.entry(PostgresMusicCatalogQueryRepository.class, "catalog"),
      Map.entry(PostgresApplicationLeaseStore.class, "applicationLeases"),
      Map.entry(PostgresSharedFolderAuditRepository.class, "audits"),
      Map.entry(PostgresSharedFolderMaintenanceLeaseStore.class, "maintenanceLeases"),
      Map.entry(PostgresMediaJobRepository.class, "mediaJobs"),
      Map.entry(PostgresSharedFolderMutationRecoveryRepository.class, "recoveries"),
      Map.entry(PostgresSharedFolderRadioRepository.class, "sharedRadio"),
      Map.entry(PostgresSharedFolderRecycleRepository.class, "recycleItems"),
      Map.entry(PostgresSharedFolderUploadSessionRepository.class, "uploadSessions"));

  private static final Map<Class<?>, String> MONGO_ADAPTER_BY_POSTGRES_ADAPTER = Map.ofEntries(
      Map.entry(PostgresMusicTrackRepository.class,
          "dev.christopherbell.music.catalog.MongoMusicTrackRepository"),
      Map.entry(PostgresMusicPlaylistRepository.class,
          "dev.christopherbell.music.library.MongoMusicPlaylistRepository"),
      Map.entry(PostgresMusicMetadataEditRepository.class,
          "dev.christopherbell.music.metadata.MongoMusicMetadataEditRepository"),
      Map.entry(PostgresMusicRadioHistoryRepository.class,
          "dev.christopherbell.music.radio.MongoMusicRadioHistoryRepository"),
      Map.entry(PostgresMusicRuntimeStateRepository.class,
          "dev.christopherbell.music.radio.MongoMusicRuntimeStateRepository"),
      Map.entry(PostgresMusicAccessAttemptRepository.class,
          "dev.christopherbell.music.security.MongoMusicAccessAttemptRepository"),
      Map.entry(PostgresMusicCatalogQueryRepository.class,
          "dev.christopherbell.music.catalog.MongoMusicCatalogQueryRepository"),
      Map.entry(PostgresApplicationLeaseStore.class,
          "dev.christopherbell.configuration.mongo.runtime.MongoApplicationLeaseStore"),
      Map.entry(PostgresSharedFolderAuditRepository.class,
          "dev.christopherbell.sharedfolder.audit.MongoSharedFolderAuditRepository"),
      Map.entry(PostgresSharedFolderMaintenanceLeaseStore.class,
          "dev.christopherbell.sharedfolder.maintenance.MongoSharedFolderMaintenanceLeaseStore"),
      Map.entry(PostgresMediaJobRepository.class,
          "dev.christopherbell.sharedfolder.media.MongoMediaJobRepository"),
      Map.entry(PostgresSharedFolderMutationRecoveryRepository.class,
          "dev.christopherbell.sharedfolder.service.MongoSharedFolderMutationRecoveryRepository"),
      Map.entry(PostgresSharedFolderRadioRepository.class,
          "dev.christopherbell.sharedfolder.radio.MongoSharedFolderRadioRepository"),
      Map.entry(PostgresSharedFolderRecycleRepository.class,
          "dev.christopherbell.sharedfolder.recycle.MongoSharedFolderRecycleRepository"),
      Map.entry(PostgresSharedFolderUploadSessionRepository.class,
          "dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository"));

  @Test
  void everyTaskFourAdapterIsSelectedAndImplementsItsPort() {
    TASK4_ADAPTERS.forEach((adapter, port) -> {
      assertThat(port.isAssignableFrom(adapter)).as(adapter.getName()).isTrue();
      assertThat(adapter.isAnnotationPresent(PostgresPersistence.class))
          .as(adapter.getName()).isTrue();
    });
  }

  @Test
  void everyManifestEntryHasMongoAndPostgresqlRealEngineRunnerAccessors() throws Exception {
    assertThat(PARITY_ACCESSOR_BY_ADAPTER.keySet())
        .containsExactlyInAnyOrderElementsOf(TASK4_ADAPTERS.keySet());
    assertThat(MONGO_ADAPTER_BY_POSTGRES_ADAPTER.keySet())
        .containsExactlyInAnyOrderElementsOf(TASK4_ADAPTERS.keySet());

    for (var entry : TASK4_ADAPTERS.entrySet()) {
      Class<?> postgresAdapter = entry.getKey();
      Class<?> port = entry.getValue();
      Class<?> mongoAdapter = Class.forName(MONGO_ADAPTER_BY_POSTGRES_ADAPTER.get(postgresAdapter));
      String accessor = PARITY_ACCESSOR_BY_ADAPTER.get(postgresAdapter);
      assertThat(port.isAssignableFrom(mongoAdapter)).as(mongoAdapter.getName()).isTrue();
      assertThat(mongoAdapter.isAnnotationPresent(MongoPersistence.class))
          .as(mongoAdapter.getName()).isTrue();
      assertThat(Task4PersistenceParityContract.class.getDeclaredMethod(accessor).getReturnType())
          .as(accessor).isEqualTo(port);
      assertThat(PostgresTask4ParityContractTest.class.getDeclaredMethod(accessor).getReturnType())
          .as("PostgreSQL " + accessor).isEqualTo(port);
      assertThat(MongoTask4ParityContractTest.class.getDeclaredMethod(accessor).getReturnType())
          .as("MongoDB " + accessor).isEqualTo(port);
    }
  }
}
