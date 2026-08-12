package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import java.util.Optional;

/**
 * Stores durable state for automated restaurant imports.
 */
public interface RestaurantImportStateRepository {
  RestaurantImportState save(RestaurantImportState state);
  Optional<RestaurantImportState> findById(String id);
}
