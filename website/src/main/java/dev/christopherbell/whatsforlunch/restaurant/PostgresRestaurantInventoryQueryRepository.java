package dev.christopherbell.whatsforlunch.restaurant;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL bounded stable restaurant inventory query. */
@PostgresPersistence
public class PostgresRestaurantInventoryQueryRepository implements RestaurantInventoryQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final DSLContext database;
  public PostgresRestaurantInventoryQueryRepository(DSLContext database) { this.database = database; }
  @Override public RestaurantInventoryQueryRepository.Page find(
      String name, String city, String state, String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "Inventory page size must be 1 through 100");
    var normalizedName = normalized(name); var normalizedCity = normalized(city); var normalizedState = normalized(state);
    var after = decodeCursor(cursor);
    Condition filters = filters(normalizedName, normalizedCity, normalizedState);
    Condition page = filters;
    if (after != null) page = page.and(RESTAURANT.DEDUPE_KEY.gt(after.name())
        .or(RESTAURANT.DEDUPE_KEY.eq(after.name()).and(RESTAURANT.RESTAURANT_ID.gt(after.id()))));
    var rows = database.selectFrom(RESTAURANT).where(page)
        .orderBy(RESTAURANT.DEDUPE_KEY.asc(), RESTAURANT.RESTAURANT_ID.asc()).limit(size + 1).fetch();
    boolean hasMore = rows.size() > size;
    var items = rows.stream().limit(size).map(PostgresRestaurantRepository::map).toList();
    String next = hasMore && !items.isEmpty() ? encodeCursor(items.getLast()) : null;
    long total = database.selectCount().from(RESTAURANT).where(filters).fetchSingle(0, long.class);
    return new RestaurantInventoryQueryRepository.Page(items, next, total);
  }
  private static Condition filters(String name, String city, String state) {
    Condition condition = DSL.noCondition();
    if (name != null) condition = condition.and(RESTAURANT.DEDUPE_KEY.startsWith(name));
    if (city != null) condition = condition.and(RESTAURANT.SEARCH_CITY.eq(city));
    if (state != null) condition = condition.and(RESTAURANT.SEARCH_STATE.eq(state));
    return condition;
  }
  private static String normalized(String value) {
    if (value == null || value.isBlank()) return null;
    var normalized = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    if (normalized.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory filter is too long");
    return normalized;
  }
  private static String encodeCursor(Restaurant value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        (value.getDedupeKey() + "\u0000" + value.getId()).getBytes(StandardCharsets.UTF_8));
  }
  private static Cursor decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    try {
      var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int separator = decoded.indexOf('\u0000');
      if (separator < 1 || separator == decoded.length() - 1) throw new IllegalArgumentException("invalid cursor");
      return new Cursor(decoded.substring(0, separator), decoded.substring(separator + 1));
    } catch (IllegalArgumentException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory cursor is invalid");
    }
  }
  private record Cursor(String name, String id) {}
}
