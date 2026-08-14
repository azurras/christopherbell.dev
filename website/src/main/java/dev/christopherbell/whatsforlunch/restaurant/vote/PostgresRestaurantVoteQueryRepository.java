package dev.christopherbell.whatsforlunch.restaurant.vote;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_VOTE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL bounded vote aggregation query. */
@PostgresPersistence
public class PostgresRestaurantVoteQueryRepository implements RestaurantVoteQueryPort {
  private static final int MAX_RESULTS = 50;
  private final DSLContext database;
  public PostgresRestaurantVoteQueryRepository(DSLContext database) { this.database = database; }

  @Override public List<RestaurantVoteSummary> topLiked(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_RESULTS));
    var upVotes = DSL.sum(DSL.when(RESTAURANT_VOTE.VOTE_VALUE.eq(1), 1).otherwise(0));
    var downVotes = DSL.sum(DSL.when(RESTAURANT_VOTE.VOTE_VALUE.eq(-1), 1).otherwise(0));
    var count = DSL.count();
    var approval = upVotes.cast(BigDecimal.class).div(count.cast(BigDecimal.class));
    return database.select(RESTAURANT_VOTE.RESTAURANT_ID, upVotes, downVotes, count)
        .from(RESTAURANT_VOTE).groupBy(RESTAURANT_VOTE.RESTAURANT_ID)
        .orderBy(approval.desc(), count.desc(), RESTAURANT_VOTE.RESTAURANT_ID.asc()).limit(limit)
        .fetch(row -> new RestaurantVoteSummary(row.value1(), row.value2().intValueExact(),
            row.value3().intValueExact(), row.value4()));
  }

  @Override public List<RestaurantVoteSummary> summariesForRestaurants(Collection<String> ids) {
    Objects.requireNonNull(ids, "restaurantIds");
    if (ids.isEmpty()) return List.of();
    var upVotes = DSL.sum(DSL.when(RESTAURANT_VOTE.VOTE_VALUE.eq(1), 1).otherwise(0));
    var downVotes = DSL.sum(DSL.when(RESTAURANT_VOTE.VOTE_VALUE.eq(-1), 1).otherwise(0));
    var count = DSL.count();
    return database.select(RESTAURANT_VOTE.RESTAURANT_ID, upVotes, downVotes, count)
        .from(RESTAURANT_VOTE).where(RESTAURANT_VOTE.RESTAURANT_ID.in(ids))
        .groupBy(RESTAURANT_VOTE.RESTAURANT_ID).orderBy(RESTAURANT_VOTE.RESTAURANT_ID)
        .fetch(row -> new RestaurantVoteSummary(row.value1(), row.value2().intValueExact(),
            row.value3().intValueExact(), row.value4()));
  }
}
