package dev.christopherbell.music.catalog;

import java.util.List;

/** Facet values represented by the current bounded search result. */
public record MusicFacets(
    List<String> artists,
    List<String> albums,
    List<String> genres,
    List<Integer> years) {
}
