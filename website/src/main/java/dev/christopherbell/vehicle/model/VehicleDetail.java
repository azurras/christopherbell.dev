package dev.christopherbell.vehicle.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API-facing vehicle details.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class VehicleDetail {
  private String id;
  private String bodyStyle;
  private String bodyClass;
  private String color;
  private String createdBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant createdOn;

  private String drivetrain;
  private Integer doors;
  private String engine;
  private String fuelType;
  private String gvwr;
  private String lastModifiedBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastUpdatedOn;

  private String licensePlate;
  private String licensePlateState;
  private String make;
  private String manufacturer;
  private String manufacturerId;
  private Integer mileage;
  private String model;
  private Map<String, String> nhtsaDecodedValues;
  private String nhtsaErrorCode;
  private String nhtsaErrorText;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant nhtsaLastDecodedOn;

  private String nickname;
  private String notes;
  private String plantCity;
  private String plantCountry;
  private String plantState;
  private LocalDate purchaseDate;
  private String series;
  private String transmission;
  private String trim;
  private String type;
  private String vin;
  private String vehicleType;
  private Integer year;
}
