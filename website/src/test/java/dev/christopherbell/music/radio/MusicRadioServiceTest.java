package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.lease.LeaseService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

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
    var consumedQueueEntries = new ArrayList<String>();
    doAnswer(invocation -> {
      consumedQueueEntries.add(invocation.getArgument(0));
      return null;
    }).when(queue).consumeForRadio(anyString());
    var runtimeState = mock(MusicRuntimeStateStore.class);
    when(runtimeState.findRadio()).thenReturn(Optional.empty());
    when(runtimeState.saveRadio(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var history = mock(MusicRadioHistoryRepository.class);
    when(history.findTop100ByOrderByStationSequenceDesc()).thenReturn(List.of());
    var savedHistory = new ArrayList<MusicRadioHistoryEvent>();
    when(history.save(any())).thenAnswer(invocation -> {
      MusicRadioHistoryEvent event = invocation.getArgument(0);
      savedHistory.add(event);
      return event;
    });

    MusicRadioSnapshot result = service(
        catalog, runtimeState, history, queue, mockSelector()).current();

    assertThat(result.source()).isEqualTo(MusicRadioState.Source.QUEUE);
    assertThat(result.trackId()).isEqualTo(queued.id());
    assertThat(result.positionSeconds()).isZero();
    assertThat(consumedQueueEntries).containsExactly("queue-1");
    assertThat(savedHistory).singleElement().satisfies(event -> {
      assertThat(event.id()).isEqualTo("station:1");
      assertThat(event.outcome()).isEqualTo(MusicRadioHistoryEvent.Outcome.PLAYED);
    });
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
    var savedStates = new ArrayList<MusicRadioState>();
    when(runtimeState.saveRadio(any())).thenAnswer(invocation -> {
      MusicRadioState state = invocation.getArgument(0);
      savedStates.add(state);
      return new MusicRadioState(
          state.id(), state.stationSequence(), state.trackId(), state.observedToken(),
          state.startedAt(), state.durationSeconds(), state.source(), state.queueEntryId(),
          Math.incrementExact(state.version()));
    });
    var history = mock(MusicRadioHistoryRepository.class);
    when(history.findTop100ByOrderByStationSequenceDesc()).thenReturn(List.of());
    when(history.existsById("station:1")).thenReturn(true);
    var savedHistory = new ArrayList<MusicRadioHistoryEvent>();
    when(history.save(any())).thenAnswer(invocation -> {
      MusicRadioHistoryEvent event = invocation.getArgument(0);
      savedHistory.add(event);
      return event;
    });
    var queue = mock(MusicQueueService.class);
    when(queue.loadForRadio()).thenReturn(MusicQueueState.empty());
    var selector = mockSelector();
    when(selector.select(anyList(), anyList(), anyString())).thenReturn(b, c);

    MusicRadioSnapshot result = service(catalog, runtimeState, history, queue, selector).current();

    assertThat(result.stationSequence()).isEqualTo(3);
    assertThat(result.trackId()).isEqualTo(c.id());
    assertThat(result.startedAt()).isEqualTo(NOW.minusSeconds(5));
    assertThat(result.positionSeconds()).isEqualTo(5);
    assertThat(savedStates).extracting(MusicRadioState::version).containsExactly(1L, 2L);
    assertThat(savedHistory).extracting(MusicRadioHistoryEvent::id)
        .containsExactly("station:2", "station:3");
    assertThat(savedHistory).extracting(MusicRadioHistoryEvent::outcome)
        .containsOnly(MusicRadioHistoryEvent.Outcome.PLAYED);
  }

  @Test
  void radioContentionReloadsTheWinningStateWithoutLosingSideEffects() {
    MusicTrack winner = track("winner.mp3", "Winner");
    var catalog = mock(MusicCatalog.class);
    when(catalog.radioCandidates(10_000)).thenReturn(List.of(winner));
    when(catalog.findReady(winner.id())).thenReturn(Optional.of(winner));
    MusicRadioState winningState = new MusicRadioState(
        MusicRadioState.ID, 4, winner.id(), winner.observedToken(), NOW,
        winner.durationSeconds(), MusicRadioState.Source.RADIO, null, 4L);
    var runtimeState = mock(MusicRuntimeStateStore.class);
    when(runtimeState.findRadio())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winningState));
    when(runtimeState.saveRadio(any())).thenThrow(new OptimisticLockingFailureException("stale"));
    var history = mock(MusicRadioHistoryRepository.class);
    when(history.findTop100ByOrderByStationSequenceDesc()).thenReturn(List.of());
    var savedHistory = new ArrayList<MusicRadioHistoryEvent>();
    when(history.save(any())).thenAnswer(invocation -> {
      MusicRadioHistoryEvent event = invocation.getArgument(0);
      savedHistory.add(event);
      return event;
    });
    var queue = mock(MusicQueueService.class);
    when(queue.loadForRadio()).thenReturn(MusicQueueState.empty());
    var consumedQueueEntries = new ArrayList<String>();
    doAnswer(invocation -> {
      consumedQueueEntries.add(invocation.getArgument(0));
      return null;
    }).when(queue).consumeForRadio(anyString());
    var selector = mockSelector();
    when(selector.select(anyList(), anyList(), nullable(String.class))).thenReturn(winner);

    MusicRadioSnapshot result = service(catalog, runtimeState, history, queue, selector).current();

    assertThat(result.stationSequence()).isEqualTo(winningState.stationSequence());
    assertThat(result.trackId()).isEqualTo(winner.id());
    assertThat(savedHistory).isEmpty();
    assertThat(consumedQueueEntries).isEmpty();
  }

  private MusicRadioService service(
      MusicCatalog catalog,
      MusicRuntimeStateStore runtimeState,
      MusicRadioHistoryRepository history,
      MusicQueueService queue,
      MusicRadioSelector selector) {
    var leases = mock(LeaseService.class);
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
