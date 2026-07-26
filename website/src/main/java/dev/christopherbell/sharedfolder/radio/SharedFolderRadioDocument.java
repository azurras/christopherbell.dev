package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import java.time.Instant;
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
    Double durationSeconds) {
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
  }

  /** Preserves construction of the playing-only document shape used before empty tombstones. */
  public SharedFolderRadioDocument(
      String id,
      long stationSequence,
      String path,
      Instant startedAt,
      Double durationSeconds) {
    this(id, State.PLAYING, stationSequence, path, startedAt, durationSeconds);
  }

  /** Creates a durable empty station identity with no playback fields. */
  public static SharedFolderRadioDocument empty(long stationSequence) {
    return new SharedFolderRadioDocument(
        ID, State.EMPTY, stationSequence, null, null, null);
  }

  /** Creates a durable playing station identity. */
  public static SharedFolderRadioDocument playing(
      long stationSequence,
      String path,
      Instant startedAt,
      Double durationSeconds) {
    return new SharedFolderRadioDocument(
        ID, State.PLAYING, stationSequence, path, startedAt, durationSeconds);
  }

  /** Closed set of durable station states. */
  public enum State {
    EMPTY,
    PLAYING
  }
}
