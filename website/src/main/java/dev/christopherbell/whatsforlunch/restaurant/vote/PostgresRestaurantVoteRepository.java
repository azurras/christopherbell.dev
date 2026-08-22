package dev.christopherbell.whatsforlunch.restaurant.vote;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL one-vote-per-account-and-restaurant adapter. */
@PostgresPersistence
public class PostgresRestaurantVoteRepository implements RestaurantVoteRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresRestaurantVoteRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("lunch", "restaurant_vote");
  }

  @Override
  public RestaurantVote save(RestaurantVote value) {
    try {
      return database.sql("""
              insert into %s
                (restaurant_vote_id, account_id, restaurant_id, vote_value,
                 created_on, last_updated_on)
              values (:id, :accountId, :restaurantId, :voteValue,
                      :createdOn, :lastUpdatedOn)
              on conflict (restaurant_vote_id) do update set
                account_id = excluded.account_id,
                restaurant_id = excluded.restaurant_id,
                vote_value = excluded.vote_value,
                created_on = excluded.created_on,
                last_updated_on = excluded.last_updated_on
              returning *
              """.formatted(table))
          .param("id", value.getId())
          .param("accountId", value.getAccountId())
          .param("restaurantId", value.getRestaurantId())
          .param("voteValue", encode(value.getVote()), Types.INTEGER)
          .param("createdOn", value.getCreatedOn().atOffset(ZoneOffset.UTC))
          .param("lastUpdatedOn", value.getLastUpdatedOn().atOffset(ZoneOffset.UTC))
          .query(PostgresRestaurantVoteRepository::map)
          .single();
    } catch (DataIntegrityViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          sqlState(failure),
          "PostgreSQL rejected a duplicate restaurant vote.",
          "PostgreSQL rejected restaurant vote data.");
    }
  }

  @Override
  public Optional<RestaurantVote> findById(String id) {
    return database.sql("select * from %s where restaurant_vote_id = :id".formatted(table))
        .param("id", id)
        .query(PostgresRestaurantVoteRepository::map)
        .optional();
  }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where restaurant_vote_id = :id".formatted(table))
        .param("id", id)
        .update();
  }

  @Override
  public List<RestaurantVote> findByRestaurantIdIn(Collection<String> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return database.sql("""
            select * from %s where restaurant_id in (:ids)
            order by restaurant_id, account_id
            """.formatted(table))
        .param("ids", ids)
        .query(PostgresRestaurantVoteRepository::map)
        .list();
  }

  @Override
  public Optional<RestaurantVote> findByRestaurantIdAndAccountId(
      String restaurantId, String accountId) {
    return database.sql("""
            select * from %s where restaurant_id = :restaurantId and account_id = :accountId
            """.formatted(table))
        .param("restaurantId", restaurantId)
        .param("accountId", accountId)
        .query(PostgresRestaurantVoteRepository::map)
        .optional();
  }

  private static Integer encode(RestaurantVoteValue value) {
    if (value == null) {
      return null;
    }
    return value == RestaurantVoteValue.UP ? 1 : -1;
  }

  private static RestaurantVote map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    var vote = row.getObject("vote_value", Integer.class);
    return RestaurantVote.builder()
        .id(row.getString("restaurant_vote_id"))
        .accountId(row.getString("account_id"))
        .restaurantId(row.getString("restaurant_id"))
        .vote(vote == null ? null : vote > 0 ? RestaurantVoteValue.UP : RestaurantVoteValue.DOWN)
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant())
        .lastUpdatedOn(row.getObject("last_updated_on", OffsetDateTime.class).toInstant())
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
