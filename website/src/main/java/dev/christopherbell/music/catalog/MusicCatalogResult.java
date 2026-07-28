package dev.christopherbell.music.catalog;

import java.util.List;

/** Bounded Music search response with album groups and result-local facets. */
public record MusicCatalogResult(
    List<MusicTrack> tracks,
    List<MusicAlbumGroup> albums,
    MusicFacets facets) {
}
