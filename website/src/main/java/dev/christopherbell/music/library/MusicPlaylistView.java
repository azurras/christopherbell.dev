package dev.christopherbell.music.library;

import java.time.Instant;
import java.util.List;

public record MusicPlaylistView(
    String id,
    String name,
    List<String> trackIds,
    long version,
    String updatedByAccountId,
    Instant updatedAt) {
  static MusicPlaylistView from(MusicPlaylist playlist) {
    return new MusicPlaylistView(
        playlist.id(), playlist.name(), playlist.trackIds(), playlist.publicVersion(),
        playlist.updatedByAccountId(), playlist.updatedAt());
  }
}
