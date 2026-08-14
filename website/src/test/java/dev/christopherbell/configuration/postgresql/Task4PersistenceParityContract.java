package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.libs.lease.LeaseGrant;
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
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadFinalizationState;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

/** Identical Task 4 persistence assertions run against disposable MongoDB and PostgreSQL. */
interface Task4PersistenceParityContract {
  Instant FIXTURE_TIME = Instant.parse("2026-08-13T20:00:00Z");
  String OWNER_ID = "task4-parity-owner";

  LeaseStore applicationLeases();
  LeaseStore applicationLeaseContender();
  Instant persistenceNow();
  void expireApplicationLease(LeaseGrant grant);
  MusicTrackRepository tracks();
  MusicCatalogQueryRepository catalog();
  MusicPlaylistRepository playlists();
  MusicMetadataEditRepository metadataEdits();
  MusicRadioHistoryRepository radioHistory();
  MusicRuntimeStateRepository runtimeState();
  MusicAccessAttemptRepository accessAttempts();
  SharedFolderAuditRepository audits();
  SharedFolderMaintenanceLeaseStore maintenanceLeases();
  SharedFolderMaintenanceLeaseStore maintenanceLeaseContender();
  void expireMaintenanceLease(LeaseGrant grant);
  MediaJobRepository mediaJobs();
  SharedFolderMutationRecoveryRepository recoveries();
  SharedFolderMutationRecoveryRepository recoveryContender();
  SharedFolderRadioRepository sharedRadio();
  SharedFolderRecycleRepository recycleItems();
  SharedFolderUploadSessionRepository uploadSessions();
  SharedFolderUploadSessionRepository uploadSessionContender();

  @Test
  default void applicationLeasePreservesExclusiveOwnershipRenewalAndMonotonicFencing() {
    Instant databaseBefore = persistenceNow();
    var first = applicationLeases().tryAcquire(
        "task4-parity-application", "owner-a", Duration.ofMinutes(1)).orElseThrow();
    Instant databaseAfter = persistenceNow();
    assertThat(first.expiresAt())
        .isAfterOrEqualTo(databaseBefore.plusSeconds(59))
        .isBeforeOrEqualTo(databaseAfter.plusSeconds(61));
    assertThat(applicationLeaseContender().tryAcquire(
        "task4-parity-application", "owner-b", Duration.ofMinutes(1))).isEmpty();
    assertThat(applicationLeases().renew(first, Duration.ofMinutes(2))).isPresent();
    expireApplicationLease(first);
    var next = applicationLeaseContender().tryAcquire(
        "task4-parity-application", "owner-b", Duration.ofMinutes(1)).orElseThrow();
    assertThat(next.fenceToken()).isGreaterThan(first.fenceToken());
    assertThat(applicationLeases().renew(first, Duration.ofMinutes(1))).isEmpty();
    assertThat(applicationLeases().release(first)).isFalse();
    assertThat(applicationLeaseContender().release(next)).isTrue();
  }

  @Test
  default void musicPortsPreserveQueriesOrderingVersionsAndBoundedCleanup() {
    tracks().save(track("task4-track-a", "task4/a.mp3", "Alpha", false));
    tracks().save(track("task4-track-b", "task4/b.mp3", "Beta", true));
    tracks().save(track("task4-track-case-lower", "task4/case-lower.mp3", "paritycase", true));
    tracks().save(track("task4-track-case-upper", "task4/case-upper.mp3", "Paritycase", true));
    assertThat(tracks().findByPath("task4/a.mp3")).isPresent();
    assertThat(tracks().updatePreferences("task4-track-a", false, false, true, false)).isTrue();
    assertThat(catalog().search(
        new MusicQuery("Alpha", null, null, null, null, null, 0, 10)).tracks())
        .extracting(MusicTrack::id).containsExactly("task4-track-a");
    assertThat(catalog().radioCandidates(10)).extracting(MusicTrack::id)
        .containsExactly("task4-track-a");
    assertThat(catalog().search(new MusicQuery(null, null, null, null, null, null, 0, 10))
        .facets().artists().stream().filter(value -> value.equalsIgnoreCase("paritycase")))
        .containsExactly("Paritycase");

    var playlist = playlists().save(new MusicPlaylist(
        "task4-playlist", "task4-playlist", "Task 4 Playlist",
        List.of("task4-track-b", "task4-track-a"),
        null, OWNER_ID, FIXTURE_TIME));
    assertThat(playlists().findById(playlist.id()).orElseThrow().trackIds())
        .containsExactly("task4-track-b", "task4-track-a");
    assertThatThrownBy(() -> new MusicPlaylist(
        "task4-playlist-invalid-membership", "task4-playlist-invalid-membership",
        "Invalid Duplicate Membership", List.of("task4-track-a", "task4-track-a"),
        null, OWNER_ID, FIXTURE_TIME))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> playlists().save(new MusicPlaylist(
        "task4-playlist-duplicate", playlist.normalizedName(), "Duplicate Playlist",
        List.of("task4-track-a"), null, OWNER_ID, FIXTURE_TIME.plusMillis(1))))
        .isInstanceOf(DuplicateKeyException.class)
        .hasCauseInstanceOf(RuntimeException.class);
    assertThat(playlists().count()).isOne();
    assertThat(playlists().findById(playlist.id()).orElseThrow()).isEqualTo(playlist);
    var changed = playlists().save(new MusicPlaylist(
        playlist.id(), playlist.normalizedName(), playlist.name(),
        List.of("task4-track-a", "task4-track-b"), playlist.version(), OWNER_ID,
        FIXTURE_TIME.plusSeconds(1)));
    assertThatThrownBy(() -> playlists().save(playlist))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(changed.trackIds())
        .containsExactly("task4-track-a", "task4-track-b");

    var edit = metadataEdits().save(metadataEdit());
    var appliedEdit = metadataEdits().save(edit.applied("task4-replacement-token"));
    assertThatThrownBy(() -> metadataEdits().save(edit))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(metadataEdits().findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(
        FIXTURE_TIME.plusSeconds(3_601))).containsExactly(appliedEdit);

    radioHistory().save(history("task4-history-a", 1, "task4-track-a"));
    radioHistory().save(history("task4-history-b", 2, "task4-track-b"));
    assertThat(radioHistory().findTop100ByOrderByStationSequenceDesc())
        .extracting(MusicRadioHistoryEvent::id)
        .containsExactly("task4-history-b", "task4-history-a");

    var queue = runtimeState().saveQueue(queue(null, "task4-queue-a", "task4-track-a"));
    var radio = runtimeState().saveRadio(radio(null, "task4-track-a"));
    var advancedQueue = runtimeState().saveQueue(
        queue(queue.version(), "task4-queue-b", "task4-track-b"));
    var advancedRadio = runtimeState().saveRadio(radio(radio.version(), "task4-track-b"));
    assertThatThrownBy(() -> runtimeState().saveQueue(queue))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> runtimeState().saveRadio(radio))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(runtimeState().findQueue()).contains(advancedQueue);
    assertThat(runtimeState().findRadio()).contains(advancedRadio);

    accessAttempts().record("task4-attempt", MusicAccessPrincipalType.IP, "127.0.0.1",
        "denied", FIXTURE_TIME, FIXTURE_TIME.plus(Duration.ofDays(30)));
    var aggregated = accessAttempts().record(
        "task4-attempt", MusicAccessPrincipalType.IP, "127.0.0.1", "denied",
        FIXTURE_TIME.plusSeconds(1), FIXTURE_TIME.plus(Duration.ofDays(30)).plusSeconds(1));
    assertThat(aggregated.count()).isEqualTo(2);
    assertThat(accessAttempts().recent(100)).contains(aggregated);
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

    var firstMaintenanceGrant = maintenanceLeases()
        .tryAcquire("task4-owner-a", Duration.ofMinutes(1)).orElseThrow();
    assertThat(maintenanceLeaseContender()
        .tryAcquire("task4-owner-b", Duration.ofMinutes(1))).isEmpty();
    assertThat(maintenanceLeases().renew(firstMaintenanceGrant, Duration.ofMinutes(2)))
        .isPresent();
    var wrongNameGrant = new LeaseGrant(
        firstMaintenanceGrant.leaseName() + "-collision",
        firstMaintenanceGrant.ownerId(),
        firstMaintenanceGrant.fenceToken(),
        firstMaintenanceGrant.expiresAt());
    assertThat(maintenanceLeases().renew(wrongNameGrant, Duration.ofMinutes(2))).isEmpty();
    assertThat(maintenanceLeases().release(wrongNameGrant)).isFalse();
    expireMaintenanceLease(firstMaintenanceGrant);
    var takeover = maintenanceLeaseContender()
        .tryAcquire("task4-owner-a", Duration.ofMinutes(1)).orElseThrow();
    assertThat(takeover.fenceToken()).isGreaterThan(firstMaintenanceGrant.fenceToken());
    assertThat(maintenanceLeases().renew(firstMaintenanceGrant, Duration.ofMinutes(1))).isEmpty();
    assertThat(maintenanceLeases().release(firstMaintenanceGrant)).isFalse();
    assertThat(maintenanceLeaseContender().release(takeover)).isTrue();

    var media = mediaJobs().save(mediaJob());
    var staleMedia = mediaJob();
    staleMedia.setVersion(media.getVersion());
    media.setStatus(MediaJobStatus.INSPECTING);
    media.setUpdatedAt(FIXTURE_TIME.plusSeconds(1));
    media = mediaJobs().save(media);
    staleMedia.setStatus(MediaJobStatus.TRANSCODING);
    assertThatThrownBy(() -> mediaJobs().save(staleMedia))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(mediaJobs().findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
        "task4-cache", MediaJobStatus.active())).contains(media);
    assertThat(mediaJobs().countByOwnerIdAndStatusIn(OWNER_ID, MediaJobStatus.active())).isOne();
    assertThat(mediaJobs().cancelActive(media.getId(), "wrong-owner",
        FIXTURE_TIME.plusSeconds(2), FIXTURE_TIME.plusSeconds(3))).isZero();
    assertThat(mediaJobs().cancelActive(media.getId(), OWNER_ID,
        FIXTURE_TIME.plusSeconds(2), FIXTURE_TIME.plusSeconds(3))).isOne();
    var cancelledMedia = mediaJobs().findById(media.getId()).orElseThrow();
    assertThat(cancelledMedia.getStatus()).isEqualTo(MediaJobStatus.CANCELED);
    assertThat(cancelledMedia.getActiveCacheKey()).isNull();
    assertThat(mediaJobs()
        .findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(
            List.of(MediaJobStatus.CANCELED), FIXTURE_TIME.plusSeconds(3),
            PageRequest.of(0, 1)).getContent())
        .containsExactly(cancelledMedia);

    var recovery = recoveries().save(recovery());
    assertThat(recoveries().findTop100ByOwnerIdOrderByUpdatedAtAsc(OWNER_ID))
        .containsExactly(recovery);

    var station = sharedRadio().save(SharedFolderRadioDocument.playing(
        1, "task4/music/a.mp3", FIXTURE_TIME, 90.0,
        List.of(new SharedFolderRadioDocument.TrackDuration(
            "task4/music/b.mp3", "task4-token-b", 91.0))));
    var emptyStation = sharedRadio().save(SharedFolderRadioDocument.empty(
        2, station.knownDurations(), station.version()));
    assertThatThrownBy(() -> sharedRadio().save(station))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(sharedRadio().findById(SharedFolderRadioDocument.ID)).contains(emptyStation);
    assertThat(station.knownDurations()).extracting(SharedFolderRadioDocument.TrackDuration::path)
        .containsExactly("task4/music/b.mp3");

    var recycleA = recycleItems().save(recycle("task4-recycle-a", FIXTURE_TIME));
    var recycleB = recycleItems().save(recycle("task4-recycle-b", FIXTURE_TIME.plusSeconds(1)));
    assertThat(recycleItems().findByStateOrderByDeletedAtDescIdDesc(
        SharedFolderRecycleState.RECYCLED, PageRequest.of(0, 1)).getContent())
        .extracting(SharedFolderRecycleItem::id).containsExactly("task4-recycle-b");
    assertThat(recycleItems()
        .findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
            SharedFolderRecycleState.RECYCLED, FIXTURE_TIME.plus(Duration.ofDays(31)),
            FIXTURE_TIME, PageRequest.of(0, 1)))
        .containsExactly(recycleA);
    var restoringRecycle = recycleItems().save(recycleB
        .withRestore("task4/replacement/task4-recycle-b", "replacement-fingerprint")
        .withRetryAfter(FIXTURE_TIME.plusSeconds(30)));
    assertThat(recycleItems().findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
        List.of(SharedFolderRecycleState.RESTORING), FIXTURE_TIME, PageRequest.of(0, 10)))
        .isEmpty();
    assertThat(recycleItems().findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
        List.of(SharedFolderRecycleState.RESTORING), FIXTURE_TIME.plusSeconds(30),
        PageRequest.of(0, 10))).containsExactly(restoringRecycle);
    var recycledAgain = recycleItems().save(restoringRecycle.recycledAgain());
    assertThat(recycledAgain.state()).isEqualTo(SharedFolderRecycleState.RECYCLED);
    assertThat(recycledAgain.replacementKey()).isNull();

    var upload = uploadSessions().save(upload());
    assertThat(uploadSessions().findById(upload.getId()).orElseThrow().getChunkDigests())
        .containsEntry("task4-chunk", "a".repeat(64));
    assertThat(uploadSessions().countByOwnerIdAndStateIn(
        OWNER_ID, List.of(SharedFolderUploadState.ACTIVE))).isOne();
  }

  @Test
  default void recoveryAndUploadClaimsUseDatabaseTimeAndExactFencing() throws Exception {
    var initiallyUnissuedRecovery = recoveries().save(recovery(
        "task4-recovery-initial-database-time", null, null));
    var recoveryInitialExpiry = recoveries().acquireOperationLease(
        initiallyUnissuedRecovery.getId(), "task4-recovery-initial",
        initiallyUnissuedRecovery.getState(), Duration.ofMinutes(1));
    assertThat(recoveryInitialExpiry).isPresent();
    assertThat(recoveryContender().acquireOperationLease(
        initiallyUnissuedRecovery.getId(), "task4-recovery-initial-contender",
        initiallyUnissuedRecovery.getState(), Duration.ofMinutes(1))).isEmpty();

    var abandonedRecovery = recoveries().save(recovery(
        "task4-recovery-abandoned-before-acquire", null, null));
    assertThat(recoveryContender().claimExpiredOperationLease(
        abandonedRecovery.getId(), null, abandonedRecovery.getState(),
        "task4-recovery-abandoned-owner", Duration.ofMinutes(1))).isPresent();

    var recovery = recoveries().save(recovery(
        "task4-recovery-fence", "task4-recovery-old", Instant.EPOCH));
    var staleRecovery = recovery.copy();
    assertThat(recoveries().renewOperationLease(recovery.getId(),
        recovery.getOperationLeaseToken(), recovery.getState(), Duration.ofMinutes(1))).isEmpty();
    assertOneWinner(
        () -> recoveries().claimExpiredOperationLease(recovery.getId(),
            "task4-recovery-old", recovery.getState(), "task4-recovery-a", Duration.ofMinutes(1))
            .isPresent(),
        () -> recoveryContender().claimExpiredOperationLease(recovery.getId(),
            "task4-recovery-old", recovery.getState(), "task4-recovery-b", Duration.ofMinutes(1))
            .isPresent());
    var recoveryWinner = recoveries().findById(recovery.getId()).orElseThrow();
    assertThat(recoveryWinner.getOperationLeaseToken())
        .isIn("task4-recovery-a", "task4-recovery-b");
    assertThat(recoveryWinner.getOperationLeaseExpiresAt()).isAfter(Instant.now());
    assertThat(recoveryContender().renewOperationLease(recovery.getId(),
        "task4-recovery-old", recovery.getState(), Duration.ofMinutes(1))).isEmpty();
    assertThat(recoveries().renewOperationLease(recovery.getId(),
        recoveryWinner.getOperationLeaseToken(), recovery.getState(), Duration.ofMinutes(1)))
        .isPresent();
    recoveryWinner.setState(SharedFolderMutationRecoveryState.SOURCE_MOVED);
    recoveryWinner.setUpdatedAt(FIXTURE_TIME.plusSeconds(5));
    var completedRecoveryPhase = recoveries().save(recoveryWinner);
    staleRecovery.setState(SharedFolderMutationRecoveryState.TARGET_QUARANTINED);
    assertThatThrownBy(() -> recoveries().save(staleRecovery))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(recoveries().findById(recovery.getId())).contains(completedRecoveryPhase);

    var append = uploadSessions().save(uploadWithAppendLease(
        "task4-upload-append-fence", "task4-append-old", Instant.EPOCH));
    assertThat(uploadSessions().renewAppendLease(append.getId(), "task4-append-old", 0,
        Duration.ofMinutes(1))).isEmpty();
    assertOneWinner(
        () -> uploadSessions().claimExpiredAppendLease(append.getId(), "task4-append-old", 0,
            "task4-append-a", Duration.ofMinutes(1)).isPresent(),
        () -> uploadSessionContender().claimExpiredAppendLease(
            append.getId(), "task4-append-old", 0,
            "task4-append-b", Duration.ofMinutes(1)).isPresent());
    var appendWinner = uploadSessions().findById(append.getId()).orElseThrow();
    assertThat(appendWinner.getAppendLeaseToken()).isIn("task4-append-a", "task4-append-b");
    assertThat(appendWinner.getAppendLeaseExpiresAt()).isAfter(Instant.now());
    assertThat(uploadSessionContender().renewAppendLease(
        append.getId(), "task4-append-old", 0, Duration.ofMinutes(1))).isEmpty();
    assertThat(uploadSessions().renewAppendLease(append.getId(),
        appendWinner.getAppendLeaseToken(), 0, Duration.ofMinutes(1))).isPresent();
    assertThatThrownBy(() -> uploadSessions().save(append))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var completedAppend = uploadSessions().findById(append.getId()).orElseThrow();
    completedAppend.setState(SharedFolderUploadState.ACTIVE);
    completedAppend.setNextOffset(4);
    completedAppend.setAppendLeaseToken(null);
    completedAppend.setAppendLeaseExpiresAt(null);
    completedAppend.setAppendOffset(null);
    completedAppend.setAppendLength(null);
    completedAppend.setAppendDigest(null);
    completedAppend.setAppendChunkKey(null);
    completedAppend = uploadSessions().save(completedAppend);
    assertThat(completedAppend.getState()).isEqualTo(SharedFolderUploadState.ACTIVE);

    var initiallyUnissuedAppend = uploadSessions().save(uploadWithAppendLease(
        "task4-upload-append-initial-database-time", null, null));
    assertThat(uploadSessions().acquireAppendLease(initiallyUnissuedAppend.getId(),
        "task4-append-initial", 0, Duration.ofMinutes(1))).isPresent();
    assertThat(uploadSessionContender().acquireAppendLease(initiallyUnissuedAppend.getId(),
        "task4-append-initial-contender", 0, Duration.ofMinutes(1))).isEmpty();

    var abandonedAppend = uploadSessions().save(uploadWithAppendLease(
        "task4-upload-append-abandoned-before-acquire", null, null));
    assertThat(uploadSessionContender().claimExpiredAppendLease(
        abandonedAppend.getId(), null, 0,
        "task4-append-abandoned-owner", Duration.ofMinutes(1))).isPresent();

    assertThat(uploadSessionContender().relinquishAppendLease(
        initiallyUnissuedAppend.getId(), "wrong-owner", 0)).isFalse();
    assertThat(uploadSessions().relinquishAppendLease(
        initiallyUnissuedAppend.getId(), "task4-append-initial", 0)).isTrue();
    assertThat(uploadSessionContender().claimExpiredAppendLease(
        initiallyUnissuedAppend.getId(), "task4-append-initial", 0,
        "task4-append-relinquished-owner", Duration.ofMinutes(1))).isPresent();

    var finalization = uploadSessions().save(uploadWithFinalizationLease(
        "task4-upload-finalization-fence", "task4-finalization-old", Instant.EPOCH));
    assertThat(uploadSessions().renewFinalizationLease(finalization.getId(),
        "task4-finalization-old", SharedFolderUploadFinalizationState.PREPARED,
        Duration.ofMinutes(1))).isEmpty();
    assertOneWinner(
        () -> uploadSessions().claimExpiredFinalizationLease(finalization.getId(),
            "task4-finalization-old", SharedFolderUploadFinalizationState.PREPARED,
            "task4-finalization-a", Duration.ofMinutes(1)).isPresent(),
        () -> uploadSessionContender().claimExpiredFinalizationLease(finalization.getId(),
            "task4-finalization-old", SharedFolderUploadFinalizationState.PREPARED,
            "task4-finalization-b", Duration.ofMinutes(1)).isPresent());
    var finalizationWinner = uploadSessions().findById(finalization.getId()).orElseThrow();
    assertThat(finalizationWinner.getFinalizationLeaseToken())
        .isIn("task4-finalization-a", "task4-finalization-b");
    assertThat(finalizationWinner.getFinalizationLeaseExpiresAt()).isAfter(Instant.now());
    assertThat(uploadSessionContender().renewFinalizationLease(finalization.getId(),
        "task4-finalization-old", SharedFolderUploadFinalizationState.PREPARED,
        Duration.ofMinutes(1))).isEmpty();
    assertThat(uploadSessions().renewFinalizationLease(finalization.getId(),
        finalizationWinner.getFinalizationLeaseToken(),
        SharedFolderUploadFinalizationState.PREPARED, Duration.ofMinutes(1))).isPresent();
    assertThatThrownBy(() -> uploadSessions().save(finalization))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var completedFinalization = uploadSessions().findById(finalization.getId()).orElseThrow();
    completedFinalization.setState(SharedFolderUploadState.COMPLETED);
    completedFinalization.setFinalizationLeaseToken(null);
    completedFinalization.setFinalizationLeaseExpiresAt(null);
    completedFinalization = uploadSessions().save(completedFinalization);
    assertThat(completedFinalization.getState()).isEqualTo(SharedFolderUploadState.COMPLETED);

    var initiallyUnissuedFinalization = uploadSessions().save(uploadWithFinalizationLease(
        "task4-upload-finalization-initial-database-time", null, null));
    assertThat(uploadSessions().acquireFinalizationLease(
        initiallyUnissuedFinalization.getId(),
        "task4-finalization-initial",
        SharedFolderUploadFinalizationState.PREPARED, Duration.ofMinutes(1))).isPresent();
    assertThat(uploadSessionContender().acquireFinalizationLease(
        initiallyUnissuedFinalization.getId(),
        "task4-finalization-initial-contender",
        SharedFolderUploadFinalizationState.PREPARED, Duration.ofMinutes(1))).isEmpty();

    var abandonedFinalization = uploadSessions().save(uploadWithFinalizationLease(
        "task4-upload-finalization-abandoned-before-acquire", null, null));
    assertThat(uploadSessionContender().claimExpiredFinalizationLease(
        abandonedFinalization.getId(), null, SharedFolderUploadFinalizationState.PREPARED,
        "task4-finalization-abandoned-owner", Duration.ofMinutes(1))).isPresent();

    assertThat(uploadSessionContender().relinquishFinalizationLease(
        initiallyUnissuedFinalization.getId(), "wrong-owner",
        SharedFolderUploadFinalizationState.PREPARED)).isFalse();
    assertThat(uploadSessions().relinquishFinalizationLease(
        initiallyUnissuedFinalization.getId(), "task4-finalization-initial",
        SharedFolderUploadFinalizationState.PREPARED)).isTrue();
    assertThat(uploadSessionContender().claimExpiredFinalizationLease(
        initiallyUnissuedFinalization.getId(), "task4-finalization-initial",
        SharedFolderUploadFinalizationState.PREPARED,
        "task4-finalization-relinquished-owner", Duration.ofMinutes(1))).isPresent();
  }

  @Test
  default void mediaRetentionCleanupIsBoundedObservableAndIdempotent() {
    Instant cutoff = Instant.parse("2026-08-13T21:00:00Z");
    audits().save(new SharedFolderAuditEvent("task4-audit-expired-a", OWNER_ID,
        "RETENTION", null, null, "SUCCESS", null, "127.0.0.1",
        cutoff.minusSeconds(20), cutoff.minusSeconds(2)));
    audits().save(new SharedFolderAuditEvent("task4-audit-expired-b", OWNER_ID,
        "RETENTION", null, null, "SUCCESS", null, "127.0.0.1",
        cutoff.minusSeconds(10), cutoff.minusSeconds(1)));
    audits().save(new SharedFolderAuditEvent("task4-audit-retained", OWNER_ID,
        "RETENTION", null, null, "SUCCESS", null, "127.0.0.1",
        cutoff, cutoff.plusSeconds(1)));
    assertThat(audits().deleteExpired(cutoff, 1)).isOne();
    assertThat(audits().deleteExpired(cutoff, 1)).isOne();
    assertThat(audits().deleteExpired(cutoff, 1)).isZero();
    assertThat(audits().search(OWNER_ID, "RETENTION", "SUCCESS", null,
        cutoff.minusSeconds(30), cutoff.plusSeconds(1), 10))
        .extracting(SharedFolderAuditEvent::id).containsExactly("task4-audit-retained");

    accessAttempts().record("task4-retention-expired-a", MusicAccessPrincipalType.IP,
        "127.0.0.2", "denied", cutoff.minusSeconds(20), cutoff.minusSeconds(2));
    accessAttempts().record("task4-retention-expired-b", MusicAccessPrincipalType.IP,
        "127.0.0.3", "denied", cutoff.minusSeconds(10), cutoff.minusSeconds(1));
    accessAttempts().record("task4-retention-retained", MusicAccessPrincipalType.IP,
        "127.0.0.4", "denied", cutoff, cutoff.plusSeconds(1));
    assertThat(accessAttempts().deleteExpired(cutoff, 1)).isOne();
    assertThat(accessAttempts().deleteExpired(cutoff, 1)).isOne();
    assertThat(accessAttempts().deleteExpired(cutoff, 1)).isZero();
    assertThat(accessAttempts().recent(100)).extracting(value -> value.id())
        .contains("task4-retention-retained")
        .doesNotContain("task4-retention-expired-a", "task4-retention-expired-b");
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
    return recovery("task4-recovery", "task4-operation-owner",
        FIXTURE_TIME.plusSeconds(60));
  }

  private static SharedFolderMutationRecovery recovery(
      String id, String token, Instant expiresAt) {
    var value = new SharedFolderMutationRecovery();
    value.setId(id);
    value.setOwnerId(OWNER_ID);
    value.setSourcePath("task4/folder/source.txt");
    value.setDestinationParentPath("task4/folder");
    value.setName("target.txt");
    value.setSourceIdentity("task4-source-identity");
    value.setState(SharedFolderMutationRecoveryState.PREPARED);
    value.setOperationLeaseToken(token);
    value.setOperationLeaseExpiresAt(expiresAt);
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

  private static SharedFolderUploadSession uploadWithAppendLease(
      String id, String token, Instant expiresAt) {
    var value = upload();
    value.setId(id);
    value.setStagingKey("task4/uploads/" + id);
    value.setState(SharedFolderUploadState.APPENDING);
    value.setAppendLeaseToken(token);
    value.setAppendLeaseExpiresAt(expiresAt);
    value.setAppendOffset(0L);
    value.setAppendLength(4L);
    value.setAppendDigest("a".repeat(64));
    value.setAppendChunkKey("task4-chunk");
    return value;
  }

  private static SharedFolderUploadSession uploadWithFinalizationLease(
      String id, String token, Instant expiresAt) {
    var value = upload();
    value.setId(id);
    value.setStagingKey("task4/uploads/" + id);
    value.setState(SharedFolderUploadState.FINALIZING);
    value.setFinalizingIdentity("task4-finalizing-identity");
    value.setFinalizingReplace(false);
    value.setFinalizationState(SharedFolderUploadFinalizationState.PREPARED);
    value.setFinalizationLeaseToken(token);
    value.setFinalizationLeaseExpiresAt(expiresAt);
    return value;
  }

  private static void assertOneWinner(
      java.util.concurrent.Callable<Boolean> first,
      java.util.concurrent.Callable<Boolean> second) throws Exception {
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var firstResult = executor.submit(() -> { start.await(); return first.call(); });
      var secondResult = executor.submit(() -> { start.await(); return second.call(); });
      start.countDown();
      assertThat(List.of(firstResult.get(), secondResult.get()))
          .containsExactlyInAnyOrder(true, false);
    }
  }
}
