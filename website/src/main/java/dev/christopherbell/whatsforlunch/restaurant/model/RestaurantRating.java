package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** User-submitted whole-number rating for a WFL restaurant. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@CompoundIndex(name = "restaurant_account_unique", def = "{'restaurantId': 1, 'accountId': 1}", unique = true)
@Document("whatsforlunch_ratings")
public class RestaurantRating {
  private final String type = "restaurant_rating";

  @Id
  private String id;

  @Indexed
  private String restaurantId;
  private String accountId;
  private Integer rating;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastUpdatedOn;
}
