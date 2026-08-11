package dev.christopherbell.music.catalog;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for indexed Music tracks. */
public interface MusicTrackRepository {
  MusicTrack save(MusicTrack track);

  Optional<MusicTrack> findById(String id);

  Optional<MusicTrack> findByPath(String path);

  List<MusicTrack> findAllByMissingSinceIsNull();

  boolean updatePreferences(
      String id,
      boolean expectedFavorite,
      boolean expectedExcluded,
      boolean favorite,
      boolean excluded);
}
