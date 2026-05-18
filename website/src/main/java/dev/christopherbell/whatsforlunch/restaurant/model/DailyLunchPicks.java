package dev.christopherbell.whatsforlunch.restaurant.model;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stores the random restaurant picks for a single lunch date.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("whatsforlunch_daily_picks")
public class DailyLunchPicks {
  @Id
  private String id;
  private String pickDate;
  private List<String> restaurantIds;
  private Instant generatedOn;
}
