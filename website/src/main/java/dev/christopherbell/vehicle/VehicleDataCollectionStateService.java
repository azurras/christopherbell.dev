package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.VehicleDataCollectionState;
import dev.christopherbell.vehicle.nhtsa.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.RandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reads vehicle data collection state.
 */
@RequiredArgsConstructor
@Service
public class VehicleDataCollectionStateService {
  private static final String NHTSA_STATE_ID = "nhtsa";
  private static final String NHTSA_STATE_NOTE = "VIN enrichment data sourced from NHTSA vPIC";
  private static final String RANDOM_VIN_STATE_ID = "randomvin";
  private static final String RANDOM_VIN_STATE_NOTE = "VIN data sourced from randomvin.com";

  private final Clock clock;
  private final NhtsaVinImportStateRepository nhtsaVinImportStateRepository;
  private final RandomVinImportStateRepository randomVinImportStateRepository;

  /**
   * Gets persisted state for all vehicle data collection jobs.
   *
   * @return the RandomVIN and NHTSA data collection state
   */
  public VehicleDataCollectionState getState() {
    return VehicleDataCollectionState.builder()
        .nhtsa(nhtsaVinImportStateRepository.findById(NHTSA_STATE_ID)
            .orElseGet(this::defaultNhtsaState))
        .randomVin(randomVinImportStateRepository.findById(RANDOM_VIN_STATE_ID)
            .orElseGet(this::defaultRandomVinState))
        .build();
  }

  /**
   * Builds the default NHTSA state when no persisted state exists yet.
   *
   * @return a default NHTSA import state
   */
  private NhtsaVinImportState defaultNhtsaState() {
    return NhtsaVinImportState.builder()
        .id(NHTSA_STATE_ID)
        .callsOnDate(LocalDate.now(clock))
        .callsToday(0)
        .lifetimeCalls(0L)
        .lifetimeVinsProcessed(0L)
        .notes(NHTSA_STATE_NOTE)
        .vinsProcessedToday(0)
        .build();
  }

  /**
   * Builds the default RandomVIN state when no persisted state exists yet.
   *
   * @return a default RandomVIN import state
   */
  private RandomVinImportState defaultRandomVinState() {
    return RandomVinImportState.builder()
        .id(RANDOM_VIN_STATE_ID)
        .callsOnDate(LocalDate.now(clock))
        .callsToday(0)
        .lifetimeCalls(0L)
        .lifetimeVinsProcessed(0L)
        .notes(RANDOM_VIN_STATE_NOTE)
        .vinsProcessedToday(0)
        .build();
  }
}
