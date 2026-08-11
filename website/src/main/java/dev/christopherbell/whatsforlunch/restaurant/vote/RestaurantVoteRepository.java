package dev.christopherbell.whatsforlunch.restaurant.vote;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Stores one binary WFL restaurant vote per account and restaurant. */
public interface RestaurantVoteRepository {
  RestaurantVote save(RestaurantVote vote);
  Optional<RestaurantVote> findById(String id);
  void deleteById(String id);
  List<RestaurantVote> findByRestaurantIdIn(Collection<String> restaurantIds);

  Optional<RestaurantVote> findByRestaurantIdAndAccountId(String restaurantId, String accountId);
}
