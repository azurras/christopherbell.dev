package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.music.catalog.MusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.MusicIndexStatus;
import dev.christopherbell.music.catalog.MusicQuery;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.music.library.MusicPlaylist;
import dev.christopherbell.music.library.MusicPlaylistRepository;
import dev.christopherbell.music.metadata.MusicMetadataEdit;
import dev.christopherbell.music.metadata.MusicMetadataEditRepository;
import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioHistoryEvent;
import dev.christopherbell.music.radio.MusicRadioHistoryRepository;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.MusicRuntimeStateRepository;
import dev.christopherbell.music.security.MusicAccessAttemptRepository;
import dev.christopherbell.music.security.MusicAccessPrincipalType;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.media.MediaJob;
import dev.christopherbell.sharedfolder.media.MediaJobRepository;
import dev.christopherbell.sharedfolder.media.MediaJobStatus;
import dev.christopherbell.sharedfolder.media.MediaOutputProfile;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleState;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

/** Identical Task 4 persistence assertions run against disposable MongoDB and PostgreSQL. */
interface Task4PersistenceParityContract {
  Instant FIXTURE_TIME = Instant.parse("2026-08-13T20:00:00Z");
  String OWNER_ID = "task4-parity-owner";

  LeaseStore applicationLeases();
  MusicTrackRepository tracks();
  MusicCatalogQueryRepository catalog();
  MusicPlaylistRepository playlists();
  MusicMetadataEditRepository metadataEdits();
  MusicRadioHistoryRepository radioHistory();
  MusicRuntimeStateRepository runtimeState();
  MusicAccessAttemptRepository accessAttempts();
  SharedFolderAuditRepository audits();
  SharedFolderMaintenanceLeaseStore maintenanceLeases();
  MediaJobRepository mediaJobs();
  SharedFolderMutationRecoveryRepository recoveries();
  SharedFolderRadioRepository sharedRadio();
  SharedFolderRecycleRepository recycleItems();
  SharedFolderUploadSessionRepository uploadSessions();

  @Test
  default void applicationLeasePreservesExclusiveOwnershipRenewalAndMonotonicFencing() {
    var first = applicationLeases().tryAcquire(
        "task4-parity-application", "owner-a", Duration.ofMinutes(1)).orElseThrow();
    assertThat(applicationLeases().tryAcquire(
        "task4-parity-application", "owner-b", Duration.ofMinutes(1))).isEmpty();
    assertThat(applicationLeases().renew(first, Duration.ofMinutes(2))).isPresent();
    assertThat(applicationLeases().release(first)).isTrue();

    var next = applicationLeases().tryAcquire(
        "task4-parity-application", "owner-b", Duration.ofMinutes(1)).orElseThrow();
    assertThat(next.fenceToken()).isGreaterThan(first.fenceToken());
    assertThat(applicationLeases().release(first)).isFalse();
  }

  @Test
  default void musicPortsPreserveQueriesOrderingVersionsAndBoundedCleanup() {
    tracks().save(track("task4-track-a", "task4/a.mp3", "Alpha", false));
    tracks().save(track("task4-track-b", "task4/b.mp3", "Beta", true));
    assertThat(tracks().findByPath("task4/a.mp3")).isPresent();
    assertThat(tracks().updatePreferences("task4-track-a", false, false, true, false)).isTrue();
    assertThat(catalog().search(
        new MusicQuery("Alpha", null, null, null, null, null, 0, 10)).tracks())
        .extracting(MusicTrack::id).containsExactly("task4-track-a");
    assertThat(catalog().radioCandidates(10)).extracting(MusicTrack::id)
        .containsExactly("task4-track-a");

    var playlist = playlists().save(new MusicPlaylist(
        "task4-playlist", "task4-playlist", "Task 4 Playlist",
        List.of("task4-track-b", "task4-track-a"), null, OWNER_ID, FIXTURE_TIME));
    assertThat(playlists().findById(playlist.id()).orElseThrow().trackIds())
        .containsExactly("task4-track-b", "task4-track-a");
    var changed = playlists().save(new MusicPlaylist(
        playlist.id(), playlist.normalizedName(), playlist.name(),
        List.of("task4-track-a", "task4-track-b"), playlist.version(), OWNER_ID,
        FIXTURE_TIME.plusSeconds(1)));
    assertThatThrownBy(() -> playlists().save(playlist))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(changed.trackIds()).containsExactly("task4-track-a", "task4-track-b");

    var edit = metadataEdits().save(metadataEdit());
    assertThat(metadataEdits().findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(
        FIXTURE_TIME.plusSeconds(3_601))).containsExactly(edit);

    radioHistory().save(history("task4-history-a", 1, "task4-track-a"));
    radioHistory().save(history("task4-history-b", 2, "task4-track-b"));
    assertThat(radioHistory().findTop100ByOrderByStationSequenceDesc())
        .extracting(MusicRadioHistoryEvent::id)
        .containsExactly("task4-history-b", "task4-history-a");

    var queue = runtimeState().saveQueue(queue(null, "task4-queue-a", "task4-track-a"));
    var radio = runtimeState().saveRadio(radio(null, "task4-track-a"));
    assertThat(runtimeState().findQueue()).contains(queue);
    assertThat(runtimeState().findRadio()).contains(radio);

    accessAttempts().record("task4-attempt", MusicAccessPrincipalType.IP, "127.0.0.1",
        "denied", FIXTURE_TIME, FIXTURE_TIME.plus(Duration.ofDays(30)));
    var aggregated = accessAttempts().record(
        "task4-attempt", MusicAccessPrincipalType.IP, "127.0.0.1", "denied",
        FIXTURE_TIME.plusSeconds(1), FIXTURE_TIME.plus(Duration.ofDays(30)).plusSeconds(1));
    assertThat(aggregated.count()).isEqualTo(2);
    assertThat(accessAttempts().recent(1)).containsExactly(aggregated);
    assertThat(accessAttempts().deleteExpired(FIXTURE_TIME.plus(Duration.ofDays(31)), 1)).isOne();
  }

  @Test
  default void sharedFolderPortsPreserveDurableIntentOrderingAndOwnershipQueries() {
    var audit = audits().save(new SharedFolderAuditEvent(
        "task4-audit", OWNER_ID, "DOWNLOAD", "task4/folder/file.mp4", 100L,
        "SUCCESS", null, "127.0.0.1", FIXTURE_TIME,
        FIXTURE_TIME.plus(Duration.ofDays(30))));
    assertThat(audits().search(OWNER_ID, "DOWNLOAD", "SUCCESS", "task4/folder/file.mp4",
        FIXTURE_TIME.minusSeconds(1), FIXTURE_TIME.plusSeconds(1), 10)).containsExactly(audit);

    Instant leaseNow = Instant.now();
    assertThat(maintenanceLeases().tryAcquire(
        "task4-owner-a", leaseNow, leaseNow.plusSeconds(60))).isTrue();
    assertThat(maintenanceLeases().tryAcquire(
        "task4-owner-b", leaseNow, leaseNow.plusSeconds(60))).isFalse();
    assertThat(maintenanceLeases().renew(
        "task4-owner-a", leaseNow.plusSeconds(1), leaseNow.plusSeconds(120))).isTrue();
    assertThat(maintenanceLeases().release("task4-owner-a")).isTrue();

    var media = mediaJobs().save(mediaJob());
    assertThat(mediaJobs().findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
        "task4-cache", MediaJobStatus.active())).contains(media);
    assertThat(mediaJobs().countByOwnerIdAndStatusIn(OWNER_ID, MediaJobStatus.active())).isOne();

    var recovery = recoveries().save(recovery());
    assertThat(recoveries().findTop100ByOwnerIdOrderByUpdatedAtAsc(OWNER_ID))
        .containsExactly(recovery);

    var station = sharedRadio().save(SharedFolderRadioDocument.playing(
        1, "task4/music/a.mp3", FIXTURE_TIME, 90.0,
        List.of(new SharedFolderRadioDocument.TrackDuration(
            "task4/music/b.mp3", "task4-token-b", 91.0))));
    assertThat(sharedRadio().findById(SharedFolderRadioDocument.ID)).contains(station);
    assertThat(station.knownDurations()).extracting(SharedFolderRadioDocument.TrackDuration::path)
        .containsExactly("task4/music/b.mp3");

    recycleItems().save(recycle("task4-recycle-a", FIXTURE_TIME));
    recycleItems().save(recycle("task4-recycle-b", FIXTURE_TIME.plusSeconds(1)));
    assertThat(recycleItems().findByStateOrderByDeletedAtDescIdDesc(
        SharedFolderRecycleState.RECYCLED, PageRequest.of(0, 1)).getContent())
        .extracting(SharedFolderRecycleItem::id).containsExactly("task4-recycle-b");

    var upload = uploadSessions().save(upload());
    assertThat(uploadSessions().findById(upload.getId()).orElseThrow().getChunkDigests())
        .containsEntry("task4-chunk", "a".repeat(64));
    assertThat(uploadSessions().countByOwnerIdAndStateIn(
        OWNER_ID, List.of(SharedFolderUploadState.ACTIVE))).isOne();
  }

  private static MusicTrack track(
      String id, String path, String artist, boolean excludedFromRadio) {
    return new MusicTrack(id, path, "token-" + id, null, id, artist, artist, "Task 4 Album",
        1, 1, "Genre", 2026, 90, "mp3", "mp3", null, false, excludedFromRadio,
        MusicIndexStatus.READY, null, FIXTURE_TIME, FIXTURE_TIME, null);
  }

  private static MusicMetadataEdit metadataEdit() {
    return new MusicMetadataEdit(
        "task4-edit", "task4-track-a", "task4/a.mp3", "task4-backup.bin", "a".repeat(64),
        "token-task4-track-a", null, "mp3", 90, OWNER_ID, FIXTURE_TIME,
        FIXTURE_TIME.plusSeconds(3_600), MusicMetadataEdit.Status.PREPARED, null, null);
  }

  private static MusicRadioHistoryEvent history(String id, long sequence, String trackId) {
    return new MusicRadioHistoryEvent(id, sequence, trackId, "token-" + trackId, "Artist",
        MusicRadioState.Source.RADIO, MusicRadioHistoryEvent.Outcome.PLAYED,
        FIXTURE_TIME.plusSeconds(sequence));
  }

  private static MusicQueueState queue(Long version, String id, String trackId) {
    return new MusicQueueState(MusicQueueState.ID,
        List.of(new MusicQueueState.Entry(id, trackId, "token-" + trackId, OWNER_ID, FIXTURE_TIME)),
        version);
  }

  private static MusicRadioState radio(Long version, String trackId) {
    return new MusicRadioState(MusicRadioState.ID, 1, trackId, "token-" + trackId, FIXTURE_TIME,
        90, MusicRadioState.Source.RADIO, null, version);
  }

  private static MediaJob mediaJob() {
    var job = new MediaJob();
    job.setId("task4-media-job");
    job.setOwnerId(OWNER_ID);
    job.setSourcePath("task4/video/source.mkv");
    job.setSourceSize(100);
    job.setSourceModifiedAt(FIXTURE_TIME);
    job.setProfile(MediaOutputProfile.VIDEO_MP4);
    job.setProfileVersion(1);
    job.setCacheKey("task4-cache");
    job.setActiveCacheKey("task4-active-cache");
    job.setStatus(MediaJobStatus.QUEUED);
    job.setReservedBytes(1_000);
    job.setCreatedAt(FIXTURE_TIME);
    job.setUpdatedAt(FIXTURE_TIME);
    job.setLastAccessedAt(FIXTURE_TIME);
    job.setArtifactsCleaned(false);
    return job;
  }

  private static SharedFolderMutationRecovery recovery() {
    var value = new SharedFolderMutationRecovery();
    value.setId("task4-recovery");
    value.setOwnerId(OWNER_ID);
    value.setSourcePath("task4/folder/source.txt");
    value.setDestinationParentPath("task4/folder");
    value.setName("target.txt");
    value.setSourceIdentity("task4-source-identity");
    value.setState(SharedFolderMutationRecoveryState.PREPARED);
    value.setOperationLeaseToken("task4-operation-owner");
    value.setOperationLeaseExpiresAt(FIXTURE_TIME.plusSeconds(60));
    value.setCreatedAt(FIXTURE_TIME);
    value.setUpdatedAt(FIXTURE_TIME);
    return value;
  }

  private static SharedFolderRecycleItem recycle(String id, Instant deletedAt) {
    return new SharedFolderRecycleItem(
        id, "task4/folder/" + id, OWNER_ID, deletedAt, deletedAt.plus(Duration.ofDays(30)),
        "task4/recycle/" + id, 10, false, "fingerprint-" + id,
        SharedFolderRecycleState.RECYCLED, null, null, "identity-" + id, Instant.EPOCH);
  }

  private static SharedFolderUploadSession upload() {
    var value = new SharedFolderUploadSession();
    value.setId("task4-upload");
    value.setOwnerId(OWNER_ID);
    value.setParentPath("task4/folder");
    value.setName("upload.bin");
    value.setExpectedBytes(4);
    value.setExpectedSha256("b".repeat(64));
    value.setNextOffset(0);
    value.setChunkDigests(Map.of("task4-chunk", "a".repeat(64)));
    value.setChunkLengths(Map.of("task4-chunk", 4L));
    value.setStagingKey("task4/uploads/task4-upload");
    value.setState(SharedFolderUploadState.ACTIVE);
    value.setExpiresAt(FIXTURE_TIME.plusSeconds(60));
    value.setCreatedAt(FIXTURE_TIME);
    value.setUpdatedAt(FIXTURE_TIME);
    return value;
  }
}
