package dev.christopherbell.whatsforlunch.restaurant;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL bounded duplicate restaurant-name query. */
@PostgresPersistence
public class PostgresRestaurantDuplicateQueryRepository implements RestaurantDuplicateQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final DSLContext database;
  public PostgresRestaurantDuplicateQueryRepository(DSLContext database) { this.database = database; }
  @Override public RestaurantDuplicateQueryRepository.Page find(String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "Duplicate page size must be 1 through 100");
    Condition condition = RESTAURANT.DEDUPE_KEY.isNotNull().and(RESTAURANT.DEDUPE_KEY.ne(""));
    if (cursor != null && !cursor.isBlank()) condition = condition.and(RESTAURANT.DEDUPE_KEY.gt(cursor));
    var keysWithExtra = database.select(RESTAURANT.DEDUPE_KEY).from(RESTAURANT).where(condition)
        .groupBy(RESTAURANT.DEDUPE_KEY).having(DSL.count().gt(1))
        .orderBy(RESTAURANT.DEDUPE_KEY).limit(size + 1).fetch(RESTAURANT.DEDUPE_KEY);
    boolean hasMore = keysWithExtra.size() > size;
    var keys = List.copyOf(keysWithExtra.subList(0, Math.min(size, keysWithExtra.size())));
    List<Restaurant> members = keys.isEmpty() ? List.of() : database.selectFrom(RESTAURANT)
        .where(RESTAURANT.DEDUPE_KEY.in(keys)).orderBy(RESTAURANT.DEDUPE_KEY, RESTAURANT.RESTAURANT_ID)
        .fetch(PostgresRestaurantRepository::map);
    return new RestaurantDuplicateQueryRepository.Page(keys, hasMore ? keys.getLast() : null, members);
  }
}
