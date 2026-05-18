package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for daily What's for Lunch restaurant selections.
 */
public interface DailyLunchPicksRepository extends MongoRepository<DailyLunchPicks, String> {}
