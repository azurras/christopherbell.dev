package dev.christopherbell.music.catalog;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Bounded read model for indexed, currently present Music tracks. */
public class MusicCatalog {
  private final MongoTemplate mongo;
  private final MusicTrackRepository tracks;

  public MusicCatalog(MongoTemplate mongo, MusicTrackRepository tracks) {
    this.mongo = mongo;
    this.tracks = tracks;
  }

  public MusicCatalogResult search(MusicQuery request) {
    var queryRequest = request == null ? new MusicQuery(null, null, null, null, 100) : request;
    var criteria = Criteria.where("missingSince").is(null)
        .and("indexStatus").is(MusicIndexStatus.READY);
    if (queryRequest.text() != null) {
      Pattern literal = Pattern.compile(
          Pattern.quote(queryRequest.text()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      criteria.andOperator(new Criteria().orOperator(
          Criteria.where("title").regex(literal),
          Criteria.where("artist").regex(literal),
          Criteria.where("albumArtist").regex(literal),
          Criteria.where("album").regex(literal),
          Criteria.where("genre").regex(literal),
          Criteria.where("path").regex(literal)));
    }
    exact(criteria, "artist", queryRequest.artist());
    exact(criteria, "album", queryRequest.album());
    exact(criteria, "genre", queryRequest.genre());
    var query = Query.query(criteria)
        .with(Sort.by(
            Sort.Order.asc("albumArtist"),
            Sort.Order.asc("album"),
            Sort.Order.asc("discNumber"),
            Sort.Order.asc("trackNumber"),
            Sort.Order.asc("title")))
        .limit(queryRequest.limit());
    List<MusicTrack> matches = List.copyOf(mongo.find(query, MusicTrack.class));
    return new MusicCatalogResult(matches, albums(matches), facets(matches));
  }

  public Optional<MusicTrack> findReady(String id) {
    if (id == null || id.isBlank()) return Optional.empty();
    return tracks.findById(id)
        .filter(track -> track.indexStatus() == MusicIndexStatus.READY)
        .filter(track -> track.missingSince() == null);
  }

  /** Returns a bounded pool of present, ready tracks eligible for smart-radio selection. */
  public List<MusicTrack> radioCandidates(int requestedLimit) {
    int limit = Math.max(1, Math.min(10_000, requestedLimit));
    Query query = Query.query(Criteria.where("missingSince").is(null)
            .and("indexStatus").is(MusicIndexStatus.READY)
            .and("excludedFromRadio").is(false))
        .with(Sort.by(Sort.Order.asc("id")))
        .limit(limit);
    return List.copyOf(mongo.find(query, MusicTrack.class));
  }

  private void exact(Criteria criteria, String field, String value) {
    if (value != null) criteria.and(field).is(value);
  }

  private List<MusicAlbumGroup> albums(List<MusicTrack> matches) {
    var groups = new LinkedHashMap<String, java.util.ArrayList<MusicTrack>>();
    for (MusicTrack track : matches) {
      String key = safe(track.albumArtist(), track.artist()) + '\n' + safe(track.album(), "Unknown Album");
      groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(track);
    }
    return groups.entrySet().stream().map(entry -> {
      String[] key = entry.getKey().split("\\n", 2);
      return new MusicAlbumGroup(key[0], key[1], List.copyOf(entry.getValue()));
    }).toList();
  }

  private MusicFacets facets(List<MusicTrack> matches) {
    return new MusicFacets(
        strings(matches.stream().map(MusicTrack::artist).toList()),
        strings(matches.stream().map(MusicTrack::album).toList()),
        strings(matches.stream().map(MusicTrack::genre).toList()),
        matches.stream().map(MusicTrack::year).filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(TreeSet::new), List::copyOf)));
  }

  private List<String> strings(List<String> values) {
    return values.stream().filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.collectingAndThen(
            java.util.stream.Collectors.toCollection(
                () -> new TreeSet<>(Comparator.comparing(
                    value -> value.toLowerCase(java.util.Locale.ROOT)))),
            List::copyOf));
  }

  private String safe(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }
}
