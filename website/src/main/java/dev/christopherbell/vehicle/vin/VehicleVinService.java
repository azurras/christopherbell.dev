package dev.christopherbell.vehicle.vin;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import dev.christopherbell.vehicle.core.VehicleMapper;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import dev.christopherbell.vehicle.model.VehicleVins;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Owns vehicle creation flows that start from one or more VINs.
 */
@RequiredArgsConstructor
@Service
public class VehicleVinService {

  private final Clock clock;
  private final VehicleMapper vehicleMapper;
  private final VehicleRepository vehicleRepository;

  /**
   * Creates a vehicle document from a VIN only.
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
    } catch (DuplicateKeyException duplicateVin) {
      throw new ResourceExistsException("Vehicle already exists for VIN: " + vin, duplicateVin);
    } catch (DataAccessException storageFailure) {
      throw new ServiceUnavailableException("Failed to save vehicle", storageFailure);
    }
  }

  /**
   * Creates vehicle documents from a batch of VINs.
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
    } catch (DuplicateKeyException duplicateVin) {
      throw new ResourceExistsException(
          "One or more vehicles already exist for the requested VINs.", duplicateVin);
    } catch (DataAccessException storageFailure) {
      throw new ServiceUnavailableException("Failed to save vehicles", storageFailure);
    }
  }

  private String normalizeVin(String rawVin) throws InvalidRequestException {
    if (rawVin == null || rawVin.isBlank()) {
      throw new InvalidRequestException("Vehicle VIN cannot be null or blank.");
    }

    var vin = VehicleVins.normalize(rawVin);
    if (!VehicleVins.isValid(vin)) {
      throw new InvalidRequestException("Vehicle VIN must be 17 valid VIN characters.");
    }
    return vin;
  }

  private List<String> normalizeBatchVins(VehicleVinBatchRequest request) throws InvalidRequestException {
    if (request == null || request.vins() == null || request.vins().isEmpty()) {
      throw new InvalidRequestException("Vehicle VIN batch cannot be null or empty.");
    }

    var vins = new ArrayList<String>();
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

  private Vehicle vehicleFromVin(String vin, Instant now) {
    return Vehicle.builder()
        .id(UUID.randomUUID().toString())
        .createdOn(now)
        .lastUpdatedOn(now)
        .vin(vin)
        .build();
  }
}
