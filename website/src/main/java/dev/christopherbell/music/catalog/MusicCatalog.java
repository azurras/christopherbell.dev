package dev.christopherbell.music.catalog;

import java.util.ArrayList;
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
    var queryRequest = request == null
        ? new MusicQuery(null, null, null, null, null, null, 0, 50)
        : request;
    long totalTracks = mongo.count(Query.query(criteria(queryRequest)), MusicTrack.class);
    int totalPages = totalPages(totalTracks, queryRequest.size());
    int page = totalPages == 0 ? 0 : Math.min(queryRequest.page(), totalPages - 1);
    var query = Query.query(criteria(queryRequest))
        .with(Sort.by(
            Sort.Order.asc("albumArtist"),
            Sort.Order.asc("album"),
            Sort.Order.asc("discNumber"),
            Sort.Order.asc("trackNumber"),
            Sort.Order.asc("title"),
            Sort.Order.asc("id")))
        .skip((long) page * queryRequest.size())
        .limit(queryRequest.size());
    List<MusicTrack> matches = List.copyOf(mongo.find(query, MusicTrack.class));
    return new MusicCatalogResult(
        matches, albums(matches), facets(queryRequest),
        page, queryRequest.size(), totalTracks, totalPages);
  }

  private Criteria criteria(MusicQuery queryRequest) {
    var filters = new ArrayList<Criteria>();
    filters.add(Criteria.where("missingSince").is(null));
    filters.add(Criteria.where("indexStatus").is(MusicIndexStatus.READY));
    if (queryRequest.text() != null) {
      Pattern literal = Pattern.compile(
          Pattern.quote(queryRequest.text()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      filters.add(new Criteria().orOperator(
          Criteria.where("title").regex(literal),
          Criteria.where("artist").regex(literal),
          Criteria.where("albumArtist").regex(literal),
          Criteria.where("album").regex(literal),
          Criteria.where("genre").regex(literal),
          Criteria.where("path").regex(literal)));
    }
    exact(filters, "artist", queryRequest.artist());
    exact(filters, "album", queryRequest.album());
    exact(filters, "genre", queryRequest.genre());
    if (queryRequest.favorite() != null) {
      filters.add(Criteria.where("favorite").is(queryRequest.favorite()));
    }
    if (queryRequest.trackIds() != null) {
      filters.add(Criteria.where("id").in(queryRequest.trackIds()));
    }
    return new Criteria().andOperator(filters);
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

  private void exact(List<Criteria> filters, String field, String value) {
    if (value != null) filters.add(Criteria.where(field).is(value));
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

  private MusicFacets facets(MusicQuery queryRequest) {
    return new MusicFacets(
        strings(distinct(queryRequest, "artist", String.class)),
        strings(distinct(queryRequest, "album", String.class)),
        strings(distinct(queryRequest, "genre", String.class)),
        distinct(queryRequest, "year", Integer.class).stream()
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(TreeSet::new), List::copyOf)));
  }

  private <T> List<T> distinct(MusicQuery queryRequest, String field, Class<T> type) {
    return mongo.findDistinct(
        Query.query(criteria(queryRequest)), field, MusicTrack.class, type);
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

  private int totalPages(long totalTracks, int size) {
    long pages = totalTracks / size + (totalTracks % size == 0 ? 0 : 1);
    return Math.toIntExact(pages);
  }
}
