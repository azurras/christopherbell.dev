package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleVinDecodeCacheRepository extends MongoRepository<VehicleVinDecodeCache, String> {}
