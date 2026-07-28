package dev.christopherbell.music.radio;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** One optimistic, globally ordered Music queue with no per-user silo. */
@Document("music_queue_state")
public record MusicQueueState(
    @Id String id,
    List<Entry> entries,
    @Version Long version) {
  public static final String ID = "global";

  public MusicQueueState {
    if (!ID.equals(id)) {
      throw new IllegalArgumentException("Music queue identity is invalid.");
    }
    entries = entries == null ? List.of() : List.copyOf(entries);
    if (entries.size() > 1_000 || entries.stream().map(Entry::id).distinct().count() != entries.size()) {
      throw new IllegalArgumentException("Music queue is invalid.");
    }
  }

  public static MusicQueueState empty() {
    return new MusicQueueState(ID, List.of(), null);
  }

  public long publicVersion() {
    return version == null ? 0 : Math.incrementExact(version);
  }

  public record Entry(
      String id,
      String trackId,
      String observedToken,
      String enqueuedByAccountId,
      Instant enqueuedAt) {
    public Entry {
      if (id == null || id.isBlank() || id.length() > 100
          || trackId == null || trackId.isBlank() || trackId.length() > 128
          || observedToken == null || observedToken.isBlank() || observedToken.length() > 128
          || enqueuedByAccountId == null || enqueuedByAccountId.isBlank()
          || enqueuedByAccountId.length() > 128
          || enqueuedAt == null) {
        throw new IllegalArgumentException("Music queue entry is invalid.");
      }
    }
  }
}
