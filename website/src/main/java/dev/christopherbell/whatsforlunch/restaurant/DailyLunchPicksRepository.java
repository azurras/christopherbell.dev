package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import java.util.Optional;

/**
 * Repository for daily What's for Lunch restaurant selections.
 */
public interface DailyLunchPicksRepository {
  DailyLunchPicks save(DailyLunchPicks picks);
  Optional<DailyLunchPicks> findById(String id);
}
