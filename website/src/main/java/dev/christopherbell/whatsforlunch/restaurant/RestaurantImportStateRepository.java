package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Stores durable state for automated restaurant imports.
 */
@Repository
public interface RestaurantImportStateRepository extends MongoRepository<RestaurantImportState, String> {
}
