package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Stores per-account What's For Lunch filters.
 */
@Repository
public interface WhatsForLunchPreferenceRepository
    extends MongoRepository<WhatsForLunchPreference, String> {
}
