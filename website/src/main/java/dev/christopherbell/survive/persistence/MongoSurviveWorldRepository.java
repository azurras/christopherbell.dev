package dev.christopherbell.survive.persistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** One versioned Mongo replacement preserves atomic gifts without multi-document transactions. */
@Repository
@MongoPersistence
public class MongoSurviveWorldRepository implements SurviveWorldRepository {
  private static final String WORLD_ID = "shared";
  private final KindScopedMongoOperations<SurviveSavedWorld> worlds;
  private volatile SurviveSavedWorld lastRead;

  public MongoSurviveWorldRepository(DomainMongoOperationsFactory factory) {
    worlds = factory.forType(SurviveSavedWorld.class);
  }

  @Override
  public Optional<SurviveSavedWorld> load() {
    var saved = worlds.findById(WORLD_ID);
    lastRead = saved.orElse(null);
    return saved;
  }

  @Override
  public Optional<SurviveSavedWorld> loadForMovement() {
    var cached = lastRead;
    if (cached != null && cached.version() != null && worlds.exists(
        Query.query(Criteria.where("id").is(WORLD_ID).and("version").is(cached.version())))) {
      return Optional.of(cached);
    }
    return load();
  }

  @Override
  public SurviveSavedWorld save(SurviveSavedWorld world) {
    var saved = worlds.save(world);
    lastRead = saved;
    return saved;
  }
}
