package dev.christopherbell.survive.persistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** One versioned Mongo replacement preserves atomic gifts without multi-document transactions. */
@Repository
@MongoPersistence
public class MongoSurviveWorldRepository implements SurviveWorldRepository {
  private final KindScopedMongoOperations<SurviveSavedWorld> worlds;

  public MongoSurviveWorldRepository(DomainMongoOperationsFactory factory) {
    worlds = factory.forType(SurviveSavedWorld.class);
  }

  @Override public Optional<SurviveSavedWorld> load() { return worlds.findById("shared"); }
  @Override public SurviveSavedWorld save(SurviveSavedWorld world) { return worlds.save(world); }
}
