package dev.christopherbell.whatsforlunch.restaurant.rating;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRating;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Stores one whole-number WFL restaurant rating per account and restaurant. */
@Repository
public interface RestaurantRatingRepository extends MongoRepository<RestaurantRating, String> {
  List<RestaurantRating> findByRestaurantIdIn(Collection<String> restaurantIds);

  Optional<RestaurantRating> findByRestaurantIdAndAccountId(String restaurantId, String accountId);
}
