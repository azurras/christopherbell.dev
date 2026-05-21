package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * User-owned default filters for What's For Lunch recommendations.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("whatsforlunch_preferences")
public class WhatsForLunchPreference {
  @Id
  private String accountId;

  private List<String> cuisines;
  private Integer radiusMiles;
}
