package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Represents a restaurant entity with its details.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@CompoundIndexes({
    @CompoundIndex(
        name = "restaurant_coordinate_bounds",
        def = "{'address.latitude': 1, 'address.longitude': 1}")
})
@Document("whatsforlunch")
public class Restaurant {
  private final String type = "restaurant";

  @Id
  private String id;

  private Address address;

  @CreatedBy
  private String createdBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  @CreatedDate
  private Instant createdOn;

  @LastModifiedBy
  private String lastModifiedBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  @LastModifiedDate
  private Instant lastUpdatedOn;

  private String name;

  @Indexed(unique = true, sparse = true)
  private String normalizedName;
  private String cuisine;
  private String phoneNumber;
  private String sourceAmenity;
  private String website;
}
