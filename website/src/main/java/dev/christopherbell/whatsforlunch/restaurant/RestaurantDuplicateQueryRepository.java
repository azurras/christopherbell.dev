package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Indexed aggregation for bounded duplicate-name discovery. */
@Repository
@RequiredArgsConstructor
public class RestaurantDuplicateQueryRepository {
  private static final int MAX_PAGE_SIZE = 100;
  private final MongoTemplate mongo;

  /** Aggregates only one page of duplicate keys and then fetches only those members. */
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
    var grouped = mongo.getCollection("whatsforlunch")
        .aggregate(pipeline)
        .into(new ArrayList<>());
    var keysWithExtra = grouped.stream()
        .map(document -> document.getString("_id"))
        .filter(key -> key != null && !key.isBlank())
        .toList();
    var hasMore = keysWithExtra.size() > size;
    var keys = List.copyOf(keysWithExtra.subList(0, Math.min(size, keysWithExtra.size())));
    var members = keys.isEmpty()
        ? List.<Restaurant>of()
        : mongo.find(
            new Query(Criteria.where("dedupeKey").in(keys))
                .with(Sort.by(Sort.Order.asc("dedupeKey"), Sort.Order.asc("_id"))),
            Restaurant.class,
            "whatsforlunch");
    return new Page(keys, hasMore ? keys.getLast() : null, List.copyOf(members));
  }

  /** Duplicate keys, continuation, and only their member documents. */
  public record Page(List<String> keys, String nextCursor, List<Restaurant> members) {}
}
