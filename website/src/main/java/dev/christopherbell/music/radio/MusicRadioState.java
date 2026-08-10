package dev.christopherbell.music.radio;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/** Durable identity and trusted catalog duration for the one global Music station. */
public record MusicRadioState(
    @Id String id,
    long stationSequence,
    String trackId,
    String observedToken,
    Instant startedAt,
    double durationSeconds,
    Source source,
    String queueEntryId,
    @Version Long version) {
  public static final String ID = "global";

  public MusicRadioState {
    if (!ID.equals(id) || stationSequence < 1 || trackId == null || trackId.isBlank()
        || trackId.length() > 128 || observedToken == null || observedToken.isBlank()
        || observedToken.length() > 128 || startedAt == null
        || !Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > 86_400
        || source == null
        || (source == Source.QUEUE && (queueEntryId == null || queueEntryId.isBlank()))
        || (queueEntryId != null && queueEntryId.length() > 100)
        || (source == Source.RADIO && queueEntryId != null)) {
      throw new IllegalArgumentException("Music radio state is invalid.");
    }
  }

  public enum Source {
    RADIO,
    QUEUE
  }
}
