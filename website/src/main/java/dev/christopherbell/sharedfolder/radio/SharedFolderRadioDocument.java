package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Fixed-key durable state for the one shared-folder radio station. */
@Document("shared_folder_radio")
public record SharedFolderRadioDocument(
    @Id String id,
    State state,
    long stationSequence,
    String path,
    Instant startedAt,
    Double durationSeconds,
    List<TrackDuration> knownDurations) {
  public static final String ID = "shared-folder-radio";

  /** Rejects malformed persisted state before station transitions rely on it. */
  public SharedFolderRadioDocument {
    if (!ID.equals(id) || stationSequence < 1) {
      throw new IllegalArgumentException("Shared-folder radio document is invalid");
    }
    if (state == null && path != null && startedAt != null) {
      state = State.PLAYING;
    }
    if (state == State.EMPTY
        && (path != null || startedAt != null || durationSeconds != null)) {
      throw new IllegalArgumentException("Empty shared-folder radio state has playback data");
    }
    if (state != State.EMPTY && state != State.PLAYING) {
      throw new IllegalArgumentException("Shared-folder radio state is required");
    }
    if (state == State.PLAYING
        && (path == null || path.isBlank() || startedAt == null)) {
      throw new IllegalArgumentException("Playing shared-folder radio state is incomplete");
    }
    if (state == State.PLAYING && durationSeconds != null
        && !SharedFolderRadioDurationRequest.isValidDuration(durationSeconds)) {
      throw new IllegalArgumentException("Shared-folder radio duration is invalid");
    }
    knownDurations = knownDurations == null ? List.of() : List.copyOf(knownDurations);
    Set<String> observedTokens = new HashSet<>();
    for (TrackDuration knownDuration : knownDurations) {
      if (knownDuration == null || !observedTokens.add(knownDuration.observedToken())) {
        throw new IllegalArgumentException("Shared-folder radio duration cache is invalid");
      }
    }
  }

  /** Preserves construction of the playing-only document shape used before empty tombstones. */
  public SharedFolderRadioDocument(
      String id,
      long stationSequence,
      String path,
      Instant startedAt,
      Double durationSeconds) {
    this(id, State.PLAYING, stationSequence, path, startedAt, durationSeconds, List.of());
  }

  /** Preserves construction of the stateful document shape used before duration caching. */
  public SharedFolderRadioDocument(
      String id,
      State state,
      long stationSequence,
      String path,
      Instant startedAt,
      Double durationSeconds) {
    this(id, state, stationSequence, path, startedAt, durationSeconds, List.of());
  }

  /** Creates a durable empty station identity with no playback fields. */
  public static SharedFolderRadioDocument empty(long stationSequence) {
    return new SharedFolderRadioDocument(
        ID, State.EMPTY, stationSequence, null, null, null, List.of());
  }

  /** Creates an empty station identity without discarding safe revision-bound durations. */
  public static SharedFolderRadioDocument empty(
      long stationSequence,
      List<TrackDuration> knownDurations) {
    return new SharedFolderRadioDocument(
        ID, State.EMPTY, stationSequence, null, null, null, knownDurations);
  }

  /** Creates a durable playing station identity. */
  public static SharedFolderRadioDocument playing(
      long stationSequence,
      String path,
      Instant startedAt,
      Double durationSeconds) {
    return new SharedFolderRadioDocument(
        ID, State.PLAYING, stationSequence, path, startedAt, durationSeconds, List.of());
  }

  /** Creates a playing station with its bounded, revision-bound duration knowledge. */
  public static SharedFolderRadioDocument playing(
      long stationSequence,
      String path,
      Instant startedAt,
      Double durationSeconds,
      List<TrackDuration> knownDurations) {
    return new SharedFolderRadioDocument(
        ID, State.PLAYING, stationSequence, path, startedAt, durationSeconds, knownDurations);
  }

  /** One trusted duration observation tied to an exact catalog entry revision. */
  public record TrackDuration(String path, String observedToken, double durationSeconds) {
    public TrackDuration {
      if (path == null || path.isBlank() || observedToken == null || observedToken.isBlank()
          || !SharedFolderRadioDurationRequest.isValidDuration(durationSeconds)) {
        throw new IllegalArgumentException("Shared-folder radio track duration is invalid");
      }
    }
  }

  /** Closed set of durable station states. */
  public enum State {
    EMPTY,
    PLAYING
  }
}
