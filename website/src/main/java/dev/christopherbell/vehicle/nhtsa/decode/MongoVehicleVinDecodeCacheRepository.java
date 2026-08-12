package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public final class MongoVehicleVinDecodeCacheRepository
    extends KindScopedRepositorySupport<VehicleVinDecodeCache>
    implements VehicleVinDecodeCacheRepository {
  public MongoVehicleVinDecodeCacheRepository(DomainMongoOperationsFactory factory) { super(factory, VehicleVinDecodeCache.class); }
  @Override public Optional<VehicleVinDecodeCache> findById(String id) { return findValueById(id); }
  @Override public VehicleVinDecodeCache save(VehicleVinDecodeCache value) { return saveValue(value); }
}
