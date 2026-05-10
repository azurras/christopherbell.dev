package dev.christopherbell.vehicle;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.RandomVinImportState;
import dev.christopherbell.vehicle.model.Vehicle;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Imports generated VINs into the vehicles collection.
 */
@Service
@Slf4j
public class RandomVinImportService {
  private static final Duration RANDOM_VIN_COOLDOWN = Duration.ofHours(24);
  private static final int MAX_RANDOM_VIN_CALLS_PER_DAY = 50;
  private static final String IMPORT_STATE_ID = "randomvin";
  private static final String IMPORT_STATE_NOTE = "VIN data sourced from randomvin.com";
  private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");
  private static final String LEGACY_IMPORT_NOTE = "Imported from randomvin.com";

  private final RandomVinClient randomVinClient;
  private final RandomVinImportStateRepository randomVinImportStateRepository;
  private final VehicleRepository vehicleRepository;
  private final Clock clock;
  private final boolean randomVinCollectionEnabled;

  public RandomVinImportService(
      RandomVinClient randomVinClient,
      RandomVinImportStateRepository randomVinImportStateRepository,
      VehicleRepository vehicleRepository,
      Clock clock,
      @Value("${vehicles.random-vin.enabled:true}") boolean randomVinCollectionEnabled
  ) {
    this.randomVinClient = randomVinClient;
    this.randomVinImportStateRepository = randomVinImportStateRepository;
    this.vehicleRepository = vehicleRepository;
    this.clock = clock;
    this.randomVinCollectionEnabled = randomVinCollectionEnabled;
  }

  @PostConstruct
  public void removeLegacyRandomVinNotes() {
    vehicleRepository.findByNotes(LEGACY_IMPORT_NOTE).forEach(vehicle -> {
      vehicle.setNotes(null);
      vehicleRepository.save(vehicle);
    });
  }

  @Scheduled(fixedDelayString = "${vehicles.random-vin.fixed-delay:600000}")
  public void importRandomVin() {
    if (!randomVinCollectionEnabled) {
      log.debug("RandomVIN collection is disabled.");
      return;
    }

    try {
      var state = currentState();
      if (isCoolingDown(state)) {
        log.info("RandomVIN collection is cooling down until {}.", state.getDisabledUntil());
        return;
      }
      if (isPermanentlyDisabled(state)) {
        log.info("RandomVIN collection is permanently disabled after HTTP 403 on {}.", state.getForbiddenOn());
        return;
      }
      if (hasReachedDailyCap(state)) {
        log.info("RandomVIN daily call cap has been reached.");
        return;
      }

      recordAttempt(state);
      var vin = normalizeVin(getRandomVin(state));
      recordVinsProcessed(state, 1);
      saveVin(vin);
    } catch (DuplicateKeyException e) {
      log.info("RandomVIN returned a VIN that already exists.");
    } catch (InvalidRequestException e) {
      log.warn("RandomVIN import skipped: {}", e.getMessage());
    } catch (IOException e) {
      log.warn("RandomVIN import failed while fetching VIN.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("RandomVIN import interrupted.", e);
    } catch (DataAccessException e) {
      log.error("RandomVIN import failed while saving vehicle.", e);
    }
  }

  private String getRandomVin(RandomVinImportState state)
      throws IOException, InterruptedException, InvalidRequestException {
    try {
      return randomVinClient.getVin();
    } catch (RandomVinClientException e) {
      handleClientFailure(state, e);
      throw new InvalidRequestException(e.getMessage(), e);
    }
  }

  private RandomVinImportState currentState() {
    var today = LocalDate.now(clock);
    var state = randomVinImportStateRepository.findById(IMPORT_STATE_ID)
        .orElseGet(() -> RandomVinImportState.builder()
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

  private boolean isCoolingDown(RandomVinImportState state) {
    return state.getDisabledUntil() != null && state.getDisabledUntil().isAfter(Instant.now(clock));
  }

  private boolean isPermanentlyDisabled(RandomVinImportState state) {
    return Boolean.TRUE.equals(state.getPermanentlyDisabled());
  }

  private boolean hasReachedDailyCap(RandomVinImportState state) {
    return Optional.ofNullable(state.getCallsToday()).orElse(0) >= MAX_RANDOM_VIN_CALLS_PER_DAY;
  }

  private void recordAttempt(RandomVinImportState state) {
    state.setLastAttemptOn(Instant.now(clock));
    state.setCallsToday(Optional.ofNullable(state.getCallsToday()).orElse(0) + 1);
    state.setLifetimeCalls(Optional.ofNullable(state.getLifetimeCalls()).orElse(0L) + 1);
    state.setCallsOnDate(LocalDate.now(clock));
    state.setNotes(IMPORT_STATE_NOTE);
    randomVinImportStateRepository.save(state);
  }

  private void recordVinsProcessed(RandomVinImportState state, int vinsProcessed) {
    state.setVinsProcessedToday(Optional.ofNullable(state.getVinsProcessedToday()).orElse(0) + vinsProcessed);
    state.setLifetimeVinsProcessed(
        Optional.ofNullable(state.getLifetimeVinsProcessed()).orElse(0L) + vinsProcessed);
    state.setNotes(IMPORT_STATE_NOTE);
    randomVinImportStateRepository.save(state);
  }

  private void handleClientFailure(RandomVinImportState state, RandomVinClientException e) {
    var now = Instant.now(clock);
    state.setLastFailureOn(now);
    state.setLastFailureStatus(e.getStatusCode());
    state.setNotes(IMPORT_STATE_NOTE);
    if (e.getStatusCode() == 403) {
      state.setForbiddenOn(now);
      state.setPermanentlyDisabled(true);
      state.setDisabledUntil(null);
    } else if (e.getStatusCode() == 429) {
      state.setDisabledUntil(now.plus(RANDOM_VIN_COOLDOWN));
    }
    randomVinImportStateRepository.save(state);
    log.warn("RandomVIN import failed with HTTP status {}.", e.getStatusCode());
  }

  private void saveVin(String vin) {
    if (vehicleRepository.existsByVin(vin)) {
      log.info("RandomVIN returned an existing VIN; skipping import.");
      return;
    }

    var now = Instant.now(clock);
    var vehicle = Vehicle.builder()
        .id(UUID.randomUUID().toString())
        .createdOn(now)
        .lastUpdatedOn(now)
        .vin(vin)
        .build();

    vehicleRepository.save(vehicle);
    log.info("Imported random VIN {}.", vin);
  }

  private String normalizeVin(String rawVin) throws InvalidRequestException {
    if (rawVin == null) {
      throw new InvalidRequestException("RandomVIN response was empty.");
    }

    var vin = rawVin.trim().toUpperCase();
    if (!VIN_PATTERN.matcher(vin).matches()) {
      throw new InvalidRequestException("RandomVIN response did not contain a valid VIN.");
    }

    return vin;
  }
}
