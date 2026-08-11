package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the daily-picks persistence port. */
@Repository
public final class MongoDailyLunchPicksRepository
    extends KindScopedRepositorySupport<DailyLunchPicks>
    implements DailyLunchPicksRepository {
  public MongoDailyLunchPicksRepository(DomainMongoOperationsFactory factory) {
    super(factory, DailyLunchPicks.class);
  }
  @Override public DailyLunchPicks save(DailyLunchPicks picks) { return saveValue(picks); }
  @Override public Optional<DailyLunchPicks> findById(String id) { return findValueById(id); }
}
