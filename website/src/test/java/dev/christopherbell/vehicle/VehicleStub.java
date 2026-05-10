package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Test fixtures for vehicles.
 */
public final class VehicleStub {
  public static final String BODY_STYLE = "Sedan";
  public static final String COLOR = "Silver";
  public static final String DRIVETRAIN = "FWD";
  public static final String ENGINE = "3.0L V6";
  public static final String FUEL_TYPE = "Gasoline";
  public static final String ID = "7b4fc486-1eaf-4fd2-b606-b4e792d33a9b";
  public static final String ID_2 = "bb0c7a0b-a716-4f52-9e80-3c6deea465ab";
  public static final Instant CREATED_ON = Instant.parse("2026-01-18T02:30:00.809Z");
  public static final Instant LAST_UPDATED_ON = Instant.parse("2026-01-18T02:35:00.809Z");
  public static final String LICENSE_PLATE = "ABC123";
  public static final String LICENSE_PLATE_STATE = "IL";
  public static final String MAKE = "Honda";
  public static final Integer MILEAGE = 125000;
  public static final String MODEL = "Accord";
  public static final String NICKNAME = "Daily";
  public static final String NOTES = "Primary commuter vehicle";
  public static final LocalDate PURCHASE_DATE = LocalDate.of(2020, 6, 15);
  public static final String TRANSMISSION = "Automatic";
  public static final String TRIM = "EX";
  public static final String TYPE = "vehicle";
  public static final String VIN = "1HGCM82633A004352";
  public static final Integer YEAR = 2003;

  private VehicleStub() {}

  public static VehicleCreateRequest getVehicleCreateRequestStub() {
    return VehicleCreateRequest.builder()
        .bodyStyle(BODY_STYLE)
        .color(COLOR)
        .drivetrain(DRIVETRAIN)
        .engine(ENGINE)
        .fuelType(FUEL_TYPE)
        .licensePlate(LICENSE_PLATE)
        .licensePlateState(LICENSE_PLATE_STATE)
        .make(MAKE)
        .mileage(MILEAGE)
        .model(MODEL)
        .nickname(NICKNAME)
        .notes(NOTES)
        .purchaseDate(PURCHASE_DATE)
        .transmission(TRANSMISSION)
        .trim(TRIM)
        .vin(VIN)
        .year(YEAR)
        .build();
  }

  public static VehicleUpdateRequest getVehicleUpdateRequestStub() {
    return VehicleUpdateRequest.builder()
        .bodyStyle(BODY_STYLE)
        .color(COLOR)
        .drivetrain(DRIVETRAIN)
        .engine(ENGINE)
        .fuelType(FUEL_TYPE)
        .licensePlate(LICENSE_PLATE)
        .licensePlateState(LICENSE_PLATE_STATE)
        .make(MAKE)
        .mileage(MILEAGE + 1000)
        .model(MODEL)
        .nickname(NICKNAME)
        .notes("Updated mileage")
        .purchaseDate(PURCHASE_DATE)
        .transmission(TRANSMISSION)
        .trim("EX-L")
        .vin(VIN)
        .year(YEAR)
        .build();
  }

  public static Vehicle getVehicleStub(String id) {
    return Vehicle.builder()
        .id(id)
        .bodyStyle(BODY_STYLE)
        .color(COLOR)
        .createdOn(CREATED_ON)
        .drivetrain(DRIVETRAIN)
        .engine(ENGINE)
        .fuelType(FUEL_TYPE)
        .licensePlate(LICENSE_PLATE)
        .licensePlateState(LICENSE_PLATE_STATE)
        .lastUpdatedOn(LAST_UPDATED_ON)
        .make(MAKE)
        .mileage(MILEAGE)
        .model(MODEL)
        .nickname(NICKNAME)
        .notes(NOTES)
        .purchaseDate(PURCHASE_DATE)
        .transmission(TRANSMISSION)
        .trim(TRIM)
        .vin(VIN)
        .year(YEAR)
        .build();
  }

  public static VehicleDetail getVehicleDetailStub(String id) {
    return VehicleDetail.builder()
        .id(id)
        .bodyStyle(BODY_STYLE)
        .color(COLOR)
        .createdOn(CREATED_ON)
        .drivetrain(DRIVETRAIN)
        .engine(ENGINE)
        .fuelType(FUEL_TYPE)
        .licensePlate(LICENSE_PLATE)
        .licensePlateState(LICENSE_PLATE_STATE)
        .lastUpdatedOn(LAST_UPDATED_ON)
        .make(MAKE)
        .mileage(MILEAGE)
        .model(MODEL)
        .nickname(NICKNAME)
        .notes(NOTES)
        .purchaseDate(PURCHASE_DATE)
        .transmission(TRANSMISSION)
        .trim(TRIM)
        .type(TYPE)
        .vin(VIN)
        .year(YEAR)
        .build();
  }
}
