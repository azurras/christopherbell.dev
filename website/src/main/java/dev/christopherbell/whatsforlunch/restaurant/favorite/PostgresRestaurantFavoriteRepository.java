package dev.christopherbell.whatsforlunch.restaurant.favorite;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_FAVORITE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL one-favorite-per-account-and-restaurant adapter. */
@PostgresPersistence
public class PostgresRestaurantFavoriteRepository implements RestaurantFavoriteRepository {
  private final DSLContext database;
  public PostgresRestaurantFavoriteRepository(DSLContext database) { this.database = database; }
  @Override public RestaurantFavorite save(RestaurantFavorite value) {
    try {
      database.insertInto(RESTAURANT_FAVORITE)
          .set(RESTAURANT_FAVORITE.RESTAURANT_FAVORITE_ID, value.getId())
          .set(RESTAURANT_FAVORITE.ACCOUNT_ID, value.getAccountId())
          .set(RESTAURANT_FAVORITE.RESTAURANT_ID, value.getRestaurantId())
          .set(RESTAURANT_FAVORITE.CREATED_ON, value.getCreatedOn().atOffset(ZoneOffset.UTC))
          .onConflict(RESTAURANT_FAVORITE.RESTAURANT_FAVORITE_ID).doUpdate()
          .set(RESTAURANT_FAVORITE.ACCOUNT_ID, value.getAccountId())
          .set(RESTAURANT_FAVORITE.RESTAURANT_ID, value.getRestaurantId())
          .set(RESTAURANT_FAVORITE.CREATED_ON, value.getCreatedOn().atOffset(ZoneOffset.UTC)).execute();
      return findByRestaurantIdAndAccountId(value.getRestaurantId(), value.getAccountId()).orElseThrow();
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          failure.sqlState(),
          "PostgreSQL rejected a duplicate restaurant favorite.",
          "PostgreSQL rejected restaurant favorite data.");
    }
  }
  @Override public void deleteByRestaurantIdAndAccountId(String restaurantId, String accountId) {
    database.deleteFrom(RESTAURANT_FAVORITE).where(RESTAURANT_FAVORITE.RESTAURANT_ID.eq(restaurantId)
        .and(RESTAURANT_FAVORITE.ACCOUNT_ID.eq(accountId))).execute();
  }
  @Override public List<RestaurantFavorite> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return database.selectFrom(RESTAURANT_FAVORITE).where(RESTAURANT_FAVORITE.ACCOUNT_ID.eq(accountId))
        .orderBy(RESTAURANT_FAVORITE.CREATED_ON.desc(), RESTAURANT_FAVORITE.RESTAURANT_FAVORITE_ID.desc())
        .fetch(PostgresRestaurantFavoriteRepository::map);
  }
  @Override public List<RestaurantFavorite> findByRestaurantIdInAndAccountId(Collection<String> ids, String accountId) {
    return ids.isEmpty() ? List.of() : database.selectFrom(RESTAURANT_FAVORITE)
        .where(RESTAURANT_FAVORITE.RESTAURANT_ID.in(ids).and(RESTAURANT_FAVORITE.ACCOUNT_ID.eq(accountId)))
        .orderBy(RESTAURANT_FAVORITE.RESTAURANT_ID).fetch(PostgresRestaurantFavoriteRepository::map);
  }
  @Override public Optional<RestaurantFavorite> findByRestaurantIdAndAccountId(String restaurantId, String accountId) {
    return database.selectFrom(RESTAURANT_FAVORITE).where(RESTAURANT_FAVORITE.RESTAURANT_ID.eq(restaurantId)
        .and(RESTAURANT_FAVORITE.ACCOUNT_ID.eq(accountId))).fetchOptional(PostgresRestaurantFavoriteRepository::map);
  }
  private static RestaurantFavorite map(
      dev.christopherbell.persistence.jooq.lunch.tables.records.RestaurantFavoriteRecord row) {
    return RestaurantFavorite.builder().id(row.getRestaurantFavoriteId()).accountId(row.getAccountId())
        .restaurantId(row.getRestaurantId()).createdOn(row.getCreatedOn().toInstant()).build();
  }
}
