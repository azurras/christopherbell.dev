package dev.christopherbell.music.radio;

import dev.christopherbell.music.web.MusicTrackView;
import java.time.Instant;
import java.util.List;

/** Global queue response with one optimistic version shared by every writer. */
public record MusicQueueView(long version, List<Item> items) {
  public record Item(
      String id,
      MusicTrackView track,
      String enqueuedByAccountId,
      Instant enqueuedAt) {}
}
