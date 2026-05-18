package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents detailed information about a restaurant, including
 * its ID, name, address, and timestamps for creation and last update.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class RestaurantDetail {
  private String id;
  private Address address;
  private String createdBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;

  private String lastModifiedBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastUpdatedOn;

  private String cuisine;
  private String name;
  private String phoneNumber;
  private String sourceAmenity;
  private String type;
  private String website;
}
