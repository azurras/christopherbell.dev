package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.Vehicle;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for vehicle documents.
 */
public interface VehicleRepository extends MongoRepository<Vehicle, String> {
  boolean existsByVin(String vin);

  List<Vehicle> findByNotes(String notes);

  List<Vehicle> findByVinIsNotNull();

  List<Vehicle> findByMakeIgnoreCase(String make);

  List<Vehicle> findAllByOrderByMakeAscModelAscYearDesc();
}
