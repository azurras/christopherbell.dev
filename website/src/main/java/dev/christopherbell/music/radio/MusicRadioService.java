package dev.christopherbell.music.radio;

import dev.christopherbell.libs.lease.LeaseService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicIndexStatus;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import dev.christopherbell.music.web.MusicTrackView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Advances the one durable Music station under a cross-instance lease and optimistic state CAS. */
@Service
public final class MusicRadioService {
  private static final String LEASE_NAME = "music-radio-transition";

  private final MusicProperties musicProperties;
  private final MusicRadioProperties radioProperties;
  private final MusicCatalog catalog;
  private final MusicRuntimeStateStore runtimeState;
  private final MusicRadioHistoryRepository history;
  private final MusicQueueService queue;
  private final MusicRadioSelector selector;
  private final MusicAccessService access;
  private final LeaseService leases;
  private final Clock clock;
  private final String leaseOwner = UUID.randomUUID().toString();
  private final Object localTransitionLock = new Object();

  public MusicRadioService(
      MusicProperties musicProperties,
      MusicRadioProperties radioProperties,
      MusicCatalog catalog,
      MusicRuntimeStateStore runtimeState,
      MusicRadioHistoryRepository history,
      MusicQueueService queue,
      MusicRadioSelector selector,
      MusicAccessService access,
      LeaseService leases,
      Clock clock) {
    this.musicProperties = musicProperties;
    this.radioProperties = radioProperties;
    this.catalog = catalog;
    this.runtimeState = runtimeState;
    this.history = history;
    this.queue = queue;
    this.selector = selector;
    this.access = access;
    this.leases = leases;
    this.clock = clock;
  }

  public MusicRadioSnapshot current() {
    access.requireRead();
    requireEnabled();
    return refreshTimeline();
  }

  /** Advances the station even when no browser is listening. */
  @Scheduled(fixedDelayString = "${app.music.radio.tick-delay:15s}")
  public void scheduledAdvance() {
    if (musicProperties.enabled()) {
      refreshTimeline();
    }
  }

  MusicRadioSnapshot refreshTimeline() {
    synchronized (localTransitionLock) {
      Instant now = clock.instant();
      if (!leases.tryAcquire(
          LEASE_NAME, leaseOwner, now, now.plus(radioProperties.leaseDuration()))) {
        return snapshot(runtimeState.findRadio().orElse(null), now);
      }
      try {
        return transitionOwned(now);
      } finally {
        leases.release(LEASE_NAME, leaseOwner);
      }
    }
  }

  private MusicRadioSnapshot transitionOwned(Instant now) {
    MusicRadioState state = runtimeState.findRadio().orElse(null);
    if (state != null && state.source() == MusicRadioState.Source.QUEUE) {
      queue.consumeForRadio(state.queueEntryId());
    }
    List<MusicTrack> candidates = catalog.radioCandidates(10_000).stream()
        .filter(this::hasTrustedDuration).toList();
    if (candidates.isEmpty() && queue.loadForRadio().entries().isEmpty()) {
      return MusicRadioSnapshot.empty();
    }
    Optional<MusicTrack> active = activeTrack(state);
    if (active.isPresent()) {
      ensureHistory(state, active.orElseThrow());
    }
    List<MusicRadioHistoryEvent> recent = new ArrayList<>(
        history.findTop100ByOrderByStationSequenceDesc());
    int transitions = 0;
    while (state == null || active.isEmpty() || ended(state).compareTo(now) <= 0) {
      if (++transitions > radioProperties.maximumCatchUpTransitions()) {
        break;
      }
      Instant startedAt = state == null || active.isEmpty() ? now : ended(state);
      long sequence = state == null ? 1 : Math.incrementExact(state.stationSequence());
      Optional<Selection> next = selectNext(candidates, recent, state, sequence, startedAt);
      if (next.isEmpty()) {
        return MusicRadioSnapshot.empty();
      }
      Selection selected = next.orElseThrow();
      MusicRadioState replacement = new MusicRadioState(
          MusicRadioState.ID,
          sequence,
          selected.track().id(),
          selected.track().observedToken(),
          startedAt,
          selected.track().durationSeconds(),
          selected.source(),
          selected.queueEntryId(),
          state == null ? null : state.version());
      try {
        state = runtimeState.saveRadio(replacement);
      } catch (OptimisticLockingFailureException | DuplicateKeyException contention) {
        return snapshot(runtimeState.findRadio().orElse(null), now);
      }
      MusicRadioHistoryEvent played = played(state, selected.track());
      saveHistoryOnce(played);
      recent.addFirst(played);
      if (selected.queueEntryId() != null) {
        queue.consumeForRadio(selected.queueEntryId());
      }
      active = Optional.of(selected.track());
    }
    return snapshot(state, now);
  }

  private Optional<Selection> selectNext(
      List<MusicTrack> candidates,
      List<MusicRadioHistoryEvent> recent,
      MusicRadioState previous,
      long nextSequence,
      Instant occurredAt) {
    MusicQueueState queueState = queue.loadForRadio();
    for (MusicQueueState.Entry entry : queueState.entries()) {
      if (previous != null && entry.id().equals(previous.queueEntryId())) {
        continue;
      }
      Optional<Selection> playableQueued = catalog.findReady(entry.trackId())
          .filter(this::hasTrustedDuration)
          .filter(queued -> entry.observedToken().equals(queued.observedToken()))
          .map(queued -> new Selection(queued, MusicRadioState.Source.QUEUE, entry.id()));
      if (playableQueued.isPresent()) {
        return playableQueued;
      }
      saveHistoryOnce(new MusicRadioHistoryEvent(
          "skip:" + entry.id(), nextSequence, entry.trackId(), entry.observedToken(), null,
          MusicRadioState.Source.QUEUE,
          MusicRadioHistoryEvent.Outcome.SKIPPED_UNPLAYABLE_QUEUE_ITEM,
          occurredAt));
      queue.consumeForRadio(entry.id());
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    String previousTrackId = previous == null ? null : previous.trackId();
    return Optional.of(new Selection(
        selector.select(candidates, recent, previousTrackId),
        MusicRadioState.Source.RADIO,
        null));
  }

  /** The track the station is playing, when it is still playable as the state recorded it. */
  private Optional<MusicTrack> activeTrack(MusicRadioState state) {
    if (state == null) {
      return Optional.empty();
    }
    return catalog.findReady(state.trackId())
        .filter(this::hasTrustedDuration)
        .filter(track -> state.observedToken().equals(track.observedToken()))
        .filter(track -> state.source() != MusicRadioState.Source.RADIO
            || !track.excludedFromRadio());
  }

  private MusicRadioSnapshot snapshot(MusicRadioState state, Instant now) {
    return activeTrack(state)
        .map(track -> playing(state, track, now))
        .orElseGet(MusicRadioSnapshot::empty);
  }

  private MusicRadioSnapshot playing(MusicRadioState state, MusicTrack track, Instant now) {
    double elapsed = Math.max(0, Duration.between(state.startedAt(), now).toMillis() / 1_000.0);
    double position = Math.min(state.durationSeconds(), elapsed);
    return new MusicRadioSnapshot(
        MusicRadioSnapshot.Status.PLAYING,
        state.stationSequence(),
        state.trackId(),
        state.observedToken(),
        state.startedAt(),
        position,
        state.durationSeconds(),
        state.source(),
        MusicTrackView.from(track));
  }

  private MusicRadioHistoryEvent played(MusicRadioState state, MusicTrack track) {
    return new MusicRadioHistoryEvent(
        "station:" + state.stationSequence(),
        state.stationSequence(),
        track.id(),
        track.observedToken(),
        track.artist(),
        state.source(),
        MusicRadioHistoryEvent.Outcome.PLAYED,
        state.startedAt());
  }

  private void saveHistoryOnce(MusicRadioHistoryEvent event) {
    try {
      history.save(event);
    } catch (DuplicateKeyException duplicate) {
      // The deterministic event id makes a completed transition idempotent across retries.
    }
  }

  private void ensureHistory(MusicRadioState state, MusicTrack track) {
    String id = "station:" + state.stationSequence();
    if (!history.existsById(id)) {
      saveHistoryOnce(played(state, track));
    }
  }

  private Instant ended(MusicRadioState state) {
    return state.startedAt().plusMillis(Math.round(state.durationSeconds() * 1_000));
  }

  private boolean hasTrustedDuration(MusicTrack track) {
    return track != null && track.indexStatus() == MusicIndexStatus.READY
        && track.missingSince() == null && track.observedToken() != null
        && Double.isFinite(track.durationSeconds()) && track.durationSeconds() > 0
        && track.durationSeconds() <= 86_400;
  }

  private void requireEnabled() {
    if (!musicProperties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Music is unavailable.");
    }
  }

  private record Selection(
      MusicTrack track,
      MusicRadioState.Source source,
      String queueEntryId) {}
}
