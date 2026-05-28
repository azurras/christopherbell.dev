package dev.christopherbell.vehicle.nhtsa.enrichment;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient.NhtsaVinDecodeRequest;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClientException;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Enriches stored VINs with details from NHTSA vPIC.
 */
@Service
@Slf4j
public class NhtsaVinEnrichmentService {
  private final Clock clock;
  private final NhtsaVinClient nhtsaVinClient;
  private final NhtsaVinImportStateRepository nhtsaVinImportStateRepository;
  private final VehicleProperties.NhtsaVin properties;
  private final VehicleRepository vehicleRepository;

  /**
   * Creates an NHTSA enrichment service.
   *
   * @param clock the clock used for deterministic timestamps
   * @param nhtsaVinClient the client used to decode VIN batches
   * @param nhtsaVinImportStateRepository the repository used to persist NHTSA import state
   * @param vehicleProperties vehicle data collection configuration
   * @param vehicleRepository the repository used to read and update vehicles
   */
  public NhtsaVinEnrichmentService(
      Clock clock,
      NhtsaVinClient nhtsaVinClient,
      NhtsaVinImportStateRepository nhtsaVinImportStateRepository,
      VehicleProperties vehicleProperties,
      VehicleRepository vehicleRepository
  ) {
    this.clock = clock;
    this.nhtsaVinClient = nhtsaVinClient;
    this.nhtsaVinImportStateRepository = nhtsaVinImportStateRepository;
    this.properties = vehicleProperties.getNhtsaVin();
    this.vehicleRepository = vehicleRepository;
  }

  /**
   * Enriches all stored VINs that have not already been decoded by NHTSA.
   */
  @Scheduled(fixedDelayString = "${vehicles.nhtsa-vin.fixed-delay}")
  public void enrichStoredVins() {
    if (!properties.isEnabled()) {
      return;
    }
    log.info("NHTSA VIN enrichment job started.");
    try {
      var state = currentState();
      if (isCoolingDown(state)) {
        log.info("NHTSA VIN enrichment is cooling down until {}.", state.getDisabledUntil());
        return;
      }
      if (isPermanentlyDisabled(state)) {
        log.info("NHTSA VIN enrichment is permanently disabled after HTTP 403 on {}.", state.getForbiddenOn());
        return;
      }

      var dueVehicles = vehicleRepository.findByVinIsNotNull().stream()
          .filter(vehicle -> !isBlank(vehicle.getVin()))
          .filter(this::isDueForEnrichment)
          .toList();

      for (var batch : batches(dueVehicles)) {
        if (isCoolingDown(state) || isPermanentlyDisabled(state)) {
          return;
        }
        enrichVehicleBatch(state, batch);
      }
    } finally {
      log.info("NHTSA VIN enrichment job completed.");
    }
  }

  /**
   * Determines whether a vehicle still needs NHTSA enrichment.
   *
   * @param vehicle the vehicle to inspect
   * @return true when the vehicle has not already been decoded
   */
  private boolean isDueForEnrichment(Vehicle vehicle) {
    return vehicle.getNhtsaLastDecodedOn() == null;
  }

  /**
   * Enriches a batch of vehicles with one NHTSA batch decode request.
   *
   * @param state the persisted NHTSA import state to update
   * @param vehicles the vehicles to enrich in one batch
   */
  private void enrichVehicleBatch(NhtsaVinImportState state, List<Vehicle> vehicles) {
    try {
      recordAttempt(state, vehicles.size());
      var decodedValuesByVin = decodedValuesByVin(nhtsaVinClient.decodeVins(toDecodeRequests(vehicles)));
      for (var vehicle : vehicles) {
        var decodedValues = decodedValuesByVin.get(normalizeVin(vehicle.getVin()));
        if (decodedValues == null) {
          deleteUnusableVehicle(vehicle, "NHTSA batch response did not include VIN");
          continue;
        }
        if (!hasUsableDecodedValues(decodedValues)) {
          deleteUnusableVehicle(vehicle, "NHTSA returned no usable data for VIN");
          continue;
        }

        applyDecodedValues(vehicle, decodedValues);
        vehicleRepository.save(vehicle);
        log.info("Enriched vehicle VIN {} with NHTSA details.", vehicle.getVin());
      }
    } catch (InvalidRequestException e) {
      log.warn("NHTSA batch enrichment skipped: {}", e.getMessage());
    } catch (NhtsaVinClientException e) {
      handleClientFailure(state, e);
    } catch (IOException e) {
      log.warn("NHTSA batch enrichment failed while fetching VINs.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("NHTSA batch enrichment interrupted.", e);
    } catch (DataAccessException e) {
      log.error("NHTSA batch enrichment failed while saving VIN details.", e);
    }
  }

  /**
   * Deletes a vehicle when NHTSA cannot produce usable data for its VIN.
   *
   * @param vehicle the vehicle to delete
   * @param reason the reason to include in logs
   */
  private void deleteUnusableVehicle(Vehicle vehicle, String reason) {
    log.warn("{} {}. Deleting vehicle {}.", reason, vehicle.getVin(), vehicle.getId());
    vehicleRepository.delete(vehicle);
  }

  /**
   * Loads the persisted NHTSA import state or creates an initialized state for today.
   *
   * @return the current NHTSA import state
   */
  private NhtsaVinImportState currentState() {
    var today = LocalDate.now(clock);
    var state = nhtsaVinImportStateRepository.findById(properties.getStateId())
        .orElseGet(() -> NhtsaVinImportState.builder()
            .id(properties.getStateId())
            .callsOnDate(today)
            .callsToday(0)
            .lifetimeCalls(0L)
            .lifetimeVinsProcessed(0L)
            .notes(properties.getStateNote())
            .vinsProcessedToday(0)
            .build());

    if (!today.equals(state.getCallsOnDate())) {
      state.setCallsOnDate(today);
      state.setCallsToday(0);
      state.setVinsProcessedToday(0);
    }
    state.setNotes(properties.getStateNote());
    return state;
  }

  /**
   * Determines whether NHTSA enrichment is inside a temporary cooldown window.
   *
   * @param state the persisted NHTSA import state
   * @return true when enrichment should be skipped until the cooldown expires
   */
  private boolean isCoolingDown(NhtsaVinImportState state) {
    return state.getDisabledUntil() != null && state.getDisabledUntil().isAfter(Instant.now(clock));
  }

  /**
   * Determines whether NHTSA enrichment was permanently disabled after an HTTP 403.
   *
   * @param state the persisted NHTSA import state
   * @return true when no more NHTSA calls should be made
   */
  private boolean isPermanentlyDisabled(NhtsaVinImportState state) {
    return Boolean.TRUE.equals(state.getPermanentlyDisabled());
  }

  /**
   * Records one outbound NHTSA batch call and the number of VINs included.
   *
   * @param state the persisted NHTSA import state to update
   * @param vinsProcessed the number of VINs included in the batch request
   */
  private void recordAttempt(NhtsaVinImportState state, int vinsProcessed) {
    state.setLastAttemptOn(Instant.now(clock));
    state.setCallsToday(Optional.ofNullable(state.getCallsToday()).orElse(0) + 1);
    state.setLifetimeCalls(Optional.ofNullable(state.getLifetimeCalls()).orElse(0L) + 1);
    state.setVinsProcessedToday(Optional.ofNullable(state.getVinsProcessedToday()).orElse(0) + vinsProcessed);
    state.setLifetimeVinsProcessed(
        Optional.ofNullable(state.getLifetimeVinsProcessed()).orElse(0L) + vinsProcessed);
    state.setCallsOnDate(LocalDate.now(clock));
    state.setNotes(properties.getStateNote());
    nhtsaVinImportStateRepository.save(state);
  }

  /**
   * Applies NHTSA HTTP failure guards and records the failure in persisted state.
   *
   * @param state the persisted NHTSA import state to update
   * @param e the client exception containing the HTTP status
   */
  private void handleClientFailure(NhtsaVinImportState state, NhtsaVinClientException e) {
    var now = Instant.now(clock);
    state.setLastFailureOn(now);
    state.setLastFailureStatus(e.getStatusCode());
    state.setNotes(properties.getStateNote());
    if (e.getStatusCode() == 403) {
      state.setForbiddenOn(now);
      state.setPermanentlyDisabled(true);
      state.setDisabledUntil(null);
    } else if (e.getStatusCode() == 429) {
      state.setDisabledUntil(now.plus(properties.getCooldown()));
    }
    nhtsaVinImportStateRepository.save(state);
    log.warn("NHTSA batch enrichment failed with HTTP status {}.", e.getStatusCode());
  }

  /**
   * Splits vehicles into NHTSA-supported batch sizes.
   *
   * @param vehicles the vehicles due for enrichment
   * @return vehicle batches with at most the configured batch size entries each
   */
  private List<List<Vehicle>> batches(List<Vehicle> vehicles) {
    var batchSize = Math.max(1, properties.getBatchSize());
    return IntStream.iterate(0, start -> start < vehicles.size(), start -> start + batchSize)
        .mapToObj(start -> vehicles.subList(start, Math.min(start + batchSize, vehicles.size())))
        .toList();
  }

  /**
   * Converts vehicles into NHTSA batch decode request entries.
   *
   * @param vehicles the vehicles to decode
   * @return decode request entries for NHTSA
   */
  private List<NhtsaVinDecodeRequest> toDecodeRequests(List<Vehicle> vehicles) {
    return vehicles.stream()
        .map(vehicle -> new NhtsaVinDecodeRequest(vehicle.getVin(), vehicle.getYear()))
        .toList();
  }

  /**
   * Indexes NHTSA decoded values by normalized VIN.
   *
   * @param decodedValues the NHTSA response rows
   * @return response rows keyed by normalized VIN
   */
  private Map<String, Map<String, String>> decodedValuesByVin(List<Map<String, String>> decodedValues) {
    var valuesByVin = new HashMap<String, Map<String, String>>();
    for (var values : decodedValues) {
      var vin = normalizeVin(value(values, "VIN"));
      if (!isBlank(vin)) {
        valuesByVin.put(vin, values);
      }
    }
    return valuesByVin;
  }

  /**
   * Normalizes a VIN for matching request and response rows.
   *
   * @param vin the VIN to normalize
   * @return the normalized VIN, or null when no VIN was provided
   */
  private String normalizeVin(String vin) {
    return vin == null ? null : vin.trim().toUpperCase();
  }

  /**
   * Applies decoded NHTSA values to a vehicle without overwriting user-entered fields.
   *
   * @param vehicle the vehicle to update
   * @param decodedValues the decoded values returned by NHTSA
   */
  private void applyDecodedValues(Vehicle vehicle, Map<String, String> decodedValues) {
    var now = Instant.now(clock);
    vehicle.setNhtsaDecodedValues(decodedValues);
    vehicle.setNhtsaErrorCode(value(decodedValues, "ErrorCode"));
    vehicle.setNhtsaErrorText(value(decodedValues, "ErrorText"));
    vehicle.setNhtsaLastDecodedOn(now);
    vehicle.setLastUpdatedOn(now);

    setIfBlank(vehicle.getMake(), value(decodedValues, "Make"), vehicle::setMake);
    setIfBlank(vehicle.getModel(), value(decodedValues, "Model"), vehicle::setModel);
    setIfBlank(vehicle.getTrim(), value(decodedValues, "Trim"), vehicle::setTrim);
    setIfBlank(vehicle.getBodyStyle(), value(decodedValues, "BodyClass"), vehicle::setBodyStyle);
    setIfBlank(vehicle.getBodyClass(), value(decodedValues, "BodyClass"), vehicle::setBodyClass);
    setIfBlank(vehicle.getFuelType(), value(decodedValues, "FuelTypePrimary"), vehicle::setFuelType);
    setIfBlank(vehicle.getTransmission(), transmission(decodedValues), vehicle::setTransmission);
    setIfBlank(vehicle.getDrivetrain(), value(decodedValues, "DriveType"), vehicle::setDrivetrain);
    setIfBlank(vehicle.getEngine(), engine(decodedValues), vehicle::setEngine);
    setIfBlank(vehicle.getGvwr(), value(decodedValues, "GVWR"), vehicle::setGvwr);
    setIfBlank(vehicle.getManufacturer(), value(decodedValues, "Manufacturer"), vehicle::setManufacturer);
    setIfBlank(vehicle.getManufacturerId(), value(decodedValues, "ManufacturerId"), vehicle::setManufacturerId);
    setIfBlank(vehicle.getPlantCity(), value(decodedValues, "PlantCity"), vehicle::setPlantCity);
    setIfBlank(vehicle.getPlantCountry(), value(decodedValues, "PlantCountry"), vehicle::setPlantCountry);
    setIfBlank(vehicle.getPlantState(), value(decodedValues, "PlantState"), vehicle::setPlantState);
    setIfBlank(vehicle.getSeries(), value(decodedValues, "Series"), vehicle::setSeries);
    setIfBlank(vehicle.getVehicleType(), value(decodedValues, "VehicleType"), vehicle::setVehicleType);

    if (vehicle.getYear() == null) {
      vehicle.setYear(toInteger(value(decodedValues, "ModelYear")));
    }
    if (vehicle.getDoors() == null) {
      vehicle.setDoors(toInteger(value(decodedValues, "Doors")));
    }
  }

  /**
   * Determines whether an NHTSA response row contains enough data to keep the vehicle.
   *
   * @param decodedValues the decoded values returned by NHTSA
   * @return true when identifying vehicle data exists
   */
  private boolean hasUsableDecodedValues(Map<String, String> decodedValues) {
    return !isBlank(value(decodedValues, "Make"))
        || !isBlank(value(decodedValues, "Model"))
        || !isBlank(value(decodedValues, "ModelYear"))
        || !isBlank(value(decodedValues, "VehicleType"));
  }

  /**
   * Builds a displayable engine description from NHTSA decoded values.
   *
   * @param decodedValues the decoded values returned by NHTSA
   * @return an engine description, or null when no engine data is present
   */
  private String engine(Map<String, String> decodedValues) {
    var displacement = value(decodedValues, "DisplacementL");
    var cylinders = value(decodedValues, "EngineCylinders");
    var model = value(decodedValues, "EngineModel");

    if (isBlank(displacement) && isBlank(cylinders) && isBlank(model)) {
      return null;
    }

    var description = "";
    if (!isBlank(displacement)) {
      description += displacement + "L";
    }
    if (!isBlank(cylinders)) {
      description += (description.isBlank() ? "" : " ") + cylinders + " cylinder";
    }
    if (!isBlank(model)) {
      description += (description.isBlank() ? "" : " ") + model;
    }
    return description;
  }

  /**
   * Builds a displayable transmission description from NHTSA decoded values.
   *
   * @param decodedValues the decoded values returned by NHTSA
   * @return a transmission description, or null when no transmission data is present
   */
  private String transmission(Map<String, String> decodedValues) {
    var style = value(decodedValues, "TransmissionStyle");
    var speeds = value(decodedValues, "TransmissionSpeeds");
    if (isBlank(style)) {
      return null;
    }
    if (isBlank(speeds)) {
      return style;
    }
    return speeds + "-speed " + style;
  }

  /**
   * Sets a string field only when the current field value is blank and the new value is present.
   *
   * @param currentValue the current vehicle field value
   * @param newValue the decoded value to apply
   * @param setter the vehicle setter for the field
   */
  private void setIfBlank(String currentValue, String newValue, java.util.function.Consumer<String> setter) {
    if (isBlank(currentValue) && !isBlank(newValue)) {
      setter.accept(newValue);
    }
  }

  /**
   * Parses an integer from a decoded NHTSA value.
   *
   * @param value the decoded value to parse
   * @return the parsed integer, or null when the value is blank or invalid
   */
  private Integer toInteger(String value) {
    if (isBlank(value)) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Reads one decoded value from an NHTSA response row.
   *
   * @param decodedValues the decoded values returned by NHTSA
   * @param key the NHTSA field key
   * @return the decoded value for the key
   */
  private String value(Map<String, String> decodedValues, String key) {
    return decodedValues.get(key);
  }

  /**
   * Determines whether a string is null or blank.
   *
   * @param value the value to inspect
   * @return true when the value is null or blank
   */
  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
