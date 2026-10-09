package dev.christopherbell.music.catalog;

/** The two listener-controlled flags on one Music track. */
public record MusicTrackPreferences(boolean favorite, boolean excludedFromRadio) {
}
