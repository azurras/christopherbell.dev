package dev.christopherbell.sharedfolder.model;

import java.time.Instant;

/** Public-safe state of the shared-folder radio station. */
public record SharedFolderRadioResponse(Status status, Playback playback) {
  /** Ensures the status and optional playback value always describe the same valid state. */
  public SharedFolderRadioResponse {
    if (status == null) {
      throw new IllegalArgumentException("Radio status is required");
    }
    if ((status == Status.EMPTY) != (playback == null)) {
      throw new IllegalArgumentException("Radio status and playback do not match");
    }
  }

  /** Creates the single valid response for a catalog with no playable tracks. */
  public static SharedFolderRadioResponse empty() {
    return new SharedFolderRadioResponse(Status.EMPTY, null);
  }

  /** Creates a playing response from an already validated playback value. */
  public static SharedFolderRadioResponse playing(Playback playback) {
    return new SharedFolderRadioResponse(Status.PLAYING, playback);
  }

  /** Closed set of valid station states. */
  public enum Status {
    EMPTY,
    PLAYING
  }

  /** One active station track with its server-owned timing information. */
  public record Playback(
      long stationSequence,
      Instant startedAt,
      double positionSeconds,
      Double durationSeconds,
      SharedDirectoryEntry entry) {
    /** Prevents malformed playback values from reaching HTTP consumers. */
    public Playback {
      if (stationSequence < 1 || startedAt == null || entry == null) {
        throw new IllegalArgumentException("Radio playback identity is invalid");
      }
      if (!Double.isFinite(positionSeconds) || positionSeconds < 0) {
        throw new IllegalArgumentException("Radio playback position is invalid");
      }
      if (durationSeconds != null
          && !SharedFolderRadioDurationRequest.isValidDuration(durationSeconds)) {
        throw new IllegalArgumentException("Radio playback duration is invalid");
      }
      if (entry.type() != SharedDirectoryEntryType.FILE
          || entry.previewKind() != SharedFolderPreviewKind.AUDIO) {
        throw new IllegalArgumentException("Radio playback entry is not audio");
      }
    }
  }
}
