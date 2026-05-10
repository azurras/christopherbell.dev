package dev.christopherbell.vehicle.model;

/**
 * Request for creating a vehicle from a VIN only.
 *
 * @param vin the VIN to store
 */
public record VehicleVinRequest(String vin) {}
