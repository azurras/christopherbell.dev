package dev.christopherbell.vehicle.model;

import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import lombok.Builder;

/**
 * Combined state for vehicle data collection integrations.
 */
@Builder
public record VehicleDataCollectionState(
    RandomVinImportState randomVin,
    NhtsaVinImportState nhtsa
) {}
