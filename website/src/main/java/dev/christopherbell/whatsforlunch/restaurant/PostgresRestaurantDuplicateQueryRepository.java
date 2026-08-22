package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL bounded duplicate restaurant-name query. */
@PostgresPersistence
public class PostgresRestaurantDuplicateQueryRepository implements RestaurantDuplicateQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcClient database;
  private final String table;

  public PostgresRestaurantDuplicateQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("lunch", "restaurant");
  }

  @Override
  public RestaurantDuplicateQueryRepository.Page find(String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Duplicate page size must be 1 through 100");
    }
    var after = cursor == null || cursor.isBlank() ? "" : cursor;
    var keysWithExtra = database.sql("""
            select dedupe_key from %s
            where dedupe_key is not null and dedupe_key <> '' and dedupe_key > :after
            group by dedupe_key having count(*) > 1
            order by dedupe_key limit :limit
            """.formatted(table))
        .param("after", after).param("limit", size + 1).query(String.class).list();
    boolean hasMore = keysWithExtra.size() > size;
    var keys = List.copyOf(keysWithExtra.subList(0, Math.min(size, keysWithExtra.size())));
    List<Restaurant> members = keys.isEmpty() ? List.of() : database.sql("""
            select * from %s where dedupe_key in (:keys)
            order by dedupe_key, restaurant_id
            """.formatted(table))
        .param("keys", keys).query(PostgresRestaurantRepository::map).list();
    return new RestaurantDuplicateQueryRepository.Page(keys, hasMore ? keys.getLast() : null, members);
  }
}
