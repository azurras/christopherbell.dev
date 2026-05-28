package dev.christopherbell.vehicle.core;

import dev.christopherbell.vehicle.model.VehicleDataCollectionState;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository;
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
  private final Clock clock;
  private final NhtsaVinImportStateRepository nhtsaVinImportStateRepository;
  private final RandomVinImportStateRepository randomVinImportStateRepository;
  private final VehicleProperties vehicleProperties;

  /**
   * Gets persisted state for all vehicle data collection jobs.
   *
   * @return the RandomVIN and NHTSA data collection state
   */
  public VehicleDataCollectionState getState() {
    var nhtsaProperties = vehicleProperties.getNhtsaVin();
    var randomVinProperties = vehicleProperties.getRandomVin();
    return VehicleDataCollectionState.builder()
        .nhtsa(nhtsaVinImportStateRepository.findById(nhtsaProperties.getStateId())
            .orElseGet(this::defaultNhtsaState))
        .randomVin(randomVinImportStateRepository.findById(randomVinProperties.getStateId())
            .orElseGet(this::defaultRandomVinState))
        .build();
  }

  /**
   * Builds the default NHTSA state when no persisted state exists yet.
   *
   * @return a default NHTSA import state
   */
  private NhtsaVinImportState defaultNhtsaState() {
    var properties = vehicleProperties.getNhtsaVin();
    return NhtsaVinImportState.builder()
        .id(properties.getStateId())
        .callsOnDate(LocalDate.now(clock))
        .callsToday(0)
        .lifetimeCalls(0L)
        .lifetimeVinsProcessed(0L)
        .notes(properties.getStateNote())
        .vinsProcessedToday(0)
        .build();
  }

  /**
   * Builds the default RandomVIN state when no persisted state exists yet.
   *
   * @return a default RandomVIN import state
   */
  private RandomVinImportState defaultRandomVinState() {
    var properties = vehicleProperties.getRandomVin();
    return RandomVinImportState.builder()
        .id(properties.getStateId())
        .callsOnDate(LocalDate.now(clock))
        .callsToday(0)
        .lifetimeCalls(0L)
        .lifetimeVinsProcessed(0L)
        .notes(properties.getStateNote())
        .vinsProcessedToday(0)
        .build();
  }
}
