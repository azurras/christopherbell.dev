package dev.christopherbell.vehicle;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.vehicle.core.VehicleCrudService;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import dev.christopherbell.vehicle.vin.VehicleVinService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Facade for vehicle APIs while storage and VIN creation live in focused
 * subfeature services.
 */
@RequiredArgsConstructor
@Service
public class VehicleService {
  private final VehicleCrudService vehicleCrudService;
  private final VehicleVinService vehicleVinService;

  /**
   * Creates a new vehicle document from complete vehicle details.
   */
  public VehicleDetail createVehicle(VehicleCreateRequest request)
      throws InvalidRequestException, ResourceExistsException {
    return vehicleCrudService.createVehicle(request);
  }

  /**
   * Creates a new vehicle document from a VIN only.
   */
  public VehicleDetail createVehicleFromVin(VehicleVinRequest request)
      throws InvalidRequestException, ResourceExistsException {
    return vehicleVinService.createVehicleFromVin(request);
  }

  /**
   * Creates new vehicle documents from multiple VINs.
   */
  public List<VehicleDetail> createVehiclesFromVins(VehicleVinBatchRequest request)
      throws InvalidRequestException, ResourceExistsException {
    return vehicleVinService.createVehiclesFromVins(request);
  }

  /**
   * Deletes a vehicle by id.
   */
  public VehicleDetail deleteVehicleById(String id)
      throws InvalidRequestException, ResourceNotFoundException {
    return vehicleCrudService.deleteVehicleById(id);
  }

  /**
   * Gets all vehicles sorted by make, model, and year.
   */
  public List<VehicleDetail> getVehicles() {
    return vehicleCrudService.getVehicles();
  }

  /**
   * Gets vehicles matching a make, ignoring case.
   */
  public List<VehicleDetail> getVehiclesByMake(String make) throws InvalidRequestException {
    return vehicleCrudService.getVehiclesByMake(make);
  }

  /**
   * Gets a vehicle by id.
   */
  public VehicleDetail getVehicleById(String id)
      throws InvalidRequestException, ResourceNotFoundException {
    return vehicleCrudService.getVehicleById(id);
  }

  /**
   * Updates an existing vehicle by id.
   */
  public VehicleDetail updateVehicle(String id, VehicleUpdateRequest request)
      throws InvalidRequestException, ResourceExistsException, ResourceNotFoundException {
    return vehicleCrudService.updateVehicle(id, request);
  }
}
