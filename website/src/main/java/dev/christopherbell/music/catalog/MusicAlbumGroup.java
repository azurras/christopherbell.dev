package dev.christopherbell.music.catalog;

import java.util.List;

/** Tracks grouped by album artist and album for responsive library rendering. */
public record MusicAlbumGroup(
    String albumArtist,
    String album,
    List<MusicTrack> tracks) {
}
