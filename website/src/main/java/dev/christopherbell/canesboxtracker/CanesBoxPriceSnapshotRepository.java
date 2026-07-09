package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Mongo repository for weekly Raising Canes Box Index snapshots.
 */
public interface CanesBoxPriceSnapshotRepository extends MongoRepository<CanesBoxPriceSnapshot, String> {
  List<CanesBoxPriceSnapshot> findTop60ByOrderByWeekStartDateDesc();
}
