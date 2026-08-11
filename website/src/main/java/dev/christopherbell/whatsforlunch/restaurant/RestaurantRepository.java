package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository interface for managing Restaurant entities in MongoDB.
 */
public interface RestaurantRepository {
  Restaurant save(Restaurant restaurant);
  Optional<Restaurant> findById(String id);
  void delete(Restaurant restaurant);
  void deleteAll(Iterable<Restaurant> restaurants);
  List<Restaurant> findAll();
  long count();
  Page<Restaurant> findAll(Pageable pageable);
  List<Restaurant> findAllById(Iterable<String> ids);
  Optional<Restaurant> findByNormalizedName(String normalizedName);

  List<Restaurant> findByDedupeKeyIn(List<String> dedupeKeys);

  /**
   * Finds restaurants whose saved coordinates fall inside a coarse candidate bounding box.
   *
   * <p>The service still applies an exact radius check after this query.</p>
   */
  List<Restaurant> findByCoordinateBounds(
      double minLatitude,
      double maxLatitude,
      double minLongitude,
      double maxLongitude);
}
