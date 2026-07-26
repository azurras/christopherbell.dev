package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioResponse.Playback;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owns the durable, in-process atomic transitions of the shared-folder radio station. */
@Service
public final class SharedFolderRadioService {
  private static final Duration STALE_END_THRESHOLD = Duration.ofSeconds(3);

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
      if (request.stationSequence() != current.stationSequence()
          || !request.path().equals(current.path())) {
        throw staleReport();
      }
      Instant now = clock.instant();
      SharedFolderRadioDocument withDuration = new SharedFolderRadioDocument(
          current.id(), current.stationSequence(), current.path(), current.startedAt(),
          request.durationSeconds());
      return transition(tracks, withDuration, now, withDuration);
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
      SharedFolderRadioDocument pendingSave) {
    if (tracks.isEmpty()) {
      return SharedFolderRadioResponse.empty();
    }
    SharedDirectoryEntry activeTrack = findTrack(tracks, current == null ? null : current.path());
    if (current == null || activeTrack == null) {
      long sequence = current == null ? 1 : Math.incrementExact(current.stationSequence());
      return saveAndRespond(selectTrack(tracks, current == null ? null : current.path()),
          sequence, now, null, now);
    }
    if (current.durationSeconds() != null) {
      Instant priorEnd = current.startedAt().plusMillis(
          Math.round(current.durationSeconds() * 1_000));
      if (!priorEnd.isAfter(now)) {
        Instant replacementStart = priorEnd.isBefore(now.minus(STALE_END_THRESHOLD))
            ? now : priorEnd;
        return saveAndRespond(selectTrack(tracks, current.path()),
            Math.incrementExact(current.stationSequence()), replacementStart, null, now);
      }
    }
    if (pendingSave != null) {
      repository.save(pendingSave);
    }
    return respond(current, activeTrack, now);
  }

  private SharedFolderRadioResponse saveAndRespond(
      SharedDirectoryEntry track,
      long sequence,
      Instant startedAt,
      Double durationSeconds,
      Instant now) {
    SharedFolderRadioDocument saved = repository.save(new SharedFolderRadioDocument(
        SharedFolderRadioDocument.ID, sequence, track.path(), startedAt, durationSeconds));
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
}
