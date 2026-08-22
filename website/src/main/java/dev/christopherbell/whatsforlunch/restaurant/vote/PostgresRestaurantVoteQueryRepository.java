package dev.christopherbell.whatsforlunch.restaurant.vote;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL bounded vote aggregation query. */
@PostgresPersistence
public class PostgresRestaurantVoteQueryRepository implements RestaurantVoteQueryPort {
  private static final int MAX_RESULTS = 50;
  private final JdbcClient database;
  private final String voteTable;
  public PostgresRestaurantVoteQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    voteTable = schemas.qualifiedTable("lunch", "restaurant_vote");
  }

  @Override public List<RestaurantVoteSummary> topLiked(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_RESULTS));
    return database.sql("""
            select restaurant_id,
              sum(case when vote_value = 1 then 1 else 0 end)::integer as up_votes,
              sum(case when vote_value = -1 then 1 else 0 end)::integer as down_votes,
              count(*)::integer as vote_count
            from %s
            where vote_value in (1, -1)
            group by restaurant_id
            order by
              (sum(case when vote_value = 1 then 1 else 0 end)::numeric / count(*)) desc,
              count(*) desc,
              restaurant_id asc
            limit :limit
            """.formatted(voteTable))
        .param("limit", limit)
        .query(PostgresRestaurantVoteQueryRepository::map)
        .list();
  }

  @Override public List<RestaurantVoteSummary> summariesForRestaurants(Collection<String> ids) {
    Objects.requireNonNull(ids, "restaurantIds");
    if (ids.isEmpty()) return List.of();
    return database.sql("""
            select restaurant_id,
              sum(case when vote_value = 1 then 1 else 0 end)::integer as up_votes,
              sum(case when vote_value = -1 then 1 else 0 end)::integer as down_votes,
              count(*)::integer as vote_count
            from %s
            where restaurant_id in (:ids) and vote_value in (1, -1)
            group by restaurant_id
            order by restaurant_id
            """.formatted(voteTable))
        .param("ids", ids)
        .query(PostgresRestaurantVoteQueryRepository::map)
        .list();
  }

  private static RestaurantVoteSummary map(java.sql.ResultSet row, int rowNumber)
      throws java.sql.SQLException {
    return new RestaurantVoteSummary(
        row.getString("restaurant_id"), row.getInt("up_votes"),
        row.getInt("down_votes"), row.getInt("vote_count"));
  }
}
