package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import java.util.Optional;

public interface VehicleVinDecodeCacheRepository {
  Optional<VehicleVinDecodeCache> findById(String id);
  VehicleVinDecodeCache save(VehicleVinDecodeCache cache);
}
