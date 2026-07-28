package dev.christopherbell.music.web;

import dev.christopherbell.music.catalog.MusicCatalogResult;
import dev.christopherbell.music.catalog.MusicFacets;
import java.util.List;

/** Bounded Music catalog response safe to expose to authorized listeners. */
public record MusicCatalogView(List<MusicTrackView> tracks, MusicFacets facets) {
  public static MusicCatalogView from(MusicCatalogResult result) {
    return new MusicCatalogView(
        result.tracks().stream().map(MusicTrackView::from).toList(), result.facets());
  }
}
