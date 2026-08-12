package dev.christopherbell.whatsforlunch.restaurant.importing;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/** Short-lived authorization record for applying a reviewed import preview. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class RestaurantImportPreviewDocument {
  @Id private String id;
  private String actorAccountId;
  private String checksum;
  private Instant createdOn;
  private Instant expiresOn;
  private Instant consumedOn;
  private RestaurantImportPreviewCounts counts;
}
