package dev.christopherbell.vehicle.randomvin.importing;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public final class MongoRandomVinImportStateRepository
    extends KindScopedRepositorySupport<RandomVinImportState>
    implements RandomVinImportStateRepository {
  public MongoRandomVinImportStateRepository(DomainMongoOperationsFactory factory) { super(factory, RandomVinImportState.class); }
  @Override public Optional<RandomVinImportState> findById(String id) { return findValueById(id); }
  @Override public RandomVinImportState save(RandomVinImportState value) { return saveValue(value); }
}
