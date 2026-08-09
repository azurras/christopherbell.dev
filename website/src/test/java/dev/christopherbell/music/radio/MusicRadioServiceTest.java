package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MusicRadioServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

  @Test
  void queuedTrackOverridesSmartRadio() {
    MusicTrack queued = track("queued.mp3", "Queued Artist");
    MusicTrack random = track("random.mp3", "Random Artist");
    var catalog = mock(MusicCatalog.class);
    when(catalog.radioCandidates(10_000)).thenReturn(List.of(random));
    when(catalog.findReady(queued.id())).thenReturn(Optional.of(queued));
    var queue = mock(MusicQueueService.class);
    var entry = new MusicQueueState.Entry(
        "queue-1", queued.id(), queued.observedToken(), "writer-1", NOW.minusSeconds(5));
    when(queue.loadForRadio()).thenReturn(
        new MusicQueueState(MusicQueueState.ID, List.of(entry), 1L));
    var runtimeState = mock(MusicRuntimeStateStore.class);
    when(runtimeState.findRadio()).thenReturn(Optional.empty());
    when(runtimeState.saveRadio(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var history = mock(MusicRadioHistoryRepository.class);
    when(history.findTop100ByOrderByStationSequenceDesc()).thenReturn(List.of());

    MusicRadioSnapshot result = service(
        catalog, runtimeState, history, queue, mockSelector()).current();

    assertThat(result.source()).isEqualTo(MusicRadioState.Source.QUEUE);
    assertThat(result.trackId()).isEqualTo(queued.id());
    assertThat(result.positionSeconds()).isZero();
  }

  @Test
  void restartCatchesUpAcrossMultipleCatalogDurationsWithoutAListenerReport() {
    MusicTrack a = track("a.mp3", "A");
    MusicTrack b = track("b.mp3", "B");
    MusicTrack c = track("c.mp3", "C");
    var catalog = mock(MusicCatalog.class);
    when(catalog.radioCandidates(10_000)).thenReturn(List.of(a, b, c));
    when(catalog.findReady(a.id())).thenReturn(Optional.of(a));
    when(catalog.findReady(b.id())).thenReturn(Optional.of(b));
    when(catalog.findReady(c.id())).thenReturn(Optional.of(c));
    var initial = new MusicRadioState(
        MusicRadioState.ID, 1, a.id(), a.observedToken(), NOW.minusSeconds(25),
        10, MusicRadioState.Source.RADIO, null, 1L);
    var runtimeState = mock(MusicRuntimeStateStore.class);
    when(runtimeState.findRadio()).thenReturn(Optional.of(initial));
    when(runtimeState.saveRadio(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var history = mock(MusicRadioHistoryRepository.class);
    when(history.findTop100ByOrderByStationSequenceDesc()).thenReturn(List.of());
    when(history.existsById("station:1")).thenReturn(true);
    var queue = mock(MusicQueueService.class);
    when(queue.loadForRadio()).thenReturn(MusicQueueState.empty());
    var selector = mockSelector();
    when(selector.select(anyList(), anyList(), anyString())).thenReturn(b, c);

    MusicRadioSnapshot result = service(catalog, runtimeState, history, queue, selector).current();

    assertThat(result.stationSequence()).isEqualTo(3);
    assertThat(result.trackId()).isEqualTo(c.id());
    assertThat(result.startedAt()).isEqualTo(NOW.minusSeconds(5));
    assertThat(result.positionSeconds()).isEqualTo(5);
  }

  private MusicRadioService service(
      MusicCatalog catalog,
      MusicRuntimeStateStore runtimeState,
      MusicRadioHistoryRepository history,
      MusicQueueService queue,
      MusicRadioSelector selector) {
    var leases = mock(MongoLeaseService.class);
    when(leases.tryAcquire(anyString(), anyString(), any(), any())).thenReturn(true);
    when(leases.release(anyString(), anyString())).thenReturn(true);
    return new MusicRadioService(
        musicProperties(), radioProperties(), catalog, runtimeState, history, queue, selector,
        mock(MusicAccessService.class), leases, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private MusicRadioSelector mockSelector() {
    return mock(MusicRadioSelector.class);
  }

  private MusicTrack track(String path, String artist) {
    return MusicTrack.ready(path, "token-" + path, new MusicProbeResult(
        path, artist, artist, "Album", 1, 1, "Genre", 2026,
        10, "mp3", "mp3", false), null, NOW);
  }

  private MusicProperties musicProperties() {
    return new MusicProperties(
        java.nio.file.Path.of("Music"), java.nio.file.Path.of("artwork"),
        "ffprobe", "ffmpeg", 100, Duration.ofMinutes(1), Duration.ofSeconds(10),
        1024 * 1024, 5 * 1024 * 1024, 1024, true);
  }

  private MusicRadioProperties radioProperties() {
    return new MusicRadioProperties(50, 10, 0.1, 100, Duration.ofSeconds(10));
  }
}
