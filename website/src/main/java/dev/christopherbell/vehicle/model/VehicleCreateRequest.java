package dev.christopherbell.vehicle.model;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to create a vehicle.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class VehicleCreateRequest {
  private String bodyStyle;
  private String color;
  private String drivetrain;
  private String engine;
  private String fuelType;
  private String licensePlate;
  private String licensePlateState;
  private String make;
  private Integer mileage;
  private String model;
  private String nickname;
  private String notes;
  private LocalDate purchaseDate;
  private String transmission;
  private String trim;
  private String vin;
  private Integer year;
}
