package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportRunStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/**
 * Durable import scheduler state for a restaurant data source.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
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
  private RestaurantImportRunStatus status;
  private String trigger;
  private String actorAccountId;
  private String lastErrorCategory;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastSkippedOn;

  private String lastSkippedTrigger;
}
