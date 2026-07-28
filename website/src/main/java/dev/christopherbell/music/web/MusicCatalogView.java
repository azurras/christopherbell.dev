package dev.christopherbell.music.web;

import dev.christopherbell.music.catalog.MusicCatalogResult;
import dev.christopherbell.music.catalog.MusicFacets;
import java.util.List;

/** Bounded paged Music catalog response safe to expose to authorized listeners. */
public record MusicCatalogView(
    List<MusicTrackView> tracks,
    MusicFacets facets,
    int page,
    int size,
    long totalTracks,
    int totalPages) {
  public static MusicCatalogView from(MusicCatalogResult result) {
    return new MusicCatalogView(
        result.tracks().stream().map(MusicTrackView::from).toList(), result.facets(),
        result.page(), result.size(), result.totalTracks(), result.totalPages());
  }
}
