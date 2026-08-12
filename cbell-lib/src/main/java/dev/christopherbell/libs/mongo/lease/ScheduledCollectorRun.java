package dev.christopherbell.libs.mongo.lease;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;

/** Durable, redacted observability for one scheduled collector attempt. */
@Builder
@Data
public class ScheduledCollectorRun {
  @Id private String id;
  private String collectorName;
  private String ownerToken;
  private ScheduledCollectorRunStatus status;
  private Instant startedOn;
  private Instant completedOn;
  private String errorCategory;
}
