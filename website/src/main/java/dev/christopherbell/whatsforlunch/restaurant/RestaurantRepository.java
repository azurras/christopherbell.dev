package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Repository interface for managing Restaurant entities in MongoDB.
 */
public interface RestaurantRepository extends MongoRepository<Restaurant, String> {
  Optional<Restaurant> findByNormalizedName(String normalizedName);

  /**
   * Finds restaurants whose saved coordinates fall inside a coarse candidate bounding box.
   *
   * <p>The service still applies an exact radius check after this query.</p>
   */
  @Query("""
      {
        'address.latitude': { $gte: ?0, $lte: ?1 },
        'address.longitude': { $gte: ?2, $lte: ?3 }
      }
      """)
  List<Restaurant> findByCoordinateBounds(
      double minLatitude,
      double maxLatitude,
      double minLongitude,
      double maxLongitude);
}
