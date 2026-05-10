package dev.christopherbell.vehicle.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent state for RandomVIN collection throttling.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("vehicle_import_state")
public class RandomVinImportState {
  @Id
  private String id;

  private Integer callsToday;
  private LocalDate callsOnDate;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant disabledUntil;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant forbiddenOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastAttemptOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant lastFailureOn;

  private Integer lastFailureStatus;
  private Long lifetimeCalls;
  private Long lifetimeVinsProcessed;
  private String notes;
  private Boolean permanentlyDisabled;
  private Integer vinsProcessedToday;
}
