package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository interface for managing Restaurant entities in MongoDB.
 */
public interface RestaurantRepository extends MongoRepository<Restaurant, String> {
  Optional<Restaurant> findByNormalizedName(String normalizedName);
}
