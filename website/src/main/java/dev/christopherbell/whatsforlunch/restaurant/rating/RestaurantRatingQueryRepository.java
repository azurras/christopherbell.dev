package dev.christopherbell.whatsforlunch.restaurant.rating;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Owns bounded aggregate queries over restaurant ratings. */
@Repository
@RequiredArgsConstructor
public class RestaurantRatingQueryRepository {
  private static final int MAX_RESULTS = 50;
  private static final String COLLECTION = "whatsforlunch_ratings";

  private final MongoTemplate mongo;

  /** Returns rated restaurant totals in stable public leaderboard order. */
  public List<RestaurantRatingSummary> topRated(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_RESULTS));
    var aggregation = Aggregation.newAggregation(
        Aggregation.group("restaurantId")
            .count().as("ratingCount")
            .sum("rating").as("ratingSum"),
        Aggregation.project("ratingCount", "ratingSum")
            .and("_id").as("restaurantId")
            .andExpression("ratingSum / ratingCount").as("averageRating"),
        Aggregation.sort(Sort.by(
            Sort.Order.desc("averageRating"),
            Sort.Order.desc("ratingCount"),
            Sort.Order.asc("restaurantId"))),
        Aggregation.limit(limit));
    return mongo.aggregate(aggregation, COLLECTION, RestaurantRatingSummary.class)
        .getMappedResults();
  }

  /** Returns aggregate rating totals for the requested candidate restaurants. */
  public List<RestaurantRatingSummary> summariesForRestaurants(
      Collection<String> restaurantIds
  ) {
    Objects.requireNonNull(restaurantIds, "restaurantIds");
    if (restaurantIds.isEmpty()) {
      return List.of();
    }
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("restaurantId").in(restaurantIds)),
        Aggregation.group("restaurantId")
            .count().as("ratingCount")
            .sum("rating").as("ratingSum"),
        Aggregation.project("ratingCount", "ratingSum")
            .and("_id").as("restaurantId"));
    return mongo.aggregate(aggregation, COLLECTION, RestaurantRatingSummary.class)
        .getMappedResults();
  }
}
