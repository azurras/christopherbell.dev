package dev.christopherbell.survive.persistence;

import dev.christopherbell.survive.model.SurviveSavedWorld;
import java.util.Optional;

/** Durable command boundary: save must atomically reject a changed storage version. */
public interface SurviveWorldRepository {
  Optional<SurviveSavedWorld> load();
  SurviveSavedWorld save(SurviveSavedWorld world);
}
