package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.persistence.SurviveWorldRepository;
import java.util.Optional;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;

/** Faithful durable boundary for service tests; immutable values prevent mutation leaks. */
class InMemorySurviveWorldRepository implements SurviveWorldRepository {
  private SurviveSavedWorld saved;
  boolean failNextSave;
  @Override public synchronized Optional<SurviveSavedWorld> load() { return Optional.ofNullable(saved); }
  @Override public synchronized SurviveSavedWorld save(SurviveSavedWorld candidate) {
    if (failNextSave) {
      failNextSave = false;
      throw new org.springframework.dao.DataAccessResourceFailureException("Storage unavailable");
    }
    if (!Objects.equals(candidate.version(), saved == null ? null : saved.version())) {
      throw new OptimisticLockingFailureException("World changed");
    }
    saved = new SurviveSavedWorld(candidate.id(), saved == null ? 0L : saved.version() + 1,
        candidate.revision(), candidate.shelters(), candidate.boats(), candidate.events(), candidate.players());
    return saved;
  }
}
