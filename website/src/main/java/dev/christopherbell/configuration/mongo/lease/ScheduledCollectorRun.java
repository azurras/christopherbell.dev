package dev.christopherbell.configuration.mongo.lease;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable, redacted observability for one scheduled collector attempt. */
@Builder
@Data
@Document(ScheduledCollectorRun.COLLECTION)
public class ScheduledCollectorRun {
  public static final String COLLECTION = "scheduled_collector_runs";

  @Id private String id;
  private String collectorName;
  private String ownerToken;
  private ScheduledCollectorRunStatus status;
  private Instant startedOn;
  private Instant completedOn;
  private String errorCategory;
}
