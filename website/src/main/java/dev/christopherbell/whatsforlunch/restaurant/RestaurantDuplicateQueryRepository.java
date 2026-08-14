package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Indexed aggregation for bounded duplicate-name discovery. */
@MongoPersistence
@Repository
public class RestaurantDuplicateQueryRepository implements RestaurantDuplicateQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final KindScopedMongoOperations<Restaurant> restaurants;

  public RestaurantDuplicateQueryRepository(DomainMongoOperationsFactory factory) {
    this.restaurants = factory.forType(Restaurant.class);
  }

  /** Aggregates only one page of duplicate keys and then fetches only those members. */
  @Override
  public Page find(String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate page size must be 1 through 100");
    }
    var keyMatch = new Document("$type", "string").append("$ne", "");
    if (cursor != null && !cursor.isBlank()) {
      keyMatch.append("$gt", cursor);
    }
    var pipeline = List.of(
        new Document("$match", new Document("dedupeKey", keyMatch)),
        new Document("$sort", new Document("dedupeKey", 1).append("_id", 1)),
        new Document("$group", new Document("_id", "$dedupeKey")
            .append("count", new Document("$sum", 1))),
        new Document("$match", new Document("count", new Document("$gt", 1))),
        new Document("$sort", new Document("_id", 1)),
        new Document("$limit", size + 1));
    var grouped = restaurants.aggregate(KindScopedAggregation.local(
        Aggregation.newAggregation(pipeline.stream()
            .<org.springframework.data.mongodb.core.aggregation.AggregationOperation>
                map(stage -> context -> stage)
            .toList())), Document.class);
    var keysWithExtra = grouped.stream()
        .map(document -> document.getString("_id"))
        .filter(key -> key != null && !key.isBlank())
        .toList();
    var hasMore = keysWithExtra.size() > size;
    var keys = List.copyOf(keysWithExtra.subList(0, Math.min(size, keysWithExtra.size())));
    var members = keys.isEmpty()
        ? List.<Restaurant>of()
        : restaurants.find(
            new Query(Criteria.where("dedupeKey").in(keys))
                .with(Sort.by(Sort.Order.asc("dedupeKey"), Sort.Order.asc("id"))),
            Pageable.unpaged());
    return new Page(keys, hasMore ? keys.getLast() : null, List.copyOf(members));
  }

  /** Duplicate keys, continuation, and only their member documents. */
  public record Page(List<String> keys, String nextCursor, List<Restaurant> members) {}
}
