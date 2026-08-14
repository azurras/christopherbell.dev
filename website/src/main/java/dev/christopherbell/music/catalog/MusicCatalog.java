package dev.christopherbell.music.catalog;

import java.util.List;
import java.util.Optional;

/** Bounded read model for indexed, currently present Music tracks. */
public class MusicCatalog {
  private final MusicCatalogQueryRepository queries;
  private final MusicTrackRepository tracks;

  public MusicCatalog(MusicCatalogQueryRepository queries, MusicTrackRepository tracks) {
    this.queries = queries;
    this.tracks = tracks;
  }

  public MusicCatalogResult search(MusicQuery request) {
    return queries.search(request);
  }

  public Optional<MusicTrack> findReady(String id) {
    if (id == null || id.isBlank()) return Optional.empty();
    return tracks.findById(id).filter(track -> track.indexStatus() == MusicIndexStatus.READY)
        .filter(track -> track.missingSince() == null);
  }

  public List<MusicTrack> radioCandidates(int requestedLimit) {
    return queries.radioCandidates(requestedLimit);
  }
}
