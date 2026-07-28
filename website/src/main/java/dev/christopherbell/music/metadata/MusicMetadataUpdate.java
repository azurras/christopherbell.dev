package dev.christopherbell.music.metadata;

/** Exact desired values for the approved writable Music tags and embedded artwork. */
public record MusicMetadataUpdate(
    String expectedObservedToken,
    String title,
    String artist,
    String albumArtist,
    String album,
    Integer trackNumber,
    Integer discNumber,
    String genre,
    Integer year,
    String artworkDataUrl,
    boolean removeArtwork) {
}
