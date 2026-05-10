package dev.christopherbell.vehicle.model;

import lombok.Builder;

/**
 * Combined state for vehicle data collection integrations.
 */
@Builder
public record VehicleDataCollectionState(
    RandomVinImportState randomVin,
    NhtsaVinImportState nhtsa
) {}
