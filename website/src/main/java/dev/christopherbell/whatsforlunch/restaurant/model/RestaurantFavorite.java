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

/** Stores one WFL favorite marker per account and restaurant. */
@AllArgsConstructor
@Builder
@CompoundIndex(name = "restaurant_account_unique", def = "{'restaurantId': 1, 'accountId': 1}", unique = true)
@Data
@Document("whatsforlunch_favorites")
@NoArgsConstructor
public class RestaurantFavorite {
  private final String type = "restaurant_favorite";

  @Id
  private String id;

  @Indexed
  private String restaurantId;

  @Indexed
  private String accountId;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;
}
