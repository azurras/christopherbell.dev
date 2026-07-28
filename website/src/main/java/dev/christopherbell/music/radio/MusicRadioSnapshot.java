package dev.christopherbell.music.radio;

import dev.christopherbell.music.web.MusicTrackView;
import java.time.Instant;

/** Public shared-station state calculated from one durable server timeline. */
public record MusicRadioSnapshot(
    Status status,
    Long stationSequence,
    String trackId,
    String observedToken,
    Instant startedAt,
    Double positionSeconds,
    Double durationSeconds,
    MusicRadioState.Source source,
    MusicTrackView track) {

  public static MusicRadioSnapshot empty() {
    return new MusicRadioSnapshot(Status.EMPTY, null, null, null, null, null, null, null, null);
  }

  public enum Status {
    EMPTY,
    PLAYING
  }
}
