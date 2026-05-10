package dev.christopherbell.vehicle;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import dev.christopherbell.vehicle.model.Vehicle;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
  private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

  private final Clock clock;
  private final VehicleMapper vehicleMapper;
  private final VehicleRepository vehicleRepository;

  /**
   * Creates a new vehicle document.
   *
   * @param request the vehicle creation request
   * @return the created vehicle details
   * @throws InvalidRequestException when the request is missing required vehicle fields
   * @throws ResourceExistsException when another vehicle already uses the VIN
   */
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

  /**
   * Creates a new vehicle document from a VIN only.
   *
   * @param request the VIN creation request
   * @return the created vehicle details
   * @throws InvalidRequestException when the VIN is missing or invalid
   * @throws ResourceExistsException when another vehicle already uses the VIN
   */
  public VehicleDetail createVehicleFromVin(VehicleVinRequest request)
      throws InvalidRequestException, ResourceExistsException {
    if (request == null) {
      throw new InvalidRequestException("Vehicle VIN request cannot be null.");
    }

    var vin = normalizeVin(request.vin());
    var vehicle = vehicleFromVin(vin, Instant.now(clock));

    try {
      var savedVehicle = vehicleRepository.save(vehicle);
      return vehicleMapper.toVehicleDetail(savedVehicle);
    } catch (DuplicateKeyException e) {
      throw new ResourceExistsException("Vehicle already exists for VIN: " + vin, e);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to save vehicle", e);
    }
  }

  /**
   * Creates new vehicle documents from multiple VINs.
   *
   * @param request the VIN batch creation request
   * @return the created vehicle details
   * @throws InvalidRequestException when the batch or any VIN is invalid
   * @throws ResourceExistsException when any VIN already exists
   */
  public List<VehicleDetail> createVehiclesFromVins(VehicleVinBatchRequest request)
      throws InvalidRequestException, ResourceExistsException {
    var vins = normalizeBatchVins(request);
    for (var vin : vins) {
      if (vehicleRepository.existsByVin(vin)) {
        throw new ResourceExistsException("Vehicle already exists for VIN: " + vin);
      }
    }

    var now = Instant.now(clock);
    var vehicles = vins.stream()
        .map(vin -> vehicleFromVin(vin, now))
        .toList();

    try {
      return vehicleRepository.saveAll(vehicles).stream()
          .map(vehicleMapper::toVehicleDetail)
          .toList();
    } catch (DuplicateKeyException e) {
      throw new ResourceExistsException("One or more vehicles already exist for the requested VINs.", e);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to save vehicles", e);
    }
  }

  /**
   * Deletes a vehicle by id.
   *
   * @param id the vehicle id to delete
   * @return the deleted vehicle details
   * @throws InvalidRequestException when the id is null or blank
   * @throws ResourceNotFoundException when the vehicle does not exist
   */
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

  /**
   * Gets all vehicles sorted by make, model, and year.
   *
   * @return all stored vehicle details
   */
  public List<VehicleDetail> getVehicles() {
    return vehicleRepository.findAllByOrderByMakeAscModelAscYearDesc().stream()
        .map(vehicleMapper::toVehicleDetail)
        .toList();
  }

  /**
   * Gets vehicles matching a make, ignoring case.
   *
   * @param make the vehicle make to search for
   * @return matching vehicle details
   * @throws InvalidRequestException when the make is null or blank
   */
  public List<VehicleDetail> getVehiclesByMake(String make) throws InvalidRequestException {
    if (make == null || make.isBlank()) {
      throw new InvalidRequestException("Vehicle make cannot be null or blank.");
    }

    return vehicleRepository.findByMakeIgnoreCase(make).stream()
        .map(vehicleMapper::toVehicleDetail)
        .toList();
  }

  /**
   * Gets a vehicle by id.
   *
   * @param id the vehicle id to fetch
   * @return the matching vehicle details
   * @throws InvalidRequestException when the id is null or blank
   * @throws ResourceNotFoundException when the vehicle does not exist
   */
  public VehicleDetail getVehicleById(String id)
      throws InvalidRequestException, ResourceNotFoundException {
    validateId(id);

    return vehicleRepository.findById(id)
        .map(vehicleMapper::toVehicleDetail)
        .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));
  }

  /**
   * Updates an existing vehicle by id.
   *
   * @param id the vehicle id to update
   * @param request the vehicle update request
   * @return the updated vehicle details
   * @throws InvalidRequestException when the id or request is invalid
   * @throws ResourceExistsException when another vehicle already uses the VIN
   * @throws ResourceNotFoundException when the vehicle does not exist
   */
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

  /**
   * Validates that a vehicle id is present.
   *
   * @param id the id to validate
   * @throws InvalidRequestException when the id is null or blank
   */
  private void validateId(String id) throws InvalidRequestException {
    if (id == null || id.isBlank()) {
      throw new InvalidRequestException("Vehicle id cannot be null or blank.");
    }
  }

  /**
   * Validates required fields for a vehicle creation request.
   *
   * @param request the request to validate
   * @throws InvalidRequestException when the request or required fields are invalid
   */
  private void validateVehicleRequest(VehicleCreateRequest request) throws InvalidRequestException {
    if (request == null) {
      throw new InvalidRequestException("Vehicle request cannot be null.");
    }
    validateVehicleFields(request.getVin(), request.getMake(), request.getModel(), request.getYear());
  }

  /**
   * Validates required fields for a vehicle update request.
   *
   * @param request the request to validate
   * @throws InvalidRequestException when the request or required fields are invalid
   */
  private void validateVehicleRequest(VehicleUpdateRequest request) throws InvalidRequestException {
    if (request == null) {
      throw new InvalidRequestException("Vehicle request cannot be null.");
    }
    validateVehicleFields(request.vin(), request.make(), request.model(), request.year());
  }

  /**
   * Validates shared required vehicle fields.
   *
   * @param vin the vehicle VIN
   * @param make the vehicle make
   * @param model the vehicle model
   * @param year the vehicle model year
   * @throws InvalidRequestException when any required value is missing
   */
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

  /**
   * Normalizes and validates a VIN.
   *
   * @param rawVin the raw VIN to normalize
   * @return the normalized VIN
   * @throws InvalidRequestException when the VIN is missing or invalid
   */
  private String normalizeVin(String rawVin) throws InvalidRequestException {
    if (rawVin == null || rawVin.isBlank()) {
      throw new InvalidRequestException("Vehicle VIN cannot be null or blank.");
    }

    var vin = rawVin.trim().toUpperCase();
    if (!VIN_PATTERN.matcher(vin).matches()) {
      throw new InvalidRequestException("Vehicle VIN must be 17 valid VIN characters.");
    }
    return vin;
  }

  /**
   * Normalizes and validates a VIN batch.
   *
   * @param request the VIN batch request
   * @return normalized VINs
   * @throws InvalidRequestException when the request, batch, or any VIN is invalid
   */
  private List<String> normalizeBatchVins(VehicleVinBatchRequest request) throws InvalidRequestException {
    if (request == null || request.vins() == null || request.vins().isEmpty()) {
      throw new InvalidRequestException("Vehicle VIN batch cannot be null or empty.");
    }

    var vins = new java.util.ArrayList<String>();
    for (var rawVin : request.vins()) {
      vins.add(normalizeVin(rawVin));
    }
    var uniqueVins = new HashSet<String>();
    for (var vin : vins) {
      if (!uniqueVins.add(vin)) {
        throw new InvalidRequestException("Vehicle VIN batch cannot contain duplicate VINs.");
      }
    }
    return vins;
  }

  /**
   * Builds a new vehicle entity from a normalized VIN.
   *
   * @param vin the normalized VIN
   * @param now the timestamp to use for audit fields
   * @return a new vehicle entity
   */
  private Vehicle vehicleFromVin(String vin, Instant now) {
    return Vehicle.builder()
        .id(UUID.randomUUID().toString())
        .createdOn(now)
        .lastUpdatedOn(now)
        .vin(vin)
        .build();
  }
}
