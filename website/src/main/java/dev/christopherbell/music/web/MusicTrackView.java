package dev.christopherbell.music.web;

import dev.christopherbell.music.catalog.MusicTrack;

/** Public Music metadata that intentionally omits disk paths and index revision internals. */
public record MusicTrackView(
    String id,
    String observedToken,
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
    boolean artworkAvailable,
    boolean favorite,
    boolean excludedFromRadio) {

  public static MusicTrackView from(MusicTrack track) {
    return new MusicTrackView(
        track.id(), track.observedToken(), track.title(), track.artist(), track.albumArtist(), track.album(),
        track.trackNumber(), track.discNumber(), track.genre(), track.year(),
        track.durationSeconds(), track.audioCodec(), track.container(),
        track.artworkRevision() != null, track.favorite(), track.excludedFromRadio());
  }
}
