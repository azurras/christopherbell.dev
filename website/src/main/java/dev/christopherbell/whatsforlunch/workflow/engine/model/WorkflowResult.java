package dev.christopherbell.whatsforlunch.workflow.engine.model;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the result of a workflow execution, including its status and timestamps.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class WorkflowResult {
  /**
   * The unique identifier for this workflow result instance.
   */
  private UUID id;

  /**
   * The status of the workflow, indicating whether it completed successfully, failed, or is in progress.
   */
  private WorkflowStatus status;

  /**
   * The timestamp when this workflow result instance was created.
   */
  private Instant createdAt;

  /**
   * The timestamp when this workflow result instance was last updated.
   */
  private Instant updatedAt;
}
