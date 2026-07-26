package dev.christopherbell.vehicle.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document(VehicleVinDecodeCache.COLLECTION)
public class VehicleVinDecodeCache {
  public static final String COLLECTION = "vehicle_vin_decode_cache";

  @Id private String vin;
  private VehicleVinDecodeResponse response;
  private String decoderVersion;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant refreshedOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant expiresOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant createdOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant lastUpdatedOn;

  public boolean isFresh(String expectedVersion, Instant now) {
    return response != null
        && expectedVersion != null
        && expectedVersion.equals(decoderVersion)
        && expiresOn != null
        && expiresOn.isAfter(now);
  }
}
