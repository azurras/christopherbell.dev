package dev.christopherbell.music.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.lease.MongoLeaseService;
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

  private MusicMetadataService service(
      MusicCatalog catalog,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicTagProcess process,
      MusicMetadataEditRepository edits) {
    var access = mock(MusicAccessService.class);
    when(access.requireWrite()).thenReturn(Account.builder().id("writer").build());
    var leases = mock(MongoLeaseService.class);
    when(leases.tryAcquire(any(), any(), any(), any())).thenReturn(true);
    var properties = metadataProperties();
    return new MusicMetadataService(
        musicProperties(), properties, catalog, tracks, probe, mock(MusicArtworkService.class),
        process, new MusicMetadataFileStore(properties, temporary.resolve("music")),
        edits, access, leases, Clock.fixed(NOW, ZoneOffset.UTC));
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
