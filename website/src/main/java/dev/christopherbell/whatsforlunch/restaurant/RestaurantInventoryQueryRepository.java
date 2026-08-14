package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Bounded stable admin restaurant inventory query. */
@MongoPersistence
@Repository
public class RestaurantInventoryQueryRepository implements RestaurantInventoryQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final KindScopedMongoOperations<Restaurant> restaurants;

  public RestaurantInventoryQueryRepository(DomainMongoOperationsFactory factory) {
    this.restaurants = factory.forType(Restaurant.class);
  }

  /** Applies normalized indexed filters and a stable name/id cursor. */
  @Override
  public Page find(
      String name,
      String city,
      String state,
      String cursor,
      int size
  ) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory page size must be 1 through 100");
    }
    var normalizedName = normalized(name);
    var normalizedCity = normalized(city);
    var normalizedState = normalized(state);
    var after = decodeCursor(cursor);

    var query = query(normalizedName, normalizedCity, normalizedState);
    if (after != null) {
      query.addCriteria(new Criteria().orOperator(
          Criteria.where("dedupeKey").gt(after.name()),
          new Criteria().andOperator(
              Criteria.where("dedupeKey").is(after.name()),
              Criteria.where("id").gt(after.id()))));
    }
    query.with(Sort.by(
        Sort.Order.asc("dedupeKey"),
        Sort.Order.asc("id"))).limit(size + 1);
    var found = restaurants.find(query, Pageable.unpaged());
    var hasMore = found.size() > size;
    var items = List.copyOf(found.subList(0, Math.min(size, found.size())));
    var nextCursor = hasMore && !items.isEmpty() ? encodeCursor(items.getLast()) : null;
    var total = restaurants.count(query(normalizedName, normalizedCity, normalizedState));
    return new Page(items, nextCursor, total);
  }

  private Query query(String name, String city, String state) {
    var criteria = new ArrayList<Criteria>();
    if (name != null) {
      criteria.add(Criteria.where("dedupeKey")
          .regex("^" + Pattern.quote(name)));
    }
    if (city != null) {
      criteria.add(Criteria.where("searchCity").is(city));
    }
    if (state != null) {
      criteria.add(Criteria.where("searchState").is(state));
    }
    return criteria.isEmpty()
        ? new Query()
        : new Query(new Criteria().andOperator(criteria.toArray(Criteria[]::new)));
  }

  private String normalized(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    var normalized = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    if (normalized.length() > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory filter is too long");
    }
    return normalized;
  }

  private String encodeCursor(Restaurant restaurant) {
    var value = restaurant.getDedupeKey() + "\u0000" + restaurant.getId();
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private Cursor decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      var decoded = new String(
          Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      var separator = decoded.indexOf('\u0000');
      if (separator < 1 || separator == decoded.length() - 1) {
        throw new IllegalArgumentException("invalid cursor");
      }
      return new Cursor(decoded.substring(0, separator), decoded.substring(separator + 1));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory cursor is invalid");
    }
  }

  private record Cursor(String name, String id) {}

  /** One stable inventory slice and its filtered total. */
  public record Page(List<Restaurant> items, String nextCursor, long total) {}
}
