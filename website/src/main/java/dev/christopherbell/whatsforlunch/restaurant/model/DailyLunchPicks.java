package dev.christopherbell.whatsforlunch.restaurant.model;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/**
 * Stores the random restaurant picks for a single lunch date.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class DailyLunchPicks {
  @Id
  private String id;
  private String pickDate;
  private List<String> restaurantIds;
  private Instant generatedOn;
}
