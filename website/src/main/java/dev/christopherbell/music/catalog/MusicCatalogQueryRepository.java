package dev.christopherbell.music.catalog;

import java.util.List;

/** Persistence-neutral bounded query boundary for the Music catalog. */
public interface MusicCatalogQueryRepository {
  MusicCatalogResult search(MusicQuery query);

  List<MusicTrack> radioCandidates(int limit);
}
