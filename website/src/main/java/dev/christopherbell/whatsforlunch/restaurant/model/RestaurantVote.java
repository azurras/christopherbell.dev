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

/** Binary member vote for a WFL restaurant. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@CompoundIndex(name = "restaurant_account_unique", def = "{'restaurantId': 1, 'accountId': 1}", unique = true)
public class RestaurantVote {
  private final String type = "restaurant_vote";

  @Id
  private String id;

  @Indexed
  private String restaurantId;
  private String accountId;
  private RestaurantVoteValue vote;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastUpdatedOn;
}
