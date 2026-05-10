package dev.christopherbell.vehicle;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Service for vehicle storage and retrieval.
 */
@RequiredArgsConstructor
@Service
public class VehicleService {
  private final VehicleMapper vehicleMapper;
  private final VehicleRepository vehicleRepository;

  public VehicleDetail createVehicle(VehicleCreateRequest request)
      throws InvalidRequestException, ResourceExistsException {
    validateVehicleRequest(request);

    var vehicle = vehicleMapper.toVehicle(request);
    vehicle.setId(UUID.randomUUID().toString());

    try {
      var savedVehicle = vehicleRepository.save(vehicle);
      return vehicleMapper.toVehicleDetail(savedVehicle);
    } catch (DuplicateKeyException e) {
      throw new ResourceExistsException("Vehicle already exists for VIN: " + request.getVin(), e);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to save vehicle", e);
    }
  }

  public VehicleDetail deleteVehicleById(String id)
      throws InvalidRequestException, ResourceNotFoundException {
    validateId(id);

    var vehicle = vehicleRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));

    try {
      vehicleRepository.delete(vehicle);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to delete vehicle with id: " + id, e);
    }

    return vehicleMapper.toVehicleDetail(vehicle);
  }

  public List<VehicleDetail> getVehicles() {
    return vehicleRepository.findAllByOrderByMakeAscModelAscYearDesc().stream()
        .map(vehicleMapper::toVehicleDetail)
        .toList();
  }

  public List<VehicleDetail> getVehiclesByMake(String make) throws InvalidRequestException {
    if (make == null || make.isBlank()) {
      throw new InvalidRequestException("Vehicle make cannot be null or blank.");
    }

    return vehicleRepository.findByMakeIgnoreCase(make).stream()
        .map(vehicleMapper::toVehicleDetail)
        .toList();
  }

  public VehicleDetail getVehicleById(String id)
      throws InvalidRequestException, ResourceNotFoundException {
    validateId(id);

    return vehicleRepository.findById(id)
        .map(vehicleMapper::toVehicleDetail)
        .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));
  }

  public VehicleDetail updateVehicle(String id, VehicleUpdateRequest request)
      throws InvalidRequestException, ResourceExistsException, ResourceNotFoundException {
    validateId(id);
    validateVehicleRequest(request);

    var existing = vehicleRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));

    var vehicleToUpdate = vehicleMapper.toVehicle(request);
    vehicleToUpdate.setId(existing.getId());
    vehicleToUpdate.setCreatedBy(existing.getCreatedBy());
    vehicleToUpdate.setCreatedOn(existing.getCreatedOn());
    vehicleToUpdate.setLastModifiedBy(existing.getLastModifiedBy());
    vehicleToUpdate.setLastUpdatedOn(existing.getLastUpdatedOn());

    try {
      var savedVehicle = vehicleRepository.save(vehicleToUpdate);
      return vehicleMapper.toVehicleDetail(savedVehicle);
    } catch (DuplicateKeyException e) {
      throw new ResourceExistsException("Vehicle already exists for VIN: " + request.vin(), e);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to update vehicle with id: " + id, e);
    }
  }

  private void validateId(String id) throws InvalidRequestException {
    if (id == null || id.isBlank()) {
      throw new InvalidRequestException("Vehicle id cannot be null or blank.");
    }
  }

  private void validateVehicleRequest(VehicleCreateRequest request) throws InvalidRequestException {
    if (request == null) {
      throw new InvalidRequestException("Vehicle request cannot be null.");
    }
    validateVehicleFields(request.getVin(), request.getMake(), request.getModel(), request.getYear());
  }

  private void validateVehicleRequest(VehicleUpdateRequest request) throws InvalidRequestException {
    if (request == null) {
      throw new InvalidRequestException("Vehicle request cannot be null.");
    }
    validateVehicleFields(request.vin(), request.make(), request.model(), request.year());
  }

  private void validateVehicleFields(String vin, String make, String model, Integer year)
      throws InvalidRequestException {
    if (vin == null || vin.isBlank()) {
      throw new InvalidRequestException("Vehicle VIN cannot be null or blank.");
    }
    if (make == null || make.isBlank()) {
      throw new InvalidRequestException("Vehicle make cannot be null or blank.");
    }
    if (model == null || model.isBlank()) {
      throw new InvalidRequestException("Vehicle model cannot be null or blank.");
    }
    if (year == null) {
      throw new InvalidRequestException("Vehicle year cannot be null.");
    }
  }
}
