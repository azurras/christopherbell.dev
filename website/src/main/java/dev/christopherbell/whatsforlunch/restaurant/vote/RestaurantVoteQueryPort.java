package dev.christopherbell.whatsforlunch.restaurant.vote;

import java.util.Collection;
import java.util.List;

/** Persistence-neutral restaurant vote aggregation query. */
public interface RestaurantVoteQueryPort {
  List<RestaurantVoteSummary> topLiked(int requestedLimit);

  List<RestaurantVoteSummary> summariesForRestaurants(Collection<String> restaurantIds);
}
