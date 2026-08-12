package dev.christopherbell.music.radio;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;

/** Idempotent station transition or skipped-queue event shared by every listener. */
public record MusicRadioHistoryEvent(
    @Id String id,
    @Indexed long stationSequence,
    String trackId,
    String observedToken,
    String artist,
    MusicRadioState.Source source,
    Outcome outcome,
    @Indexed Instant occurredAt) {

  public enum Outcome {
    PLAYED,
    SKIPPED_UNPLAYABLE_QUEUE_ITEM
  }
}
