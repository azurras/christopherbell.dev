package dev.christopherbell.whatsforlunch.restaurant.vote;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Stores one binary WFL restaurant vote per account and restaurant. */
@Repository
public interface RestaurantVoteRepository extends MongoRepository<RestaurantVote, String> {
  List<RestaurantVote> findByRestaurantIdIn(Collection<String> restaurantIds);

  Optional<RestaurantVote> findByRestaurantIdAndAccountId(String restaurantId, String accountId);
}
