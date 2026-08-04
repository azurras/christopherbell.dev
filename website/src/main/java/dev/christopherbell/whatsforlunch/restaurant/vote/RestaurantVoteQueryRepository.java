package dev.christopherbell.whatsforlunch.restaurant.vote;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.AggregationExpression;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Owns bounded aggregate queries over binary restaurant votes. */
@Repository
@RequiredArgsConstructor
public class RestaurantVoteQueryRepository {
  private static final int MAX_RESULTS = 50;
  private static final String COLLECTION = "whatsforlunch_ratings";

  private final MongoTemplate mongo;

  /** Returns restaurant vote totals in stable public leaderboard order. */
  public List<RestaurantVoteSummary> topLiked(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_RESULTS));
    return mongo.aggregate(leaderboardAggregation(limit), COLLECTION, RestaurantVoteSummary.class)
        .getMappedResults();
  }

  /** Returns aggregate vote totals for the requested candidate restaurants. */
  public List<RestaurantVoteSummary> summariesForRestaurants(Collection<String> restaurantIds) {
    Objects.requireNonNull(restaurantIds, "restaurantIds");
    if (restaurantIds.isEmpty()) {
      return List.of();
    }
    AggregationExpression up = voteCountExpression(RestaurantVoteValue.UP);
    AggregationExpression down = voteCountExpression(RestaurantVoteValue.DOWN);
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("restaurantId").in(restaurantIds)),
        Aggregation.group("restaurantId")
            .sum(up).as("upVotes")
            .sum(down).as("downVotes")
            .count().as("voteCount"),
        Aggregation.project("upVotes", "downVotes", "voteCount").and("_id").as("restaurantId"));
    return mongo.aggregate(aggregation, COLLECTION, RestaurantVoteSummary.class).getMappedResults();
  }

  private static Aggregation leaderboardAggregation(int limit) {
    AggregationExpression up = voteCountExpression(RestaurantVoteValue.UP);
    AggregationExpression down = voteCountExpression(RestaurantVoteValue.DOWN);
    return Aggregation.newAggregation(
        Aggregation.group("restaurantId")
            .sum(up).as("upVotes")
            .sum(down).as("downVotes")
            .count().as("voteCount"),
        Aggregation.project("upVotes", "downVotes", "voteCount")
            .and("_id").as("restaurantId")
            .andExpression("upVotes * 1.0 / voteCount").as("approvalRatio"),
        Aggregation.sort(Sort.by(
            Sort.Order.desc("approvalRatio"),
            Sort.Order.desc("voteCount"),
            Sort.Order.asc("restaurantId"))),
        Aggregation.limit(limit));
  }

  private static AggregationExpression voteCountExpression(RestaurantVoteValue vote) {
    return ConditionalOperators.when(Criteria.where("vote").is(vote.name())).then(1).otherwise(0);
  }
}
