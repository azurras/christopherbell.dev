package dev.christopherbell.music.library;

import java.util.List;
import java.util.Optional;

/** Persistence port for global optimistic music playlists. */
public interface MusicPlaylistRepository {
  MusicPlaylist save(MusicPlaylist playlist);
  Optional<MusicPlaylist> findById(String id);
  List<MusicPlaylist> findTop100ByOrderByNormalizedNameAsc();
  long count();
  void delete(MusicPlaylist playlist);
}
