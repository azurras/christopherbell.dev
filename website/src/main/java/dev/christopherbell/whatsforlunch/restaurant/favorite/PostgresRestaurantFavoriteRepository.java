package dev.christopherbell.whatsforlunch.restaurant.favorite;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL one-favorite-per-account-and-restaurant adapter. */
@PostgresPersistence
public class PostgresRestaurantFavoriteRepository implements RestaurantFavoriteRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresRestaurantFavoriteRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("lunch", "restaurant_favorite");
  }

  @Override
  public RestaurantFavorite save(RestaurantFavorite value) {
    try {
      return database.sql("""
              insert into %s
                (restaurant_favorite_id, account_id, restaurant_id, created_on)
              values (:id, :accountId, :restaurantId, :createdOn)
              on conflict (restaurant_favorite_id) do update set
                account_id = excluded.account_id,
                restaurant_id = excluded.restaurant_id,
                created_on = excluded.created_on
              returning *
              """.formatted(table))
          .param("id", value.getId())
          .param("accountId", value.getAccountId())
          .param("restaurantId", value.getRestaurantId())
          .param("createdOn", value.getCreatedOn().atOffset(ZoneOffset.UTC))
          .query(PostgresRestaurantFavoriteRepository::map)
          .single();
    } catch (DataIntegrityViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          sqlState(failure),
          "PostgreSQL rejected a duplicate restaurant favorite.",
          "PostgreSQL rejected restaurant favorite data.");
    }
  }

  @Override
  public void deleteByRestaurantIdAndAccountId(String restaurantId, String accountId) {
    database.sql("""
            delete from %s where restaurant_id = :restaurantId and account_id = :accountId
            """.formatted(table))
        .param("restaurantId", restaurantId)
        .param("accountId", accountId)
        .update();
  }

  @Override
  public List<RestaurantFavorite> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return database.sql("""
            select * from %s where account_id = :accountId
            order by created_on desc, restaurant_favorite_id desc
            """.formatted(table))
        .param("accountId", accountId)
        .query(PostgresRestaurantFavoriteRepository::map)
        .list();
  }

  @Override
  public List<RestaurantFavorite> findByRestaurantIdInAndAccountId(
      Collection<String> ids, String accountId) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return database.sql("""
            select * from %s
            where restaurant_id in (:ids) and account_id = :accountId
            order by restaurant_id
            """.formatted(table))
        .param("ids", ids)
        .param("accountId", accountId)
        .query(PostgresRestaurantFavoriteRepository::map)
        .list();
  }

  @Override
  public Optional<RestaurantFavorite> findByRestaurantIdAndAccountId(
      String restaurantId, String accountId) {
    return database.sql("""
            select * from %s where restaurant_id = :restaurantId and account_id = :accountId
            """.formatted(table))
        .param("restaurantId", restaurantId)
        .param("accountId", accountId)
        .query(PostgresRestaurantFavoriteRepository::map)
        .optional();
  }

  private static RestaurantFavorite map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return RestaurantFavorite.builder()
        .id(row.getString("restaurant_favorite_id"))
        .accountId(row.getString("account_id"))
        .restaurantId(row.getString("restaurant_id"))
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant())
        .build();
  }

  private static String sqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure) {
        return sqlFailure.getSQLState();
      }
    }
    return null;
  }
}
