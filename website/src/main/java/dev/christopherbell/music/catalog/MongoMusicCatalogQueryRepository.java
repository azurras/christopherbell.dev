package dev.christopherbell.music.catalog;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** MongoDB query implementation for the bounded Music catalog. */
@MongoPersistence
public class MongoMusicCatalogQueryRepository implements MusicCatalogQueryRepository {
  private final KindScopedMongoOperations<MusicTrack> mongo;

  public MongoMusicCatalogQueryRepository(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(MusicTrack.class);
  }

  @Override
  public MusicCatalogResult search(MusicQuery request) {
    MusicQuery queryRequest = request == null
        ? new MusicQuery(null, null, null, null, null, null, 0, 50) : request;
    long totalTracks = mongo.count(Query.query(criteria(queryRequest)));
    int totalPages = MusicCatalogResultSupport.totalPages(totalTracks, queryRequest.size());
    int page = totalPages == 0 ? 0 : Math.min(queryRequest.page(), totalPages - 1);
    Query query = Query.query(criteria(queryRequest))
        .with(Sort.by(Sort.Order.asc("albumArtist"), Sort.Order.asc("album"),
            Sort.Order.asc("discNumber"), Sort.Order.asc("trackNumber"),
            Sort.Order.asc("title"), Sort.Order.asc("id")))
        .skip((long) page * queryRequest.size()).limit(queryRequest.size());
    List<MusicTrack> matches = mongo.find(query, Pageable.unpaged());
    return new MusicCatalogResult(matches, MusicCatalogResultSupport.albums(matches), facets(queryRequest), page,
        queryRequest.size(), totalTracks, totalPages);
  }

  @Override
  public List<MusicTrack> radioCandidates(int requestedLimit) {
    int limit = Math.max(1, Math.min(10_000, requestedLimit));
    Query query = Query.query(Criteria.where("missingSince").is(null)
            .and("indexStatus").is(MusicIndexStatus.READY).and("excludedFromRadio").is(false))
        .with(Sort.by(Sort.Order.asc("id"))).limit(limit);
    return mongo.find(query, Pageable.unpaged());
  }

  private Criteria criteria(MusicQuery request) {
    var filters = new ArrayList<Criteria>();
    filters.add(Criteria.where("missingSince").is(null));
    filters.add(Criteria.where("indexStatus").is(MusicIndexStatus.READY));
    if (request.text() != null) {
      Pattern literal = Pattern.compile(Pattern.quote(request.text()),
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      filters.add(new Criteria().orOperator(Criteria.where("title").regex(literal),
          Criteria.where("artist").regex(literal), Criteria.where("albumArtist").regex(literal),
          Criteria.where("album").regex(literal), Criteria.where("genre").regex(literal),
          Criteria.where("path").regex(literal)));
    }
    exact(filters, "artist", request.artist());
    exact(filters, "album", request.album());
    exact(filters, "genre", request.genre());
    if (request.favorite() != null) filters.add(Criteria.where("favorite").is(request.favorite()));
    if (request.trackIds() != null) filters.add(Criteria.where("id").in(request.trackIds()));
    return new Criteria().andOperator(filters);
  }

  private MusicFacets facets(MusicQuery request) {
    return new MusicFacets(MusicCatalogResultSupport.strings(distinct(request, "artist", String.class)),
        MusicCatalogResultSupport.strings(distinct(request, "album", String.class)),
        MusicCatalogResultSupport.strings(distinct(request, "genre", String.class)),
        distinct(request, "year", Integer.class).stream().filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(TreeSet::new), List::copyOf)));
  }

  private <T> List<T> distinct(MusicQuery request, String field, Class<T> type) {
    var aggregation = Aggregation.newAggregation(Aggregation.match(criteria(request)),
        Aggregation.group(field), Aggregation.sort(Sort.by(Sort.Direction.ASC, "_id")));
    return mongo.aggregate(KindScopedAggregation.local(aggregation), Document.class).stream()
        .map(document -> document.get("_id")).filter(type::isInstance).map(type::cast).toList();
  }

  private static void exact(List<Criteria> filters, String field, String value) {
    if (value != null) filters.add(Criteria.where(field).is(value));
  }

}
