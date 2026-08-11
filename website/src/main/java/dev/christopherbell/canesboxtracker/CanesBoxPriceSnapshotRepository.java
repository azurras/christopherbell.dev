package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * Mongo repository for weekly Raising Canes Box Index snapshots.
 */
public interface CanesBoxPriceSnapshotRepository {
  CanesBoxPriceSnapshot save(CanesBoxPriceSnapshot snapshot);
  Optional<CanesBoxPriceSnapshot> findById(String id);
  List<CanesBoxPriceSnapshot> findTop60ByOrderByWeekStartDateDesc();
}
