package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Durable import scheduler state for a restaurant data source.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("restaurant_import_state")
public class RestaurantImportState {
  @Id
  private String id;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastStartedOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastCompletedOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastFailedOn;

  private String lastCompletedMonth;
  private String lastFailureMessage;
  private RestaurantImportResult lastResult;
}
