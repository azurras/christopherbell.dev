package dev.christopherbell.whatsforlunch.restaurant.preference;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import java.util.Optional;

/**
 * Stores per-account What's For Lunch filters.
 */
public interface WhatsForLunchPreferenceRepository {
  WhatsForLunchPreference save(WhatsForLunchPreference preference);
  Optional<WhatsForLunchPreference> findById(String accountId);
}
