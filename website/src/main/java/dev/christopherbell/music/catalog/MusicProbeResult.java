package dev.christopherbell.music.catalog;

/** Validated FFprobe metadata safe for durable catalog persistence. */
public record MusicProbeResult(
    String title,
    String artist,
    String albumArtist,
    String album,
    Integer trackNumber,
    Integer discNumber,
    String genre,
    Integer year,
    double durationSeconds,
    String audioCodec,
    String container,
    boolean hasArtwork) {
}
