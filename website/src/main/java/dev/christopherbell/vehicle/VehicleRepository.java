package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.Vehicle;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for vehicle documents.
 */
public interface VehicleRepository extends MongoRepository<Vehicle, String> {
  /**
   * Checks whether a vehicle exists with the given VIN.
   *
   * @param vin the VIN to check
   * @return true when a vehicle already exists with the VIN
   */
  boolean existsByVin(String vin);

  /**
   * Finds vehicles with an exact notes value.
   *
   * @param notes the notes value to match
   * @return matching vehicles
   */
  List<Vehicle> findByNotes(String notes);

  /**
   * Finds vehicles that have a VIN value.
   *
   * @return vehicles with non-null VINs
   */
  List<Vehicle> findByVinIsNotNull();

  /**
   * Finds vehicles by make, ignoring case.
   *
   * @param make the make to search for
   * @return matching vehicles
   */
  List<Vehicle> findByMakeIgnoreCase(String make);

  /**
   * Finds all vehicles sorted by make, model, and descending year.
   *
   * @return sorted vehicles
   */
  List<Vehicle> findAllByOrderByMakeAscModelAscYearDesc();
}
