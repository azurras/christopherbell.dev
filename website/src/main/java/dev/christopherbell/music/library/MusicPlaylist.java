package dev.christopherbell.music.library;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One global optimistic playlist shared by every Music listener. */
@Document("music_playlists")
public record MusicPlaylist(
    @Id String id,
    @Indexed(unique = true) String normalizedName,
    String name,
    List<String> trackIds,
    @Version Long version,
    String updatedByAccountId,
    Instant updatedAt) {

  public MusicPlaylist {
    trackIds = trackIds == null ? List.of() : List.copyOf(trackIds);
    if (id == null || id.isBlank() || normalizedName == null || normalizedName.isBlank()
        || name == null || name.isBlank() || name.length() > 100
        || trackIds.size() > 1_000 || trackIds.stream().distinct().count() != trackIds.size()
        || updatedByAccountId == null || updatedByAccountId.isBlank() || updatedAt == null) {
      throw new IllegalArgumentException("Music playlist is invalid.");
    }
  }

  public long publicVersion() {
    return version == null ? 0 : Math.incrementExact(version);
  }
}
