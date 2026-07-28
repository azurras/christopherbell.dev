package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioResponse.Playback;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument.TrackDuration;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owns the durable, in-process atomic transitions of the shared-folder radio station. */
@Service
public final class SharedFolderRadioService {
  private static final int MAX_KNOWN_DURATIONS = 10_000;

  private final SharedFolderCatalogService catalog;
  private final SharedFolderRadioRepository repository;
  private final Clock clock;
  private final IntUnaryOperator randomIndex;
  private final Object stationLock = new Object();

  /** Creates the production station using a bounded thread-local random index. */
  @Autowired
  public SharedFolderRadioService(
      SharedFolderCatalogService catalog,
      SharedFolderRadioRepository repository,
      Clock clock) {
    this(catalog, repository, clock, bound -> ThreadLocalRandom.current().nextInt(bound));
  }

  /** Creates a deterministically selectable station for focused tests. */
  SharedFolderRadioService(
      SharedFolderCatalogService catalog,
      SharedFolderRadioRepository repository,
      Clock clock,
      IntUnaryOperator randomIndex) {
    if (catalog == null || repository == null || clock == null || randomIndex == null) {
      throw new IllegalArgumentException("Radio dependencies are required");
    }
    this.catalog = catalog;
    this.repository = repository;
    this.clock = clock;
    this.randomIndex = randomIndex;
  }

  /** Returns the current station state, advancing an expired known-duration track when needed. */
  public SharedFolderRadioResponse current() {
    List<SharedDirectoryEntry> tracks = catalog.audioTracksBelowMusic();
    synchronized (stationLock) {
      SharedFolderRadioDocument current = repository
          .findById(SharedFolderRadioDocument.ID)
          .orElse(null);
      Instant now = clock.instant();
      return transition(tracks, current, now, null);
    }
  }

  /** Accepts a matching bounded duration report and returns the resulting station state. */
  public SharedFolderRadioResponse reportDuration(SharedFolderRadioDurationRequest request) {
    requireValidReport(request);
    List<SharedDirectoryEntry> tracks = catalog.audioTracksBelowMusic();
    synchronized (stationLock) {
      SharedFolderRadioDocument current = repository
          .findById(SharedFolderRadioDocument.ID)
          .orElseThrow(this::staleReport);
      if (current.state() == SharedFolderRadioDocument.State.EMPTY) {
        throw staleReport();
      }
      SharedDirectoryEntry activeTrack = findTrack(tracks, current.path());
      if (activeTrack == null) {
        saveEmpty(current);
        throw staleReport();
      }
      if (request.stationSequence() != current.stationSequence()
          || !request.path().equals(current.path())) {
        throw staleReport();
      }
      Instant now = clock.instant();
      return transition(tracks, current, now, request.durationSeconds());
    }
  }

  private void requireValidReport(SharedFolderRadioDurationRequest request) {
    if (request == null || request.stationSequence() < 1
        || !SharedFolderRadioDurationRequest.isValidDuration(request.durationSeconds())) {
      throw invalidReport();
    }
    try {
      SharedFolderPathResolver.safeRelativeSegments(request.path(), false);
    } catch (RuntimeException exception) {
      throw invalidReport();
    }
  }

  private SharedFolderRadioResponse transition(
      List<SharedDirectoryEntry> tracks,
      SharedFolderRadioDocument current,
      Instant now,
      Double observedDuration) {
    if (tracks.isEmpty()) {
      saveEmpty(current);
      return SharedFolderRadioResponse.empty();
    }
    SharedDirectoryEntry activeTrack = current == null
        || current.state() == SharedFolderRadioDocument.State.EMPTY
        ? null : findTrack(tracks, current.path());
    if (current == null || current.state() == SharedFolderRadioDocument.State.EMPTY
        || activeTrack == null) {
      long sequence = current == null ? 1 : Math.incrementExact(current.stationSequence());
      String previousPath = current == null
          || current.state() == SharedFolderRadioDocument.State.EMPTY ? null : current.path();
      KnownDurations knownDurations = KnownDurations.from(current);
      SharedDirectoryEntry selected = selectTrack(tracks, previousPath);
      return saveAndRespond(selected, sequence, now, knownDurations.find(selected),
          knownDurations.values(), now);
    }
    KnownDurations knownDurations = KnownDurations.from(current);
    if (observedDuration != null) {
      knownDurations.record(activeTrack, observedDuration);
    }
    Double durationSeconds = observedDuration != null
        ? observedDuration : knownDurations.find(activeTrack);
    SharedFolderRadioDocument resolved = SharedFolderRadioDocument.playing(
        current.stationSequence(), current.path(), current.startedAt(), durationSeconds,
        knownDurations.values());
    boolean changed = observedDuration != null
        || !Objects.equals(current.durationSeconds(), durationSeconds)
        || !current.knownDurations().equals(resolved.knownDurations());
    while (resolved.durationSeconds() != null) {
      Instant priorEnd = resolved.startedAt().plusMillis(
          Math.round(resolved.durationSeconds() * 1_000));
      if (priorEnd.isAfter(now)) {
        break;
      }
      activeTrack = selectTrack(tracks, resolved.path());
      resolved = SharedFolderRadioDocument.playing(
          Math.incrementExact(resolved.stationSequence()), activeTrack.path(), priorEnd,
          knownDurations.find(activeTrack), knownDurations.values());
      changed = true;
    }
    SharedFolderRadioDocument saved = changed ? repository.save(resolved) : resolved;
    return respond(saved, activeTrack, now);
  }

  private SharedFolderRadioDocument saveEmpty(SharedFolderRadioDocument current) {
    if (current != null && current.state() == SharedFolderRadioDocument.State.EMPTY) {
      return current;
    }
    long sequence = current == null ? 1 : Math.incrementExact(current.stationSequence());
    List<TrackDuration> knownDurations = current == null ? List.of() : current.knownDurations();
    return repository.save(SharedFolderRadioDocument.empty(sequence, knownDurations));
  }

  private SharedFolderRadioResponse saveAndRespond(
      SharedDirectoryEntry track,
      long sequence,
      Instant startedAt,
      Double durationSeconds,
      List<TrackDuration> knownDurations,
      Instant now) {
    SharedFolderRadioDocument saved = repository.save(SharedFolderRadioDocument.playing(
        sequence, track.path(), startedAt, durationSeconds, knownDurations));
    return respond(saved, track, now);
  }

  private SharedFolderRadioResponse respond(
      SharedFolderRadioDocument document,
      SharedDirectoryEntry track,
      Instant now) {
    double positionSeconds = Math.max(0, Duration.between(document.startedAt(), now).toMillis()
        / 1_000.0);
    return SharedFolderRadioResponse.playing(new Playback(
        document.stationSequence(), document.startedAt(), positionSeconds,
        document.durationSeconds(), track));
  }

  private SharedDirectoryEntry findTrack(List<SharedDirectoryEntry> tracks, String path) {
    if (path == null) {
      return null;
    }
    return tracks.stream().filter(track -> path.equals(track.path())).findFirst().orElse(null);
  }

  private SharedDirectoryEntry selectTrack(List<SharedDirectoryEntry> tracks, String previousPath) {
    List<SharedDirectoryEntry> candidates = tracks.size() < 2 || previousPath == null
        ? tracks
        : tracks.stream().filter(track -> !previousPath.equals(track.path())).toList();
    int selected = randomIndex.applyAsInt(candidates.size());
    if (selected < 0 || selected >= candidates.size()) {
      throw new IllegalStateException("Radio random index is outside its requested bound");
    }
    return candidates.get(selected);
  }

  private ResponseStatusException staleReport() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Radio duration report is stale");
  }

  private ResponseStatusException invalidReport() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Radio duration report is invalid");
  }

  private static final class KnownDurations {
    private final LinkedHashMap<String, TrackDuration> byObservedToken;

    private KnownDurations(LinkedHashMap<String, TrackDuration> byObservedToken) {
      this.byObservedToken = byObservedToken;
    }

    private static KnownDurations from(SharedFolderRadioDocument document) {
      LinkedHashMap<String, TrackDuration> durations = new LinkedHashMap<>();
      if (document != null) {
        for (TrackDuration duration : document.knownDurations()) {
          durations.put(duration.observedToken(), duration);
        }
      }
      return new KnownDurations(durations);
    }

    private Double find(SharedDirectoryEntry track) {
      TrackDuration duration = byObservedToken.get(track.observedToken());
      return duration != null && duration.path().equals(track.path())
          ? duration.durationSeconds() : null;
    }

    private void record(SharedDirectoryEntry track, double durationSeconds) {
      if (track.observedToken() == null || track.observedToken().isBlank()) {
        return;
      }
      byObservedToken.remove(track.observedToken());
      byObservedToken.put(track.observedToken(),
          new TrackDuration(track.path(), track.observedToken(), durationSeconds));
      while (byObservedToken.size() > MAX_KNOWN_DURATIONS) {
        String oldest = byObservedToken.keySet().iterator().next();
        byObservedToken.remove(oldest);
      }
    }

    private List<TrackDuration> values() {
      return List.copyOf(byObservedToken.values());
    }
  }
}
