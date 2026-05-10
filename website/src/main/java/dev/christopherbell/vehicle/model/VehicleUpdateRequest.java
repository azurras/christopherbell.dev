package dev.christopherbell.vehicle.model;

import java.time.LocalDate;
import lombok.Builder;

/**
 * Request to update a vehicle.
 */
@Builder
public record VehicleUpdateRequest(
    String bodyStyle,
    String color,
    String drivetrain,
    String engine,
    String fuelType,
    String licensePlate,
    String licensePlateState,
    String make,
    Integer mileage,
    String model,
    String nickname,
    String notes,
    LocalDate purchaseDate,
    String transmission,
    String trim,
    String vin,
    Integer year
) {}
