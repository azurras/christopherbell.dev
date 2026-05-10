package dev.christopherbell.vehicle;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.NhtsaVinClient.NhtsaVinDecodeRequest;
import dev.christopherbell.vehicle.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.model.Vehicle;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
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
  private static final Duration NHTSA_COOLDOWN = Duration.ofHours(24);
  private static final int NHTSA_BATCH_SIZE = 50;
  private static final String IMPORT_STATE_ID = "nhtsa";
  private static final String IMPORT_STATE_NOTE = "VIN enrichment data sourced from NHTSA vPIC";

  private final Clock clock;
  private final NhtsaVinClient nhtsaVinClient;
  private final NhtsaVinImportStateRepository nhtsaVinImportStateRepository;
  private final VehicleRepository vehicleRepository;

  public NhtsaVinEnrichmentService(
      Clock clock,
      NhtsaVinClient nhtsaVinClient,
      NhtsaVinImportStateRepository nhtsaVinImportStateRepository,
      VehicleRepository vehicleRepository
  ) {
    this.clock = clock;
    this.nhtsaVinClient = nhtsaVinClient;
    this.nhtsaVinImportStateRepository = nhtsaVinImportStateRepository;
    this.vehicleRepository = vehicleRepository;
  }

  @Scheduled(fixedDelayString = "${vehicles.nhtsa-vin.fixed-delay:3600000}")
  public void enrichStoredVins() {
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
  }

  private boolean isDueForEnrichment(Vehicle vehicle) {
    return vehicle.getNhtsaLastDecodedOn() == null;
  }

  private void enrichVehicleBatch(NhtsaVinImportState state, List<Vehicle> vehicles) {
    try {
      recordAttempt(state, vehicles.size());
      var decodedValuesByVin = decodedValuesByVin(nhtsaVinClient.decodeVins(toDecodeRequests(vehicles)));
      for (var vehicle : vehicles) {
        var decodedValues = decodedValuesByVin.get(normalizeVin(vehicle.getVin()));
        if (decodedValues == null) {
          log.warn("NHTSA batch response did not include VIN {}.", vehicle.getVin());
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

  private NhtsaVinImportState currentState() {
    var today = LocalDate.now(clock);
    var state = nhtsaVinImportStateRepository.findById(IMPORT_STATE_ID)
        .orElseGet(() -> NhtsaVinImportState.builder()
            .id(IMPORT_STATE_ID)
            .callsOnDate(today)
            .callsToday(0)
            .lifetimeCalls(0L)
            .lifetimeVinsProcessed(0L)
            .notes(IMPORT_STATE_NOTE)
            .vinsProcessedToday(0)
            .build());

    if (!today.equals(state.getCallsOnDate())) {
      state.setCallsOnDate(today);
      state.setCallsToday(0);
      state.setVinsProcessedToday(0);
    }
    state.setNotes(IMPORT_STATE_NOTE);
    return state;
  }

  private boolean isCoolingDown(NhtsaVinImportState state) {
    return state.getDisabledUntil() != null && state.getDisabledUntil().isAfter(Instant.now(clock));
  }

  private boolean isPermanentlyDisabled(NhtsaVinImportState state) {
    return Boolean.TRUE.equals(state.getPermanentlyDisabled());
  }

  private void recordAttempt(NhtsaVinImportState state, int vinsProcessed) {
    state.setLastAttemptOn(Instant.now(clock));
    state.setCallsToday(Optional.ofNullable(state.getCallsToday()).orElse(0) + 1);
    state.setLifetimeCalls(Optional.ofNullable(state.getLifetimeCalls()).orElse(0L) + 1);
    state.setVinsProcessedToday(Optional.ofNullable(state.getVinsProcessedToday()).orElse(0) + vinsProcessed);
    state.setLifetimeVinsProcessed(
        Optional.ofNullable(state.getLifetimeVinsProcessed()).orElse(0L) + vinsProcessed);
    state.setCallsOnDate(LocalDate.now(clock));
    state.setNotes(IMPORT_STATE_NOTE);
    nhtsaVinImportStateRepository.save(state);
  }

  private void handleClientFailure(NhtsaVinImportState state, NhtsaVinClientException e) {
    var now = Instant.now(clock);
    state.setLastFailureOn(now);
    state.setLastFailureStatus(e.getStatusCode());
    state.setNotes(IMPORT_STATE_NOTE);
    if (e.getStatusCode() == 403) {
      state.setForbiddenOn(now);
      state.setPermanentlyDisabled(true);
      state.setDisabledUntil(null);
    } else if (e.getStatusCode() == 429) {
      state.setDisabledUntil(now.plus(NHTSA_COOLDOWN));
    }
    nhtsaVinImportStateRepository.save(state);
    log.warn("NHTSA batch enrichment failed with HTTP status {}.", e.getStatusCode());
  }

  private List<List<Vehicle>> batches(List<Vehicle> vehicles) {
    return IntStream.iterate(0, start -> start < vehicles.size(), start -> start + NHTSA_BATCH_SIZE)
        .mapToObj(start -> vehicles.subList(start, Math.min(start + NHTSA_BATCH_SIZE, vehicles.size())))
        .toList();
  }

  private List<NhtsaVinDecodeRequest> toDecodeRequests(List<Vehicle> vehicles) {
    return vehicles.stream()
        .map(vehicle -> new NhtsaVinDecodeRequest(vehicle.getVin(), vehicle.getYear()))
        .toList();
  }

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

  private String normalizeVin(String vin) {
    return vin == null ? null : vin.trim().toUpperCase();
  }

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

  private void setIfBlank(String currentValue, String newValue, java.util.function.Consumer<String> setter) {
    if (isBlank(currentValue) && !isBlank(newValue)) {
      setter.accept(newValue);
    }
  }

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

  private String value(Map<String, String> decodedValues, String key) {
    return decodedValues.get(key);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
