package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL bounded stable restaurant inventory query. */
@PostgresPersistence
public class PostgresRestaurantInventoryQueryRepository implements RestaurantInventoryQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcClient database;
  private final String table;

  public PostgresRestaurantInventoryQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("lunch", "restaurant");
  }

  @Override
  public RestaurantInventoryQueryRepository.Page find(
      String name, String city, String state, String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Inventory page size must be 1 through 100");
    }
    var normalizedName = normalized(name);
    var normalizedCity = normalized(city);
    var normalizedState = normalized(state);
    var after = decodeCursor(cursor);
    var parameters = new LinkedHashMap<String, Object>();
    var filters = filters(normalizedName, normalizedCity, normalizedState, parameters);
    var page = new StringBuilder(filters);
    if (after != null) {
      page.append(" and (dedupe_key > :afterName or (dedupe_key = :afterName and restaurant_id > :afterId))");
      parameters.put("afterName", after.name());
      parameters.put("afterId", after.id());
    }
    parameters.put("limit", size + 1);
    var rows = database.sql("select * from %s where %s order by dedupe_key, restaurant_id limit :limit"
            .formatted(table, page))
        .params(parameters).query(PostgresRestaurantRepository::map).list();
    var hasMore = rows.size() > size;
    var items = rows.stream().limit(size).toList();
    String next = hasMore && !items.isEmpty() ? encodeCursor(items.getLast()) : null;
    parameters.remove("afterName");
    parameters.remove("afterId");
    parameters.remove("limit");
    long total = database.sql("select count(*) from %s where %s".formatted(table, filters))
        .params(parameters).query(Long.class).single();
    return new RestaurantInventoryQueryRepository.Page(items, next, total);
  }

  private static String filters(
      String name, String city, String state, LinkedHashMap<String, Object> parameters) {
    var clauses = new java.util.ArrayList<String>();
    if (name != null) {
      clauses.add("left(dedupe_key, length(:name)) = :name");
      parameters.put("name", name);
    }
    if (city != null) {
      clauses.add("search_city = :city");
      parameters.put("city", city);
    }
    if (state != null) {
      clauses.add("search_state = :state");
      parameters.put("state", state);
    }
    return clauses.isEmpty() ? "true" : String.join(" and ", clauses);
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
