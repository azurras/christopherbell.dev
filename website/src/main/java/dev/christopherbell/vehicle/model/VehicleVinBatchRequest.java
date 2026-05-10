package dev.christopherbell.vehicle.model;

import java.util.List;

/**
 * Request for creating vehicles from multiple VINs.
 *
 * @param vins the VINs to store
 */
public record VehicleVinBatchRequest(List<String> vins) {}
