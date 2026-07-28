package dev.christopherbell.music.catalog;

import java.util.List;

/** Bounded catalog search, server-side view constraint, and page request. */
public record MusicQuery(
    String text,
    String artist,
    String album,
    String genre,
    Boolean favorite,
    List<String> trackIds,
    int page,
    int size) {
  private static final int MAX_TEXT = 100;
  private static final int MAX_FACET = 300;

  public MusicQuery {
    text = bounded(text, MAX_TEXT);
    artist = bounded(artist, MAX_FACET);
    album = bounded(album, MAX_FACET);
    genre = bounded(genre, MAX_FACET);
    trackIds = trackIds == null ? null : List.copyOf(trackIds);
    if (trackIds != null && (trackIds.size() > 1_000
        || trackIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128))) {
      throw new IllegalArgumentException("Music track filter is invalid.");
    }
    page = Math.max(0, page);
    size = Math.max(1, Math.min(100, size));
  }

  private static String bounded(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String clean = value.strip();
    return clean.length() <= maximum ? clean : clean.substring(0, maximum);
  }
}
