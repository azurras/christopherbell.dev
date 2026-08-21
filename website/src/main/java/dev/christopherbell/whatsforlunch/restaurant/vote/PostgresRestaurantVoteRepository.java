package dev.christopherbell.whatsforlunch.restaurant.vote;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_VOTE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL one-vote-per-account-and-restaurant adapter. */
@PostgresPersistence
public class PostgresRestaurantVoteRepository implements RestaurantVoteRepository {
  private final DSLContext database;
  public PostgresRestaurantVoteRepository(DSLContext database) { this.database = database; }
  @Override public RestaurantVote save(RestaurantVote value) {
    try {
      database.insertInto(RESTAURANT_VOTE).set(RESTAURANT_VOTE.RESTAURANT_VOTE_ID, value.getId())
          .set(RESTAURANT_VOTE.ACCOUNT_ID, value.getAccountId()).set(RESTAURANT_VOTE.RESTAURANT_ID, value.getRestaurantId())
          .set(RESTAURANT_VOTE.VOTE_VALUE, encode(value.getVote()))
          .set(RESTAURANT_VOTE.CREATED_ON, value.getCreatedOn().atOffset(ZoneOffset.UTC))
          .set(RESTAURANT_VOTE.LAST_UPDATED_ON, value.getLastUpdatedOn().atOffset(ZoneOffset.UTC))
          .onConflict(RESTAURANT_VOTE.RESTAURANT_VOTE_ID).doUpdate()
          .set(RESTAURANT_VOTE.ACCOUNT_ID, value.getAccountId()).set(RESTAURANT_VOTE.RESTAURANT_ID, value.getRestaurantId())
          .set(RESTAURANT_VOTE.VOTE_VALUE, encode(value.getVote()))
          .set(RESTAURANT_VOTE.CREATED_ON, value.getCreatedOn().atOffset(ZoneOffset.UTC))
          .set(RESTAURANT_VOTE.LAST_UPDATED_ON, value.getLastUpdatedOn().atOffset(ZoneOffset.UTC)).execute();
      return findById(value.getId()).orElseThrow();
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          failure.sqlState(),
          "PostgreSQL rejected a duplicate restaurant vote.",
          "PostgreSQL rejected restaurant vote data.");
    }
  }
  @Override public Optional<RestaurantVote> findById(String id) {
    return database.selectFrom(RESTAURANT_VOTE).where(RESTAURANT_VOTE.RESTAURANT_VOTE_ID.eq(id))
        .fetchOptional(PostgresRestaurantVoteRepository::map);
  }
  @Override public void deleteById(String id) { database.deleteFrom(RESTAURANT_VOTE).where(RESTAURANT_VOTE.RESTAURANT_VOTE_ID.eq(id)).execute(); }
  @Override public List<RestaurantVote> findByRestaurantIdIn(Collection<String> ids) {
    return ids.isEmpty() ? List.of() : database.selectFrom(RESTAURANT_VOTE)
        .where(RESTAURANT_VOTE.RESTAURANT_ID.in(ids))
        .orderBy(RESTAURANT_VOTE.RESTAURANT_ID, RESTAURANT_VOTE.ACCOUNT_ID)
        .fetch(PostgresRestaurantVoteRepository::map);
  }
  @Override public Optional<RestaurantVote> findByRestaurantIdAndAccountId(String restaurantId, String accountId) {
    return database.selectFrom(RESTAURANT_VOTE).where(RESTAURANT_VOTE.RESTAURANT_ID.eq(restaurantId)
        .and(RESTAURANT_VOTE.ACCOUNT_ID.eq(accountId))).fetchOptional(PostgresRestaurantVoteRepository::map);
  }
  private static Integer encode(RestaurantVoteValue value) {
    if (value == null) return null;
    return value == RestaurantVoteValue.UP ? 1 : -1;
  }
  static RestaurantVote map(dev.christopherbell.persistence.jooq.lunch.tables.records.RestaurantVoteRecord row) {
    return RestaurantVote.builder().id(row.getRestaurantVoteId()).accountId(row.getAccountId())
        .restaurantId(row.getRestaurantId()).vote(row.getVoteValue() == null
            ? null
            : row.getVoteValue() > 0 ? RestaurantVoteValue.UP : RestaurantVoteValue.DOWN)
        .createdOn(row.getCreatedOn().toInstant()).lastUpdatedOn(row.getLastUpdatedOn().toInstant()).build();
  }
}
