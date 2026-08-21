package dev.christopherbell.music.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.lease.CollectorLeaseGuard;
import dev.christopherbell.libs.lease.LeaseOwnershipLostException;
import dev.christopherbell.libs.lease.LeaseService;
import dev.christopherbell.libs.lease.ScheduledCollectorCoordinator;
import dev.christopherbell.music.catalog.MusicArtworkService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicFileRevision;
import dev.christopherbell.music.catalog.MusicProbe;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.music.security.MusicAccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class MusicMetadataServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
  @TempDir Path temporary;

  @Test
  void successfulEditBacksUpValidatesAtomicallyReplacesAndCanUndo() throws Exception {
    Path source = source("song.mp3", "original-audio");
    MusicTrack original = track("song.mp3", MusicFileRevision.observe(source).token(), "mp3", 180);
    var current = new AtomicReference<>(original);
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(original.id())).thenAnswer(ignored -> Optional.of(current.get()));
    var tracks = mock(MusicTrackRepository.class);
    when(tracks.save(any())).thenAnswer(invocation -> {
      MusicTrack saved = invocation.getArgument(0);
      current.set(saved);
      return saved;
    });
    MusicProbe probe = ignored -> metadata("mp3", 180);
    MusicTagProcess process = (input, output, update, artwork) -> copy(input, output);
    var edits = mock(MusicMetadataEditRepository.class);
    var applied = new AtomicReference<MusicMetadataEdit>();
    when(edits.save(any())).thenAnswer(invocation -> {
      MusicMetadataEdit edit = invocation.getArgument(0);
      if (edit.status() == MusicMetadataEdit.Status.APPLIED) applied.set(edit);
      return edit;
    });
    when(edits.findById(any())).thenAnswer(ignored -> Optional.ofNullable(applied.get()));
    var service = service(catalog, tracks, probe, process, edits);
    var update = new MusicMetadataUpdate(
        original.observedToken(), "New title", "Artist", null, "Album", 1, 1,
        "Genre", 2026, null, false);

    MusicMetadataResult changed = service.edit(original.id(), update);
    MusicMetadataResult undone = service.undo(changed.editId(), changed.observedToken());

    assertThat(changed.observedToken()).isNotEqualTo(original.observedToken());
    assertThat(undone.observedToken()).isNotEqualTo(changed.observedToken());
    assertThat(applied.get().backupSha256()).hasSize(64);
    assertThat(Files.readString(source)).isEqualTo("original-audio");
    verify(tracks, org.mockito.Mockito.times(2)).save(any());

    when(edits.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any()))
        .thenReturn(List.of(applied.get()));
    service.cleanupExpired();
    assertThat(Files.list(temporary.resolve("private")).toList()).isEmpty();
    verify(edits).delete(applied.get());
  }

  @Test
  void staleRevisionFailsBeforeProcessOrBackupEffects() throws Exception {
    Path source = source("song.mp3", "audio");
    MusicTrack track = track("song.mp3", MusicFileRevision.observe(source).token(), "mp3", 180);
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    var process = mock(MusicTagProcess.class);
    var edits = mock(MusicMetadataEditRepository.class);
    var service = service(catalog, mock(MusicTrackRepository.class), ignored -> metadata("mp3", 180),
        process, edits);

    assertThatThrownBy(() -> service.edit(track.id(), new MusicMetadataUpdate(
        "b".repeat(64), "Title", null, null, null, null, null, null, null, null, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");

    verify(process, never()).rewrite(any(), any(), any(), any());
    verify(edits, never()).save(any());
  }

  @Test
  void changedAudioOutputLeavesOriginalAndRemovesPrivateBackup() throws Exception {
    Path source = source("song.flac", "original");
    MusicTrack track = track("song.flac", MusicFileRevision.observe(source).token(), "flac", 180);
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    MusicTagProcess process = (input, output, update, artwork) -> copy(input, output);
    var edits = mock(MusicMetadataEditRepository.class);
    var service = service(catalog, mock(MusicTrackRepository.class), ignored -> metadata("aac", 180),
        process, edits);

    assertThatThrownBy(() -> service.edit(track.id(), new MusicMetadataUpdate(
        track.observedToken(), "Title", null, null, null, null, null, null, null, null, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("422 UNPROCESSABLE_ENTITY");

    assertThat(Files.readString(source)).isEqualTo("original");
    assertThat(Files.list(temporary.resolve("private")).toList()).isEmpty();
    verify(edits, never()).save(any());
  }

  @Test
  void cleanupExpiredWhenLeaseIsContendedLeavesBackupsAndRowsUntouched() throws Exception {
    Path backup = privateBackup("contended.backup.mp3");
    var edits = mock(MusicMetadataEditRepository.class);
    var coordinator = mock(ScheduledCollectorCoordinator.class);
    when(coordinator.run(
        org.mockito.ArgumentMatchers.eq("music-metadata-cleanup"),
        org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(10)),
        any()))
        .thenReturn(null);
    var service = service(
        mock(MusicCatalog.class), mock(MusicTrackRepository.class),
        ignored -> metadata("mp3", 180), mock(MusicTagProcess.class), edits, coordinator);

    service.cleanupExpired();

    assertThat(backup).exists();
    verify(edits, never()).delete(any());
  }

  @Test
  void cleanupExpiredWhenOwnershipIsLostBeforeFileDeleteLeavesBackupAndRow() throws Exception {
    Path backup = privateBackup("before-file.backup.mp3");
    var edit = expiredEdit(backup.getFileName().toString());
    var edits = mock(MusicMetadataEditRepository.class);
    when(edits.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any())).thenReturn(List.of(edit));
    var guard = mock(CollectorLeaseGuard.class);
    org.mockito.Mockito.doThrow(new LeaseOwnershipLostException("music-metadata-cleanup"))
        .when(guard).verifyHeld();
    var service = service(
        mock(MusicCatalog.class), mock(MusicTrackRepository.class),
        ignored -> metadata("mp3", 180), mock(MusicTagProcess.class), edits,
        runningCoordinator(guard));

    assertThatThrownBy(service::cleanupExpired)
        .isInstanceOf(LeaseOwnershipLostException.class);
    assertThat(backup).exists();
    verify(edits, never()).delete(any());
  }

  @Test
  void cleanupExpiredWhenOwnershipIsLostAfterFileDeleteKeepsRepositoryRow() throws Exception {
    Path backup = privateBackup("before-row.backup.mp3");
    var edit = expiredEdit(backup.getFileName().toString());
    var edits = mock(MusicMetadataEditRepository.class);
    when(edits.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(any())).thenReturn(List.of(edit));
    var guard = mock(CollectorLeaseGuard.class);
    org.mockito.Mockito.doNothing()
        .doThrow(new LeaseOwnershipLostException("music-metadata-cleanup"))
        .when(guard).verifyHeld();
    var service = service(
        mock(MusicCatalog.class), mock(MusicTrackRepository.class),
        ignored -> metadata("mp3", 180), mock(MusicTagProcess.class), edits,
        runningCoordinator(guard));

    assertThatThrownBy(service::cleanupExpired)
        .isInstanceOf(LeaseOwnershipLostException.class);
    assertThat(backup).doesNotExist();
    verify(edits, never()).delete(any());
  }

  private MusicMetadataService service(
      MusicCatalog catalog,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicTagProcess process,
      MusicMetadataEditRepository edits) {
    return service(
        catalog, tracks, probe, process, edits, runningCoordinator(CollectorLeaseGuard.NONE));
  }

  private MusicMetadataService service(
      MusicCatalog catalog,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicTagProcess process,
      MusicMetadataEditRepository edits,
      ScheduledCollectorCoordinator scheduledCollectors) {
    var access = mock(MusicAccessService.class);
    when(access.requireWrite()).thenReturn(Account.builder().id("writer").build());
    var leases = mock(LeaseService.class);
    when(leases.tryAcquire(any(), any(), any(), any())).thenReturn(true);
    var properties = metadataProperties();
    return new MusicMetadataService(
        musicProperties(), properties, catalog, tracks, probe, mock(MusicArtworkService.class),
        process, new MusicMetadataFileStore(properties, temporary.resolve("music")),
        edits, access, leases, scheduledCollectors, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ScheduledCollectorCoordinator runningCoordinator(CollectorLeaseGuard guard) {
    var coordinator = mock(ScheduledCollectorCoordinator.class);
    when(coordinator.run(any(), any(), any())).thenAnswer(invocation -> {
      ScheduledCollectorCoordinator.Work<?> work = invocation.getArgument(2);
      Object value = work.execute(guard);
      guard.verifyHeld();
      return new ScheduledCollectorCoordinator.Outcome<>(
          dev.christopherbell.libs.lease.ScheduledCollectorRunStatus.SUCCEEDED,
          value);
    });
    return coordinator;
  }

  private Path privateBackup(String fileName) throws IOException {
    Path root = Files.createDirectories(temporary.resolve("private"));
    return Files.writeString(root.resolve(fileName), "backup");
  }

  private MusicMetadataEdit expiredEdit(String backupFileName) {
    return new MusicMetadataEdit(
        "edit", "track", "song.mp3", backupFileName, "a".repeat(64),
        "b".repeat(64), "c".repeat(64), "mp3", 180, "writer",
        NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(1)),
        MusicMetadataEdit.Status.APPLIED, null, 0L);
  }

  private MusicProperties musicProperties() {
    return new MusicProperties(
        temporary.resolve("music"), temporary.resolve("artwork"), "ffprobe", "ffmpeg", 100,
        Duration.ofMinutes(5), Duration.ofSeconds(20), 1_048_576, 5_242_880, 2048, true);
  }

  private MusicMetadataProperties metadataProperties() {
    return new MusicMetadataProperties(
        temporary.resolve("private"), Duration.ofMinutes(2), 1_048_576,
        1_048_576, 5_242_880, 20_971_520, Duration.ofDays(30), Duration.ofMinutes(30));
  }

  private Path source(String relative, String content) throws IOException {
    Path source = temporary.resolve("music").resolve(relative);
    Files.createDirectories(source.getParent());
    return Files.writeString(source, content);
  }

  private MusicTrack track(String path, String token, String codec, double duration) {
    return MusicTrack.ready(path, token, metadata(codec, duration), null, NOW);
  }

  private MusicProbeResult metadata(String codec, double duration) {
    return new MusicProbeResult(
        "Title", "Artist", "Artist", "Album", 1, 1, "Genre", 2026,
        duration, codec, codec, false);
  }

  private void copy(Path source, Path destination) {
    try {
      Files.copy(source, destination);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
