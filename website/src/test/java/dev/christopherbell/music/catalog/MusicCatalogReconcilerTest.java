package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.lease.CollectorLeaseGuard;
import dev.christopherbell.configuration.mongo.lease.LeaseOwnershipLostException;
import dev.christopherbell.configuration.mongo.lease.ScheduledCollectorCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MusicCatalogReconcilerTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
  @TempDir Path tempDir;

  @Test
  void probesAtMostTheConfiguredBatchAndSkipsUnchangedTracks() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("Music"));
    for (int index = 0; index < 101; index++) {
      Files.writeString(root.resolve("track-%03d.mp3".formatted(index)), "audio-" + index);
    }
    var repository = memoryRepository();
    var probe = mock(MusicProbe.class);
    when(probe.probe(any())).thenReturn(metadata("Song"));
    var reconciler = reconciler(root, repository.proxy(), probe, 100);

    var first = reconciler.reconcile();
    var second = reconciler.reconcile();

    assertThat(first.probed()).isEqualTo(100);
    assertThat(second.probed()).isEqualTo(1);
    assertThat(repository.rows).hasSize(101);
    verify(probe, times(101)).probe(any());
  }

  @Test
  void preservesLastGoodMetadataOnProbeFailureAndMarksMissingRows() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("Music"));
    Path changed = Files.writeString(root.resolve("changed.flac"), "new-audio");
    String currentToken = MusicFileRevision.observe(changed).token();
    var good = MusicTrack.ready(
        "changed.flac", "old-token", metadata("Known title"), null, NOW.minusSeconds(60));
    var missing = MusicTrack.ready(
        "gone.mp3", "gone-token", metadata("Gone title"), null, NOW.minusSeconds(60));
    var repository = memoryRepository(good, missing);
    var probe = mock(MusicProbe.class);
    when(probe.probe(any())).thenThrow(new MusicProbeException("probe failed"));

    var result = reconciler(root, repository.proxy(), probe, 100).reconcile();

    var changedRow = repository.byPath("changed.flac");
    assertThat(result.failed()).isEqualTo(1);
    assertThat(changedRow.title()).isEqualTo("Known title");
    assertThat(changedRow.observedToken()).isEqualTo("old-token");
    assertThat(changedRow.indexStatus()).isEqualTo(MusicIndexStatus.PROBE_FAILED);
    assertThat(changedRow.pendingObservedToken()).isEqualTo(currentToken);
    assertThat(repository.byPath("gone.mp3").missingSince()).isEqualTo(NOW);
  }

  @Test
  void ignoresSymlinksAndNonAudioFiles() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("Music"));
    Files.writeString(root.resolve("notes.txt"), "not audio");
    Path outside = Files.writeString(tempDir.resolve("outside.mp3"), "outside");
    try {
      Files.createSymbolicLink(root.resolve("linked.mp3"), outside);
    } catch (UnsupportedOperationException | java.io.IOException unavailable) {
      return;
    }
    var repository = memoryRepository();
    var probe = mock(MusicProbe.class);

    var result = reconciler(root, repository.proxy(), probe, 100).reconcile();

    assertThat(result.discovered()).isZero();
    verify(probe, never()).probe(any());
  }

  @Test
  void scheduledReconcileWhenLeaseIsContendedDoesNotScanOrWrite() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("Music"));
    Files.writeString(root.resolve("song.mp3"), "audio");
    var repository = memoryRepository();
    var probe = mock(MusicProbe.class);
    var coordinator = mock(ScheduledCollectorCoordinator.class);
    when(coordinator.run(
        org.mockito.ArgumentMatchers.eq("music-catalog-reconcile"),
        org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(30)),
        any()))
        .thenReturn(null);

    reconciler(root, repository.proxy(), probe, 100, coordinator).scheduledReconcile();

    assertThat(repository.rows).isEmpty();
    verify(probe, never()).probe(any());
  }

  @Test
  void scheduledReconcileStopsWritingAfterLeaseOwnershipIsLost() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("Music"));
    Files.writeString(root.resolve("first.mp3"), "first-audio");
    Files.writeString(root.resolve("second.mp3"), "second-audio");
    var repository = memoryRepository();
    var probe = mock(MusicProbe.class);
    when(probe.probe(any())).thenReturn(metadata("Song"));
    var guard = mock(CollectorLeaseGuard.class);
    doNothing().doThrow(new LeaseOwnershipLostException("music-catalog-reconcile"))
        .when(guard).verifyHeld();
    var coordinator = mock(ScheduledCollectorCoordinator.class);
    when(coordinator.run(
        org.mockito.ArgumentMatchers.eq("music-catalog-reconcile"),
        org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(30)),
        any()))
        .thenAnswer(invocation -> {
          ScheduledCollectorCoordinator.Work<?> work = invocation.getArgument(2);
          return work.execute(guard);
        });

    var reconciler = reconciler(root, repository.proxy(), probe, 100, coordinator);

    assertThatThrownBy(reconciler::scheduledReconcile)
        .isInstanceOf(LeaseOwnershipLostException.class);
    assertThat(repository.rows).hasSize(1);
    assertThat(repository.byPath("first.mp3")).isNotNull();
  }

  private MusicCatalogReconciler reconciler(
      Path root,
      MusicTrackRepository repository,
      MusicProbe probe,
      int batchSize) {
    return reconciler(
        root, repository, probe, batchSize, mock(ScheduledCollectorCoordinator.class));
  }

  private MusicCatalogReconciler reconciler(
      Path root,
      MusicTrackRepository repository,
      MusicProbe probe,
      int batchSize,
      ScheduledCollectorCoordinator scheduledCollectors) {
    var properties = new MusicProperties(
        root,
        tempDir.resolve("artwork"),
        "ffprobe",
        "ffmpeg",
        batchSize,
        Duration.ofMinutes(5),
        Duration.ofSeconds(20),
        1_048_576,
        5_242_880,
        2048,
        true);
    var artwork = mock(MusicArtworkService.class);
    when(artwork.extract(any(), any(), any())).thenReturn(Optional.empty());
    return new MusicCatalogReconciler(
        properties, repository, probe, artwork, scheduledCollectors,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private MusicProbeResult metadata(String title) {
    return new MusicProbeResult(
        title, "Artist", "Artist", "Album", 1, 1, "Genre", 2026,
        180.0, "flac", "flac", false);
  }

  private MemoryRepository memoryRepository(MusicTrack... initial) {
    return new MemoryRepository(initial);
  }

  private static final class MemoryRepository {
    private final List<MusicTrack> rows = new ArrayList<>();
    private final MusicTrackRepository proxy = mock(MusicTrackRepository.class);

    private MemoryRepository(MusicTrack... initial) {
      rows.addAll(List.of(initial));
      when(proxy.findByPath(any())).thenAnswer(invocation -> Optional.ofNullable(
          rows.stream().filter(row -> row.path().equals(invocation.getArgument(0))).findFirst()
              .orElse(null)));
      when(proxy.findAllByMissingSinceIsNull()).thenAnswer(invocation ->
          rows.stream().filter(row -> row.missingSince() == null).toList());
      when(proxy.save(any())).thenAnswer(invocation -> {
        MusicTrack saved = invocation.getArgument(0);
        rows.removeIf(row -> row.path().equals(saved.path()));
        rows.add(saved);
        return saved;
      });
    }

    private MusicTrackRepository proxy() {
      return proxy;
    }

    private MusicTrack byPath(String path) {
      return rows.stream().filter(row -> row.path().equals(path)).findFirst().orElseThrow();
    }
  }
}
