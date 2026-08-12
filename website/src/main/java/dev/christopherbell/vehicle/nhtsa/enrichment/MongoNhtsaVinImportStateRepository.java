package dev.christopherbell.vehicle.nhtsa.enrichment;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public final class MongoNhtsaVinImportStateRepository
    extends KindScopedRepositorySupport<NhtsaVinImportState>
    implements NhtsaVinImportStateRepository {
  public MongoNhtsaVinImportStateRepository(DomainMongoOperationsFactory factory) { super(factory, NhtsaVinImportState.class); }
  @Override public Optional<NhtsaVinImportState> findById(String id) { return findValueById(id); }
  @Override public NhtsaVinImportState save(NhtsaVinImportState value) { return saveValue(value); }
}
