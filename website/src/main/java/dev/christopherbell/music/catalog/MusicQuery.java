package dev.christopherbell.music.catalog;

/** Bounded catalog search and optional exact facets. */
public record MusicQuery(
    String text,
    String artist,
    String album,
    String genre,
    int limit) {
  private static final int MAX_TEXT = 100;
  private static final int MAX_FACET = 300;

  public MusicQuery {
    text = bounded(text, MAX_TEXT);
    artist = bounded(artist, MAX_FACET);
    album = bounded(album, MAX_FACET);
    genre = bounded(genre, MAX_FACET);
    limit = Math.max(1, Math.min(100, limit));
  }

  private static String bounded(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String clean = value.strip();
    return clean.length() <= maximum ? clean : clean.substring(0, maximum);
  }
}
